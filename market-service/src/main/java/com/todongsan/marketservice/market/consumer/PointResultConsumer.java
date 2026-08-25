package com.todongsan.marketservice.market.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.marketservice.market.entity.MarketPrediction;
import com.todongsan.marketservice.market.repository.MarketMapper;
import com.todongsan.marketservice.market.service.MarketPredictionTransactionService;
import com.todongsan.marketservice.market.type.PredictionStatus;
import com.todongsan.marketservice.outbox.OutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * point.deducted / point.deduction.failed 이벤트 컨슈머.
 *
 * Member-Point가 포인트 차감 결과를 이벤트로 발행하면,
 * 이 컨슈머가 Market prediction의 상태를 전이한다.
 *
 * 비정상 조합 매트릭스 (SAGA_DESIGN.md 참조):
 * - POINT_PENDING + deducted   → CONFIRMED (정상)
 * - POINT_PENDING + failed     → FAILED (정상)
 * - CONFIRMED     + deducted   → 멱등 무시
 * - FAILED        + deducted   → 환불 트리거 (늦은 성공 레이스)
 * - FAILED        + failed     → 멱등 무시
 * - SETTLED/REFUNDED + any     → 멱등 무시
 *
 * Inbox 패턴: processed_event 테이블로 중복 소비 차단.
 * 비즈니스 처리와 같은 트랜잭션에서 INSERT하여 원자적 중복 방지.
 */
@Slf4j
@Component
@ConditionalOnBean(KafkaTemplate.class)
@RequiredArgsConstructor
public class PointResultConsumer {

    private final MarketPredictionTransactionService transactionService;
    private final MarketMapper marketMapper;
    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {"point.deducted", "point.deduction.failed"},
            groupId = "market-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = null;
        try {
            JsonNode envelope = objectMapper.readTree(record.value());
            eventId = envelope.path("eventId").asText();
            String eventType = envelope.path("eventType").asText();
            JsonNode payload = envelope.path("payload");

            Long predictionId = payload.path("predictionId").asLong();
            Long memberId = payload.path("memberId").asLong();
            String idempotencyKey = payload.path("idempotencyKey").asText();
            String failReason = payload.has("failReason") ? payload.path("failReason").asText() : null;

            processEvent(eventId, eventType, predictionId, memberId, idempotencyKey, failReason);

            log.info("Point result processed: eventId={}, type={}, predictionId={}",
                    eventId, eventType, predictionId);
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Point result consumer error: eventId={}, error={}", eventId, e.getMessage(), e);
            // JSON 파싱 실패 등 복구 불가 → ack 종결
            if (eventId != null && e instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                ack.acknowledge();
            } else {
                throw new RuntimeException(e); // 기술 실패 → 재시도 → DLT
            }
        }
    }

    /**
     * 상태×이벤트 매트릭스 구현.
     * inbox(processed_event) INSERT와 비즈니스 처리를 같은 트랜잭션에서 수행한다.
     */
    @Transactional
    public void processEvent(String eventId, String eventType, Long predictionId,
                              Long memberId, String idempotencyKey, String failReason) {
        // 1. Inbox 중복 체크
        if (outboxMapper.existsProcessedEvent(eventId)) {
            log.debug("Duplicate event ignored: eventId={}", eventId);
            return;
        }

        // 2. Prediction 조회
        MarketPrediction prediction = marketMapper.selectPredictionById(predictionId);
        if (prediction == null) {
            log.warn("Prediction not found: predictionId={}, eventId={}", predictionId, eventId);
            outboxMapper.insertProcessedEvent(eventId, LocalDateTime.now());
            return;
        }

        // 3. 상태×이벤트 매트릭스
        PredictionStatus currentStatus = prediction.getStatus();

        if ("POINT_DEDUCTED".equals(eventType)) {
            handlePointDeducted(eventId, prediction, currentStatus);
        } else if ("POINT_DEDUCTION_FAILED".equals(eventType)) {
            handlePointDeductionFailed(eventId, prediction, currentStatus, failReason);
        } else {
            log.warn("Unknown event type: {}, eventId={}", eventType, eventId);
        }

        // 4. Inbox 기록 (같은 트랜잭션)
        outboxMapper.insertProcessedEvent(eventId, LocalDateTime.now());
    }

    private void handlePointDeducted(String eventId, MarketPrediction prediction,
                                      PredictionStatus currentStatus) {
        switch (currentStatus) {
            case POINT_PENDING, POINT_UNKNOWN -> {
                // 정상: 차감 성공 → 가격 확정
                boolean confirmed = transactionService.confirmPredictionForReconciliation(prediction.getId());
                if (confirmed) {
                    log.info("Prediction confirmed via event: predictionId={}", prediction.getId());
                } else {
                    log.warn("Prediction confirmation failed (market state changed): predictionId={}",
                            prediction.getId());
                }
            }
            case CONFIRMED, SETTLED -> {
                // 멱등 무시: 이미 확정된 상태
                log.debug("Idempotent ignore: predictionId={} already {}", prediction.getId(), currentStatus);
            }
            case FAILED -> {
                // 늦은 성공 레이스!
                // 타임아웃 대사가 FAILED로 확정한 뒤 point.deducted가 도착한 경우.
                // 포인트가 실제로 차감되었으므로 무시하면 유실 → 환불 트리거.
                log.warn("Late success detected! predictionId={}, eventId={}. Triggering refund.",
                        prediction.getId(), eventId);
                triggerRefund(prediction);
            }
            case REFUND_PENDING, REFUND_UNKNOWN, REFUNDED -> {
                // 이미 환불 진행 중이거나 완료 → 멱등 무시
                log.debug("Idempotent ignore: predictionId={} already in refund flow ({})",
                        prediction.getId(), currentStatus);
            }
        }
    }

    private void handlePointDeductionFailed(String eventId, MarketPrediction prediction,
                                             PredictionStatus currentStatus, String failReason) {
        switch (currentStatus) {
            case POINT_PENDING, POINT_UNKNOWN -> {
                // 정상: 차감 실패 → FAILED 전이
                transactionService.markPredictionFailed(
                        prediction.getId(),
                        failReason != null ? failReason : "POINT_DEDUCTION_FAILED");
                log.info("Prediction failed via event: predictionId={}, reason={}",
                        prediction.getId(), failReason);
            }
            case FAILED -> {
                // 멱등 무시: 이미 FAILED
                log.debug("Idempotent ignore: predictionId={} already FAILED", prediction.getId());
            }
            case CONFIRMED, SETTLED, REFUND_PENDING, REFUND_UNKNOWN, REFUNDED -> {
                // 이미 확정된 상태에서 failed 수신 → 멱등 무시
                log.debug("Idempotent ignore: predictionId={} already {}", prediction.getId(), currentStatus);
            }
        }
    }

    /**
     * 늦은 성공 환불 트리거.
     * FAILED 확정 후 point.deducted가 도착한 경우, 차감된 포인트를 환불한다.
     *
     * 환불 키: REFUND-{원 차감 idempotencyKey} → 원거래당 환불 1회 보장.
     * TODO: Phase 2 커밋 4에서 환불-원거래 연결 구현 시 실제 환불 로직 추가.
     */
    private void triggerRefund(MarketPrediction prediction) {
        // FAILED → REFUND_PENDING 전이
        marketMapper.updatePredictionToRefundPending(
                prediction.getId(),
                LocalDateTime.now());

        log.info("Refund triggered for late success: predictionId={}, memberId={}, amount={}",
                prediction.getId(), prediction.getMemberId(), prediction.getPointAmount());

        // TODO: Member-Point에 환불 요청 이벤트 발행 (Phase 2 커밋 4)
    }
}

package com.todongsan.memberpointservice.point.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.global.util.RequestHashUtil;
import com.todongsan.memberpointservice.member.repository.MemberRepository;
import com.todongsan.memberpointservice.outbox.entity.OutboxEvent;
import com.todongsan.memberpointservice.outbox.repository.OutboxEventRepository;
import com.todongsan.memberpointservice.point.entity.PointHistory;
import com.todongsan.memberpointservice.point.entity.PointHistoryType;
import com.todongsan.memberpointservice.point.entity.PointReferenceType;
import com.todongsan.memberpointservice.point.entity.PointTransactionStatus;
import com.todongsan.memberpointservice.point.repository.PointHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * prediction.refund.requested 이벤트 컨슈머.
 *
 * 늦은 성공 레이스 등으로 Market이 환불을 요청하면,
 * 포인트를 적립(환불)하고 point.refunded 이벤트를 발행한다.
 *
 * 환불-원거래 연결:
 * - 환불 키: REFUND-{원 차감 idempotencyKey} → 원거래당 환불 1회 구조적 보장
 * - 금액 상한: 환불 금액 ≤ 원 차감 금액 (서버 측 검증, 호출자 신뢰 금지)
 *
 * 보상 트랜잭션 원칙:
 * - (a) 보상은 비즈니스 사유로 실패하지 않도록 설계 — 탈퇴 회원 환불 허용
 * - (b) 기술 실패는 성공할 때까지 재시도
 * - (d) 보상은 멱등이어야 한다
 */
@Slf4j
@Component
public class PredictionRefundConsumer {

    private final MemberRepository memberRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public PredictionRefundConsumer(MemberRepository memberRepository,
                                    PointHistoryRepository pointHistoryRepository,
                                    OutboxEventRepository outboxEventRepository,
                                    PlatformTransactionManager transactionManager,
                                    ObjectMapper objectMapper) {
        this.memberRepository = memberRepository;
        this.pointHistoryRepository = pointHistoryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "prediction.refund.requested",
            groupId = "member-point-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = null;
        try {
            JsonNode envelope = objectMapper.readTree(record.value());
            eventId = envelope.path("eventId").asText();
            JsonNode payload = envelope.path("payload");

            Long memberId = payload.path("memberId").asLong();
            Long predictionId = payload.path("predictionId").asLong();
            Long marketId = payload.path("marketId").asLong();
            BigDecimal amount = new BigDecimal(payload.path("amount").asText());
            String refundKey = payload.path("refundIdempotencyKey").asText();
            String originalSpendKey = payload.path("originalSpendKey").asText();

            processRefund(memberId, predictionId, marketId, amount, refundKey, originalSpendKey);

            log.info("Prediction refund processed: eventId={}, memberId={}, predictionId={}",
                    eventId, memberId, predictionId);
            ack.acknowledge();

        } catch (Exception e) {
            // 환불은 보상 트랜잭션 — 기술 실패는 성공할 때까지 재시도
            log.error("Prediction refund failed (will retry): eventId={}, error={}", eventId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void processRefund(Long memberId, Long predictionId, Long marketId,
                                BigDecimal amount, String refundKey, String originalSpendKey) {
        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.DOWN);

        // 이미 처리된 환불인지 확인 (멱등성)
        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(refundKey);
        if (existing.isPresent()) {
            log.info("Refund already processed: key={}", refundKey);
            BigDecimal existingAmount = normalizedAmount;
            txTemplate.executeWithoutResult(status ->
                    insertRefundedOutboxEvent(memberId, predictionId, marketId, existingAmount, refundKey));
            return;
        }

        // 원거래 조회 + 금액 상한 검증
        PointHistory originalSpend = pointHistoryRepository.findByIdempotencyKey(originalSpendKey)
                .orElse(null);

        if (originalSpend != null) {
            // 환불 금액 ≤ 원 차감 금액 (서버 측 검증, 호출자 신뢰 금지)
            if (normalizedAmount.compareTo(originalSpend.getAmount()) > 0) {
                log.warn("Refund amount exceeds original spend: refundAmount={}, originalAmount={}, key={}",
                        normalizedAmount, originalSpend.getAmount(), refundKey);
                normalizedAmount = originalSpend.getAmount(); // 상한으로 클램프
            }
        } else {
            // 원거래를 못 찾아도 환불은 진행 (보상 트랜잭션은 비즈니스 사유로 실패하지 않아야 함)
            log.warn("Original spend not found for refund: originalKey={}, proceeding with requested amount", originalSpendKey);
        }

        // 단일 트랜잭션: 환불(earn) + point.refunded outbox INSERT
        BigDecimal finalAmount = normalizedAmount;
        txTemplate.executeWithoutResult(status -> {
            // 환불 = 포인트 적립
            // findById 사용 (탈퇴 회원도 환불 가능 — 보상 트랜잭션 원칙)
            memberRepository.earnPoint(memberId, finalAmount);

            var member = memberRepository.findById(memberId).orElse(null);
            BigDecimal balanceSnapshot = member != null ? member.getPointBalance() : BigDecimal.ZERO;

            String requestHash = RequestHashUtil.compute(
                    memberId, "REFUND_MARKET", finalAmount, "MARKET_PREDICTION", predictionId);

            PointHistory history = PointHistory.builder()
                    .memberId(memberId)
                    .type(PointHistoryType.REFUND_MARKET)
                    .amount(finalAmount)
                    .balanceSnapshot(balanceSnapshot)
                    .reason("Market 예측 환불 (늦은 성공)")
                    .referenceType(PointReferenceType.MARKET_PREDICTION)
                    .referenceId(predictionId)
                    .idempotencyKey(refundKey)
                    .requestHash(requestHash)
                    .status(PointTransactionStatus.SUCCEEDED)
                    .build();

            pointHistoryRepository.save(history);

            // point.refunded outbox INSERT (같은 트랜잭션)
            insertRefundedOutboxEvent(memberId, predictionId, marketId, finalAmount, refundKey);
        });
    }

    private void insertRefundedOutboxEvent(Long memberId, Long predictionId, Long marketId,
                                            BigDecimal amount, String refundKey) {
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "POINT_REFUNDED");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", LocalDateTime.now().toString());
        envelope.put("aggregateType", "POINT");
        envelope.put("aggregateId", memberId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId);
        payload.put("predictionId", predictionId);
        payload.put("marketId", marketId);
        payload.put("amount", amount);
        payload.put("refundIdempotencyKey", refundKey);
        envelope.put("payload", payload);

        try {
            outboxEventRepository.save(OutboxEvent.builder()
                    .aggregateType("POINT")
                    .aggregateId(memberId)
                    .eventType("POINT_REFUNDED")
                    .eventId(eventId)
                    .payload(objectMapper.writeValueAsString(envelope))
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        }
    }
}

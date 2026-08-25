package com.todongsan.memberpointservice.point.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.global.util.RequestHashUtil;
import com.todongsan.memberpointservice.member.entity.Member;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * prediction.created 이벤트 컨슈머.
 *
 * Market이 예측 참여를 생성하면 이 컨슈머가 포인트 차감을 수행하고,
 * 결과를 point.deducted / point.deduction.failed 이벤트로 발행한다.
 *
 * 핵심: spend 로직과 응답 이벤트 outbox INSERT가 같은 트랜잭션에 있어야 한다.
 * 그래야 "차감은 됐는데 응답 이벤트 유실" (이중 쓰기) 문제가 없다.
 *
 * 실패 분류:
 * - 잔액부족(비즈니스 거절) → point.deduction.failed 이벤트 발행 + ack (재시도 아님)
 * - 회원미존재 등 → ack 종결 (이벤트 없음, Market은 타임아웃 대사로 FAILED 확정)
 * - 기술실패 → 예외 전파 → 재시도 → DLT
 */
@Slf4j
@Component
public class PredictionCreatedConsumer {

    private final MemberRepository memberRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public PredictionCreatedConsumer(MemberRepository memberRepository,
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
            topics = "prediction.created",
            groupId = "member-point-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String sourceEventId = null;
        try {
            JsonNode envelope = objectMapper.readTree(record.value());
            sourceEventId = envelope.path("eventId").asText();
            JsonNode payload = envelope.path("payload");

            Long memberId = payload.path("memberId").asLong();
            Long predictionId = payload.path("predictionId").asLong();
            Long marketId = payload.path("marketId").asLong();
            BigDecimal amount = new BigDecimal(payload.path("pointAmount").asText());
            String idempotencyKey = payload.path("idempotencyKey").asText();

            processSpend(memberId, predictionId, marketId, amount, idempotencyKey);

            log.info("Prediction spend processed: eventId={}, memberId={}, predictionId={}",
                    sourceEventId, memberId, predictionId);
            ack.acknowledge();

        } catch (CustomException e) {
            if (isBusinessRejection(e.getErrorCode())) {
                log.warn("Prediction spend business rejection (no retry): eventId={}, errorCode={}",
                        sourceEventId, e.getErrorCode().getCode());
                ack.acknowledge();
            } else {
                log.error("Prediction spend technical failure (will retry): eventId={}", sourceEventId);
                throw e;
            }
        } catch (Exception e) {
            log.error("Prediction spend unrecoverable error: eventId={}, error={}",
                    sourceEventId, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    /**
     * 포인트 차감 + 응답 이벤트 outbox INSERT를 한 트랜잭션에서 수행한다.
     *
     * - 성공: point_history SUCCEEDED + outbox point.deducted
     * - 잔액부족: point_history FAILED + outbox point.deduction.failed
     * - 이미 처리됨: outbox만 INSERT (point_history가 inbox 역할)
     */
    private void processSpend(Long memberId, Long predictionId, Long marketId,
                               BigDecimal amount, String idempotencyKey) {
        BigDecimal normalizedAmount = amount.setScale(2, RoundingMode.DOWN);
        String requestHash = RequestHashUtil.compute(
                memberId, "SPEND_MARKET", amount, "MARKET_PREDICTION", predictionId);

        // 1. 낙관적 검사 (auto-commit read)
        Optional<PointHistory> existing = pointHistoryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            handleAlreadyProcessed(existing.get(), memberId, predictionId, marketId, requestHash);
            return;
        }

        // 2. 단일 트랜잭션: PENDING → 차감 → confirm/fail → outbox INSERT
        try {
            Boolean success = txTemplate.execute(status -> {
                Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
                        .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

                PointHistory pending = PointHistory.builder()
                        .memberId(memberId)
                        .type(PointHistoryType.SPEND_MARKET)
                        .amount(normalizedAmount)
                        .balanceSnapshot(BigDecimal.ZERO)
                        .reason("Market 예측 참여")
                        .referenceType(PointReferenceType.MARKET_PREDICTION)
                        .referenceId(predictionId)
                        .idempotencyKey(idempotencyKey)
                        .requestHash(requestHash)
                        .status(PointTransactionStatus.PENDING)
                        .build();

                pointHistoryRepository.saveAndFlush(pending);

                int affected = memberRepository.spendPoint(memberId, normalizedAmount);
                if (affected == 0) {
                    // 잔액 부족: FAILED 확정 + point.deduction.failed 이벤트
                    pending = pointHistoryRepository.save(pending);
                    pending.fail(member.getPointBalance(), ErrorCode.POINT_INSUFFICIENT.getCode());
                    insertOutboxEvent("POINT_DEDUCTION_FAILED", memberId, predictionId, marketId,
                            normalizedAmount, idempotencyKey, "POINT_INSUFFICIENT");
                    return false; // 잔액부족 시그널 (커밋은 됨)
                }

                // 성공: SUCCEEDED 확정 + point.deducted 이벤트
                Member updated = memberRepository.findByIdAndDeletedAtIsNull(memberId)
                        .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
                pending = pointHistoryRepository.save(pending);
                pending.confirm(updated.getPointBalance());
                insertOutboxEvent("POINT_DEDUCTED", memberId, predictionId, marketId,
                        normalizedAmount, idempotencyKey, null);
                return true;
            });

            if (Boolean.FALSE.equals(success)) {
                // 잔액부족 이벤트는 이미 발행됨. 비즈니스 거절이지만 이벤트로 처리 완료.
                log.info("Prediction spend insufficient balance: memberId={}, predictionId={}", memberId, predictionId);
            }

        } catch (DataIntegrityViolationException e) {
            // UNIQUE 위반 → 이미 처리된 요청. 재조회하여 응답 이벤트 보장.
            PointHistory winner = pointHistoryRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
            handleAlreadyProcessed(winner, memberId, predictionId, marketId, requestHash);
        }
    }

    /**
     * 이미 처리된 요청에 대해 응답 이벤트를 보장한다.
     * idempotency로 차감은 중복 실행되지 않지만, 응답 이벤트가 아직 안 나갔을 수 있다.
     * (예: 차감 성공 후 outbox INSERT 전에 프로세스 크래시)
     *
     * 중복 이벤트가 발행되더라도 Market의 inbox(processed_event)가 차단한다.
     */
    private void handleAlreadyProcessed(PointHistory history, Long memberId,
                                         Long predictionId, Long marketId, String requestHash) {
        if (history.getStatus() == PointTransactionStatus.PENDING) {
            // 선행 요청이 처리 중 — 재시도하면 확정된 상태를 볼 수 있음
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (!requestHash.equals(history.getRequestHash())) {
            log.warn("Idempotency key conflict for prediction spend: key={}", history.getIdempotencyKey());
            return; // 키 충돌은 무시 (Market에서 다른 키로 재시도할 것)
        }

        // 응답 이벤트 재발행 (Market inbox가 중복 차단)
        txTemplate.executeWithoutResult(status -> {
            if (history.getStatus() == PointTransactionStatus.SUCCEEDED) {
                insertOutboxEvent("POINT_DEDUCTED", memberId, predictionId, marketId,
                        history.getAmount(), history.getIdempotencyKey(), null);
            } else if (history.getStatus() == PointTransactionStatus.FAILED) {
                insertOutboxEvent("POINT_DEDUCTION_FAILED", memberId, predictionId, marketId,
                        history.getAmount(), history.getIdempotencyKey(), history.getFailReason());
            }
        });
    }

    private void insertOutboxEvent(String eventType, Long memberId, Long predictionId,
                                    Long marketId, BigDecimal amount, String idempotencyKey,
                                    String failReason) {
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", LocalDateTime.now().toString());
        envelope.put("aggregateType", "POINT");
        envelope.put("aggregateId", memberId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId);
        payload.put("predictionId", predictionId);
        payload.put("marketId", marketId);
        payload.put("amount", amount);
        payload.put("idempotencyKey", idempotencyKey);
        if (failReason != null) {
            payload.put("failReason", failReason);
        }
        envelope.put("payload", payload);

        try {
            outboxEventRepository.save(OutboxEvent.builder()
                    .aggregateType("POINT")
                    .aggregateId(memberId)  // 메시지 키 = memberId
                    .eventType(eventType)
                    .eventId(eventId)
                    .payload(objectMapper.writeValueAsString(envelope))
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        }
    }

    private boolean isBusinessRejection(ErrorCode errorCode) {
        return switch (errorCode) {
            case MEMBER_NOT_FOUND,
                 POINT_INVALID_AMOUNT,
                 VALIDATION_FAILED,
                 IDEMPOTENCY_KEY_REQUIRED -> true;
            default -> false;
        };
    }
}

package com.todongsan.memberpointservice.point.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.memberpointservice.global.exception.CustomException;
import com.todongsan.memberpointservice.global.exception.ErrorCode;
import com.todongsan.memberpointservice.point.dto.request.EarnRequest;
import com.todongsan.memberpointservice.point.service.PointInternalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.math.BigDecimal;

/**
 * Battle 보상 이벤트 컨슈머.
 *
 * 실패 분류 (기존 "4xx 재시도 금지" 정책의 컨슈머 버전):
 * - 비즈니스 거절 (MEMBER_NOT_FOUND, POINT_INVALID_AMOUNT 등): 재시도 무의미 → FAILED 기록 + 로그 종결 + ack
 * - 이미 처리됨 (ALREADY_PROCESSED): 멱등성으로 정상 → ack
 * - 기술 실패 (DB 순단, 타임아웃 등): 재시도 가능 → 예외 전파 → Spring Kafka 백오프 재시도 → DLT
 *
 * enable.auto.commit=false + ack-mode=manual → 처리 완료 후 수동 ack (at-least-once)
 * 중복 소비는 earn()의 idempotencyKey 멱등성으로 해소
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BattleRewardConsumer {

    private final PointInternalService pointInternalService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "battle.reward.requested",
            groupId = "member-point-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = null;
        try {
            JsonNode envelope = objectMapper.readTree(record.value());
            eventId = envelope.path("eventId").asText();
            JsonNode payload = envelope.path("payload");

            String idempotencyKey = payload.path("idempotencyKey").asText();

            EarnRequest request = buildEarnRequest(
                    payload.path("memberId").asLong(),
                    payload.path("type").asText(),
                    new BigDecimal(payload.path("amount").asText()),
                    payload.path("referenceType").asText(),
                    payload.path("referenceId").asLong(),
                    payload.path("reason").asText()
            );

            pointInternalService.earn(idempotencyKey, request);

            log.info("Battle reward processed: eventId={}, memberId={}, key={}",
                    eventId, payload.path("memberId").asLong(), idempotencyKey);
            ack.acknowledge();

        } catch (CustomException e) {
            // 비즈니스 거절 vs 이미 처리됨 분류
            if (isBusinessRejection(e.getErrorCode())) {
                // 재시도해도 영원히 같은 결과 → 로그 + ack로 종결
                log.warn("Battle reward business rejection (no retry): eventId={}, errorCode={}, message={}",
                        eventId, e.getErrorCode().getCode(), e.getMessage());
                ack.acknowledge();
            } else {
                // 기술 실패 → 예외 전파 → Spring Kafka 재시도 → DLT
                log.error("Battle reward technical failure (will retry): eventId={}, errorCode={}",
                        eventId, e.getErrorCode().getCode());
                throw e;
            }
        } catch (Exception e) {
            // JSON 파싱 실패 등 포이즌 메시지 → 재시도 무의미 → ack로 종결
            log.error("Battle reward unrecoverable error (no retry): eventId={}, error={}",
                    eventId, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    /**
     * 비즈니스 거절: 재시도해도 결과가 바뀌지 않는 에러.
     * 기존 REST의 "4xx 재시도 금지" 정책의 컨슈머 버전.
     */
    private boolean isBusinessRejection(ErrorCode errorCode) {
        return switch (errorCode) {
            case MEMBER_NOT_FOUND,
                 POINT_INVALID_AMOUNT,
                 POINT_INVALID_REFERENCE_TYPE,
                 VALIDATION_FAILED,
                 IDEMPOTENCY_KEY_CONFLICT,
                 IDEMPOTENCY_KEY_REQUIRED -> true;
            // POINT_TRANSACTION_ALREADY_PROCESSED는 멱등성 정상 동작 → 여기 안 옴 (earn이 200 반환)
            default -> false;
        };
    }

    /**
     * EarnRequest는 @Getter만 있고 setter가 없으므로 리플렉션으로 생성.
     */
    private EarnRequest buildEarnRequest(Long memberId, String type, BigDecimal amount,
                                          String referenceType, Long referenceId, String reason) {
        try {
            EarnRequest request = new EarnRequest();
            setField(request, "memberId", memberId);
            setField(request, "type", type);
            setField(request, "amount", amount);
            setField(request, "referenceType", referenceType);
            setField(request, "referenceId", referenceId);
            setField(request, "reason", reason);
            return request;
        } catch (Exception e) {
            throw new RuntimeException("EarnRequest 생성 실패", e);
        }
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}

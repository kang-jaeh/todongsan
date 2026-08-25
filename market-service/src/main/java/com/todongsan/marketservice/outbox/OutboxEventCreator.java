package com.todongsan.marketservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Market Outbox 이벤트 생성.
 * 비즈니스 변경과 같은 트랜잭션 안에서 호출해야 한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventCreator {

    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    /**
     * prediction.created 이벤트를 outbox에 저장한다.
     */
    public String createPredictionCreatedEvent(Long marketId, Long memberId, Long predictionId,
                                                BigDecimal pointAmount, String idempotencyKey) {
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "PREDICTION_CREATED");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", LocalDateTime.now().toString());
        envelope.put("aggregateType", "MARKET_PREDICTION");
        envelope.put("aggregateId", predictionId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId);
        payload.put("marketId", marketId);
        payload.put("predictionId", predictionId);
        payload.put("pointAmount", pointAmount);
        payload.put("idempotencyKey", idempotencyKey);
        envelope.put("payload", payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        }

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("MARKET_PREDICTION");
        event.setAggregateId(memberId);  // 메시지 키 = memberId (순서 보장)
        event.setEventType("PREDICTION_CREATED");
        event.setEventId(eventId);
        event.setPayload(json);

        outboxMapper.insertOutboxEvent(event);
        return eventId;
    }

    /**
     * prediction.refund.requested 이벤트를 outbox에 저장한다.
     * 늦은 성공 레이스 등으로 환불이 필요할 때 사용.
     */
    public String createRefundRequestedEvent(Long marketId, Long memberId, Long predictionId,
                                              BigDecimal amount, String refundIdempotencyKey) {
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "PREDICTION_REFUND_REQUESTED");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", LocalDateTime.now().toString());
        envelope.put("aggregateType", "MARKET_PREDICTION");
        envelope.put("aggregateId", predictionId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId);
        payload.put("marketId", marketId);
        payload.put("predictionId", predictionId);
        payload.put("amount", amount);
        payload.put("refundIdempotencyKey", refundIdempotencyKey);
        // 원 차감 키를 환불 키에서 파생: REFUND-{원키} → 원키
        payload.put("originalSpendKey", refundIdempotencyKey.replaceFirst("^REFUND-", ""));
        envelope.put("payload", payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        }

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("MARKET_PREDICTION");
        event.setAggregateId(memberId);
        event.setEventType("PREDICTION_REFUND_REQUESTED");
        event.setEventId(eventId);
        event.setPayload(json);

        outboxMapper.insertOutboxEvent(event);
        return eventId;
    }

    /**
     * market.voided 감사/알림용 이벤트를 outbox에 저장한다.
     * 무효화 사실의 감사 기록 및 향후 알림 확장용 이벤트.
     * 환불 실행은 MarketRefundService의 REST batch가 담당한다 (소비자 없음, 의도된 설계).
     */
    public String createMarketVoidedEvent(Long marketId, Long marketVoidId, int refundCount) {
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "MARKET_VOIDED");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", LocalDateTime.now().toString());
        envelope.put("aggregateType", "MARKET");
        envelope.put("aggregateId", marketId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("marketId", marketId);
        payload.put("marketVoidId", marketVoidId);
        payload.put("refundCount", refundCount);
        envelope.put("payload", payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        }

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("MARKET");
        event.setAggregateId(marketId);
        event.setEventType("MARKET_VOIDED");
        event.setEventId(eventId);
        event.setPayload(json);

        outboxMapper.insertOutboxEvent(event);
        return eventId;
    }
}

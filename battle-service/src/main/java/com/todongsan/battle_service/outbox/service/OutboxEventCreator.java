package com.todongsan.battle_service.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todongsan.battle_service.outbox.entity.OutboxEvent;
import com.todongsan.battle_service.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox 이벤트를 봉투(envelope) 표준에 맞게 생성하고 저장한다.
 * 반드시 비즈니스 변경과 같은 트랜잭션 안에서 호출해야 한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventCreator {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Battle 보상 요청 이벤트를 outbox에 저장한다.
     *
     * @return 생성된 eventId (UUID)
     */
    public String createRewardEvent(Long battleId, Long memberId, String type,
                                     BigDecimal amount, String reason, String idempotencyKey) {
        String eventId = UUID.randomUUID().toString();

        // 봉투(envelope) 구조: CLAUDE.md 이벤트 표준
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "BATTLE_REWARD_REQUESTED");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", LocalDateTime.now().toString());
        envelope.put("aggregateType", "BATTLE");
        envelope.put("aggregateId", battleId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memberId", memberId);
        payload.put("type", type);
        payload.put("amount", amount);
        payload.put("referenceType", "BATTLE");
        payload.put("referenceId", battleId);
        payload.put("reason", reason);
        payload.put("idempotencyKey", idempotencyKey);
        envelope.put("payload", payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Outbox 이벤트 직렬화 실패", e);
        }

        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType("BATTLE")
                .aggregateId(battleId)
                .eventType("BATTLE_REWARD_REQUESTED")
                .eventId(eventId)
                .payload(json)
                .build());

        return eventId;
    }
}

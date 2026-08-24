package com.todongsan.battle_service.outbox.publisher;

import com.todongsan.battle_service.outbox.entity.OutboxEvent;
import com.todongsan.battle_service.outbox.entity.OutboxStatus;
import com.todongsan.battle_service.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 폴링 퍼블리셔.
 *
 * PENDING 상태의 outbox_event를 주기적으로 조회하여 Kafka에 발행하고
 * PUBLISHED로 전이한다.
 *
 * at-least-once 발행 보장:
 * - Kafka 발행 성공 후 DB UPDATE 전에 프로세스가 죽으면 다음 폴링에서 재발행된다.
 * - 컨슈머는 eventId 기반 멱등성으로 중복을 해소한다.
 *
 * Phase 6에서 Debezium CDC로 전환 예정. 폴링 방식 대비 발행 지연 감소 기대.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String TOPIC = "battle.reward.requested";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        for (OutboxEvent event : events) {
            try {
                // 메시지 키 = aggregateId (memberId가 아닌 battleId)
                // 같은 Battle의 이벤트가 같은 파티션에 순서 보장됨
                kafkaTemplate.send(TOPIC, String.valueOf(event.getAggregateId()), event.getPayload())
                        .get(); // 동기 대기 — 발행 성공 확인 후 PUBLISHED 전이
                event.markPublished();
                log.debug("Outbox published: eventId={}, type={}", event.getEventId(), event.getEventType());
            } catch (Exception e) {
                // 발행 실패 시 다음 폴링에서 재시도. PENDING 유지.
                log.warn("Outbox publish failed: eventId={}, error={}", event.getEventId(), e.getMessage());
                break; // 순서 보장을 위해 실패 시 나머지도 중단
            }
        }
    }
}

package com.todongsan.memberpointservice.outbox.publisher;

import com.todongsan.memberpointservice.outbox.entity.OutboxEvent;
import com.todongsan.memberpointservice.outbox.entity.OutboxStatus;
import com.todongsan.memberpointservice.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Member-Point Outbox 폴링 퍼블리셔.
 *
 * point.deducted / point.deduction.failed 이벤트를 Kafka로 발행한다.
 * 메시지 키 = memberId (같은 회원의 차감·환불 순서 보장).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)  // 1초 간격 — 양방향 Saga 지연 최소화
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        for (OutboxEvent event : events) {
            try {
                // eventType에서 토픽명 결정: POINT_DEDUCTED → point.deducted
                String topic = event.getEventType().toLowerCase().replace('_', '.');
                // 메시지 키 = memberId (aggregateId에 memberId를 저장)
                kafkaTemplate.send(topic, String.valueOf(event.getAggregateId()), event.getPayload())
                        .get();
                event.markPublished();
                log.debug("Outbox published: eventId={}, topic={}", event.getEventId(), topic);
            } catch (Exception e) {
                log.warn("Outbox publish failed: eventId={}, error={}", event.getEventId(), e.getMessage());
                break;
            }
        }
    }
}

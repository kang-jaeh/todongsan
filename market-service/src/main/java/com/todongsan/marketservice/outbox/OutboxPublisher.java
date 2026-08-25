package com.todongsan.marketservice.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@ConditionalOnBean(KafkaTemplate.class)
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxMapper outboxMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxMapper.selectPendingEvents();
        for (OutboxEvent event : events) {
            try {
                // eventType에서 토픽명: PREDICTION_CREATED → prediction.created
                String topic = event.getEventType().toLowerCase().replace('_', '.');
                kafkaTemplate.send(topic, String.valueOf(event.getAggregateId()), event.getPayload())
                        .get();
                outboxMapper.markPublished(event.getId(), LocalDateTime.now());
                log.debug("Outbox published: eventId={}, topic={}", event.getEventId(), topic);
            } catch (Exception e) {
                log.warn("Outbox publish failed: eventId={}, error={}", event.getEventId(), e.getMessage());
                break;
            }
        }
    }
}

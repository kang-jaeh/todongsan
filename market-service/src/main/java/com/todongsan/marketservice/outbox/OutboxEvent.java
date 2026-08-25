package com.todongsan.marketservice.outbox;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class OutboxEvent {
    private Long id;
    private String aggregateType;
    private Long aggregateId;
    private String eventType;
    private String eventId;
    private String payload;
    private String status;  // PENDING / PUBLISHED
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
}

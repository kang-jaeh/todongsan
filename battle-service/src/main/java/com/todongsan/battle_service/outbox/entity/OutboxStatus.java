package com.todongsan.battle_service.outbox.entity;

public enum OutboxStatus {
    PENDING,    // 발행 대기
    PUBLISHED   // Kafka 발행 완료
}

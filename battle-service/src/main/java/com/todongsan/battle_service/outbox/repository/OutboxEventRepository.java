package com.todongsan.battle_service.outbox.repository;

import com.todongsan.battle_service.outbox.entity.OutboxEvent;
import com.todongsan.battle_service.outbox.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);
}

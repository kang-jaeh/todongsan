package com.todongsan.memberpointservice.outbox.repository;

import com.todongsan.memberpointservice.outbox.entity.OutboxEvent;
import com.todongsan.memberpointservice.outbox.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);
}

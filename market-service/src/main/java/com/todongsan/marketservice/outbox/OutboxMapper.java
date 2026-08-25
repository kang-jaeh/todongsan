package com.todongsan.marketservice.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxMapper {

    void insertOutboxEvent(OutboxEvent event);

    List<OutboxEvent> selectPendingEvents();

    int markPublished(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt);

    // Inbox: 이벤트 중복 소비 차단
    boolean existsProcessedEvent(@Param("eventId") String eventId);

    void insertProcessedEvent(@Param("eventId") String eventId, @Param("processedAt") LocalDateTime processedAt);
}

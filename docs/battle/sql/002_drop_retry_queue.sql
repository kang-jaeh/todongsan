-- RetryQueue → Kafka+Outbox 전환에 따른 테이블 제거.
-- Kafka+DLT가 재시도와 실패 메시지 보존을 담당하므로 RetryQueue 불필요.
-- 기존 "3회 실패 → 영구 포기(유실 가능)" → "DLT에 메시지 보존(유실 불가)" 개선.

USE battle;

DROP TABLE IF EXISTS point_reward_retry_queue;

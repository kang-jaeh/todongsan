-- k6 부하테스트용 시딩/리셋 스크립트
-- 테스트 전에 실행하여 DB를 초기 상태로 리셋한다.

USE memberpoint;

-- 기존 테스트 데이터 초기화
DELETE FROM point_history WHERE idempotency_key LIKE 'k6:%';
DELETE FROM outbox_event;

-- 테스트 회원 생성 (없으면 INSERT, 있으면 잔액 리셋)
INSERT INTO member (id, nickname, point_balance, role, oauth_provider, oauth_id, created_at, updated_at)
VALUES (9001, 'k6-user-A', 0.00, 'USER', 'KAKAO', 'k6-oauth-A', NOW(), NOW())
ON DUPLICATE KEY UPDATE point_balance = 0.00, deleted_at = NULL;

INSERT INTO member (id, nickname, point_balance, role, oauth_provider, oauth_id, created_at, updated_at)
VALUES (9002, 'k6-user-B', 1000.00, 'USER', 'KAKAO', 'k6-oauth-B', NOW(), NOW())
ON DUPLICATE KEY UPDATE point_balance = 1000.00, deleted_at = NULL;

-- 시나리오 A용: 멱등성 테스트 회원 (잔액 100P)
INSERT INTO member (id, nickname, point_balance, role, oauth_provider, oauth_id, created_at, updated_at)
VALUES (9003, 'k6-user-idem', 100.00, 'USER', 'KAKAO', 'k6-oauth-idem', NOW(), NOW())
ON DUPLICATE KEY UPDATE point_balance = 100.00, deleted_at = NULL;

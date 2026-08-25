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

-- 부하테스트용: 50명 회원 (VU별 분산, 행 락 경합 방지)
INSERT INTO member (id, nickname, point_balance, role, oauth_provider, oauth_id, created_at, updated_at)
VALUES
  (9100, 'k6-load-00', 0, 'USER', 'KAKAO', 'k6-load-00', NOW(), NOW()),
  (9101, 'k6-load-01', 0, 'USER', 'KAKAO', 'k6-load-01', NOW(), NOW()),
  (9102, 'k6-load-02', 0, 'USER', 'KAKAO', 'k6-load-02', NOW(), NOW()),
  (9103, 'k6-load-03', 0, 'USER', 'KAKAO', 'k6-load-03', NOW(), NOW()),
  (9104, 'k6-load-04', 0, 'USER', 'KAKAO', 'k6-load-04', NOW(), NOW()),
  (9105, 'k6-load-05', 0, 'USER', 'KAKAO', 'k6-load-05', NOW(), NOW()),
  (9106, 'k6-load-06', 0, 'USER', 'KAKAO', 'k6-load-06', NOW(), NOW()),
  (9107, 'k6-load-07', 0, 'USER', 'KAKAO', 'k6-load-07', NOW(), NOW()),
  (9108, 'k6-load-08', 0, 'USER', 'KAKAO', 'k6-load-08', NOW(), NOW()),
  (9109, 'k6-load-09', 0, 'USER', 'KAKAO', 'k6-load-09', NOW(), NOW()),
  (9110, 'k6-load-10', 0, 'USER', 'KAKAO', 'k6-load-10', NOW(), NOW()),
  (9111, 'k6-load-11', 0, 'USER', 'KAKAO', 'k6-load-11', NOW(), NOW()),
  (9112, 'k6-load-12', 0, 'USER', 'KAKAO', 'k6-load-12', NOW(), NOW()),
  (9113, 'k6-load-13', 0, 'USER', 'KAKAO', 'k6-load-13', NOW(), NOW()),
  (9114, 'k6-load-14', 0, 'USER', 'KAKAO', 'k6-load-14', NOW(), NOW()),
  (9115, 'k6-load-15', 0, 'USER', 'KAKAO', 'k6-load-15', NOW(), NOW()),
  (9116, 'k6-load-16', 0, 'USER', 'KAKAO', 'k6-load-16', NOW(), NOW()),
  (9117, 'k6-load-17', 0, 'USER', 'KAKAO', 'k6-load-17', NOW(), NOW()),
  (9118, 'k6-load-18', 0, 'USER', 'KAKAO', 'k6-load-18', NOW(), NOW()),
  (9119, 'k6-load-19', 0, 'USER', 'KAKAO', 'k6-load-19', NOW(), NOW()),
  (9120, 'k6-load-20', 0, 'USER', 'KAKAO', 'k6-load-20', NOW(), NOW()),
  (9121, 'k6-load-21', 0, 'USER', 'KAKAO', 'k6-load-21', NOW(), NOW()),
  (9122, 'k6-load-22', 0, 'USER', 'KAKAO', 'k6-load-22', NOW(), NOW()),
  (9123, 'k6-load-23', 0, 'USER', 'KAKAO', 'k6-load-23', NOW(), NOW()),
  (9124, 'k6-load-24', 0, 'USER', 'KAKAO', 'k6-load-24', NOW(), NOW()),
  (9125, 'k6-load-25', 0, 'USER', 'KAKAO', 'k6-load-25', NOW(), NOW()),
  (9126, 'k6-load-26', 0, 'USER', 'KAKAO', 'k6-load-26', NOW(), NOW()),
  (9127, 'k6-load-27', 0, 'USER', 'KAKAO', 'k6-load-27', NOW(), NOW()),
  (9128, 'k6-load-28', 0, 'USER', 'KAKAO', 'k6-load-28', NOW(), NOW()),
  (9129, 'k6-load-29', 0, 'USER', 'KAKAO', 'k6-load-29', NOW(), NOW()),
  (9130, 'k6-load-30', 0, 'USER', 'KAKAO', 'k6-load-30', NOW(), NOW()),
  (9131, 'k6-load-31', 0, 'USER', 'KAKAO', 'k6-load-31', NOW(), NOW()),
  (9132, 'k6-load-32', 0, 'USER', 'KAKAO', 'k6-load-32', NOW(), NOW()),
  (9133, 'k6-load-33', 0, 'USER', 'KAKAO', 'k6-load-33', NOW(), NOW()),
  (9134, 'k6-load-34', 0, 'USER', 'KAKAO', 'k6-load-34', NOW(), NOW()),
  (9135, 'k6-load-35', 0, 'USER', 'KAKAO', 'k6-load-35', NOW(), NOW()),
  (9136, 'k6-load-36', 0, 'USER', 'KAKAO', 'k6-load-36', NOW(), NOW()),
  (9137, 'k6-load-37', 0, 'USER', 'KAKAO', 'k6-load-37', NOW(), NOW()),
  (9138, 'k6-load-38', 0, 'USER', 'KAKAO', 'k6-load-38', NOW(), NOW()),
  (9139, 'k6-load-39', 0, 'USER', 'KAKAO', 'k6-load-39', NOW(), NOW()),
  (9140, 'k6-load-40', 0, 'USER', 'KAKAO', 'k6-load-40', NOW(), NOW()),
  (9141, 'k6-load-41', 0, 'USER', 'KAKAO', 'k6-load-41', NOW(), NOW()),
  (9142, 'k6-load-42', 0, 'USER', 'KAKAO', 'k6-load-42', NOW(), NOW()),
  (9143, 'k6-load-43', 0, 'USER', 'KAKAO', 'k6-load-43', NOW(), NOW()),
  (9144, 'k6-load-44', 0, 'USER', 'KAKAO', 'k6-load-44', NOW(), NOW()),
  (9145, 'k6-load-45', 0, 'USER', 'KAKAO', 'k6-load-45', NOW(), NOW()),
  (9146, 'k6-load-46', 0, 'USER', 'KAKAO', 'k6-load-46', NOW(), NOW()),
  (9147, 'k6-load-47', 0, 'USER', 'KAKAO', 'k6-load-47', NOW(), NOW()),
  (9148, 'k6-load-48', 0, 'USER', 'KAKAO', 'k6-load-48', NOW(), NOW()),
  (9149, 'k6-load-49', 0, 'USER', 'KAKAO', 'k6-load-49', NOW(), NOW())
ON DUPLICATE KEY UPDATE point_balance = 0, deleted_at = NULL;

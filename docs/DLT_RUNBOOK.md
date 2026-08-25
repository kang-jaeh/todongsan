# DLT (Dead Letter Topic) 운영 Runbook

## 개요

DLT는 Kafka 컨슈머가 백오프 재시도(3회) 후에도 처리 실패한 메시지를 격리하는 토픽이다.
DLT에 들어간 메시지는 **자동 처리되지 않으며**, 운영자가 원인을 분류하고 수동으로 재처리해야 한다.

기존 RetryQueue(3회 실패 → 영구 포기 = 유실)와 달리, DLT는 메시지를 보존하므로 유실이 없다.

---

## 토픽 목록

| 원본 토픽 | DLT 토픽 | 소비자 |
|----------|---------|--------|
| `battle.reward.requested` | `battle.reward.requested.dlt` | member-point-service |

---

## 1단계: DLT 메시지 조회

### Kafka UI (권장)
1. http://localhost:8989 접속
2. Topics → `battle.reward.requested.dlt` 선택
3. Messages 탭에서 메시지 내용 확인

### CLI
```bash
docker exec todongsan-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic battle.reward.requested.dlt \
  --from-beginning \
  --max-messages 10
```

### 메시지 구조 (봉투 표준)
```json
{
  "eventId": "uuid-...",
  "eventType": "BATTLE_REWARD_REQUESTED",
  "schemaVersion": 1,
  "occurredAt": "2026-08-24T20:00:00",
  "aggregateType": "BATTLE",
  "aggregateId": 42,
  "payload": {
    "memberId": 1,
    "type": "EARN_VOTE_WIN",
    "amount": 10.00,
    "referenceType": "BATTLE",
    "referenceId": 42,
    "reason": "배틀 승리 보상",
    "idempotencyKey": "battle:settle:42:member:1"
  }
}
```

DLT 메시지에는 Kafka 헤더로 원인 정보가 추가된다:
- `kafka_dlt-exception-fqcn`: 예외 클래스명
- `kafka_dlt-exception-message`: 예외 메시지
- `kafka_dlt-original-topic`: 원본 토픽
- `kafka_dlt-original-partition`: 원본 파티션
- `kafka_dlt-original-offset`: 원본 오프셋

---

## 2단계: 원인 분류

### A. 기술 실패 (일시 장애) — 재처리 대상

| 증상 | 원인 | 조치 |
|------|------|------|
| DB 커넥션 에러 | Member-Point DB 순단 | DB 복구 확인 후 재발행 |
| 타임아웃 | Member-Point 서비스 과부하 | 서비스 정상화 확인 후 재발행 |
| UnknownHostException | 네트워크 일시 장애 | 네트워크 복구 확인 후 재발행 |

### B. 비즈니스 거절 — 재처리 대상 아님

> 컨슈머 코드(BattleRewardConsumer)에서 비즈니스 거절은 ack 처리하므로
> 정상적으로는 DLT에 도달하지 않는다. 아래 케이스가 발견되면 컨슈머 코드 버그를 의심할 것.

| 증상 | 원인 | 조치 |
|------|------|------|
| MEMBER_NOT_FOUND | 탈퇴 회원 | 재처리 불필요. 로그 확인 후 종결 |
| POINT_INVALID_AMOUNT | 금액 0 이하 | 발행 측(Battle) 데이터 확인 |
| IDEMPOTENCY_KEY_CONFLICT | 같은 키 다른 내용 | 발행 측 멱등키 생성 로직 확인 |

### C. 포이즌 메시지 — 재처리 불가

| 증상 | 원인 | 조치 |
|------|------|------|
| JSON 파싱 실패 | 메시지 형식 깨짐 | 발행 측 직렬화 코드 확인. 메시지 폐기 |
| 필수 필드 누락 | 스키마 불일치 | schemaVersion 확인. 호환성 처리 추가 |

---

## 3단계: 수동 재발행

원인이 기술 실패(A)로 확인되고, 장애가 해소된 후 수행한다.

### 방법 1: 원본 토픽에 재발행 (권장)

DLT 메시지의 value(JSON)를 그대로 원본 토픽에 발행한다.
컨슈머의 멱등성(idempotencyKey)이 중복을 차단하므로 안전하다.

```bash
# 1. DLT에서 메시지 추출 (JSON value)
docker exec todongsan-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic battle.reward.requested.dlt \
  --from-beginning \
  --max-messages 1 > /tmp/dlt-message.json

# 2. 원본 토픽에 재발행
docker exec -i todongsan-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic battle.reward.requested < /tmp/dlt-message.json
```

### 방법 2: REST API 직접 호출

DLT 메시지의 payload에서 정보를 추출해 Member-Point API를 직접 호출한다.
멱등키가 있으므로 중복 처리 걱정 없다.

```bash
curl -X POST http://localhost:8080/internal/api/v1/points/earn \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: battle:settle:42:member:1" \
  -d '{
    "memberId": 1,
    "type": "EARN_VOTE_WIN",
    "amount": 10.00,
    "referenceType": "BATTLE",
    "referenceId": 42,
    "reason": "배틀 승리 보상 (DLT 수동 재처리)"
  }'
```

---

## 4단계: 처리 완료 후

1. DLT 메시지가 재처리되었는지 확인:
   - `GET /internal/api/v1/points/transactions?idempotencyKey=battle:settle:42:member:1`
   - status가 `PROCESSED`이면 성공

2. DLT 토픽 정리:
   - DLT 토픽은 retention 설정에 따라 자동 만료됨
   - 즉시 정리가 필요하면 토픽의 retention.ms를 일시적으로 줄인 후 복원

---

## 모니터링 (Phase 3 예정)

- Prometheus 메트릭: DLT 유입 수, 컨슈머 lag
- Grafana 알림: DLT에 새 메시지 유입 시 알림
- 정기 점검: 매일 DLT 토픽 메시지 수 확인 (0이 정상)

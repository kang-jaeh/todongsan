# SAGA_DESIGN.md — Market 예측 참여 Saga 설계

## 1. A안(동기 유지) vs B안(전체 Choreography) 판단 근거

### A안: spend는 REST 동기 유지, 환불/정산만 이벤트

- 장점: 사용자가 즉시 성공/실패를 알 수 있음. 현재 코드 변경 최소
- 단점: Market이 Member-Point REST API에 강하게 결합. 장애 전파. "Saga 구현했다"의 실체가 얇음

### B안: 전체 Choreography Saga (채택)

- 장점: 양방향 이벤트, 늦은 성공 매트릭스, inbox 패턴 등 정석 Saga의 모든 요소가 실체를 가짐
- 단점: 참여 API가 즉시 결과를 반환하지 못함 (202 Accepted + 폴링)
- **"즉시 결과" 문제의 해결**: POINT_PENDING 선삽입이 이미 있으므로, 202 Accepted + predictionId 반환 후
  클라이언트가 상태를 폴링하는 구조. 결제 시스템이 실제로 이렇게 동작함 ("결제 처리 중...")
- **이 프로젝트의 목적**: "정석 Saga를 타협 없이 구현하고 증거를 남긴다"

### 면접 최상급 답변 뼈대

"왜 spend까지 비동기로 했나?"
→ 트레이드오프를 알고 선택했다. 대안(A: 동기 유지)과 하이브리드(동기 + 이벤트는 보상용만)도 알지만,
이 프로젝트에서는 Saga의 모든 패턴(outbox, inbox, 비정상 조합 매트릭스, 보상 트랜잭션)을
실제로 구현하고 검증하는 것이 목적이었다. 프로덕션에서는 응답 속도 요구사항에 따라 A안이 합리적일 수 있다.

---

## 2. Saga 단계 분류

| 단계 | 서비스 | 분류 | 설명 |
|------|--------|------|------|
| 예측 생성 | Market | 보상 가능 (compensatable) | FAILED 전이로 되돌림 |
| 포인트 차감 | Member-Point | 피벗 (pivot) | 성공 후엔 환불로만 되돌림 |
| 가격 확정 | Market | 재시도형 (retriable) | CONFIRMED 전이, 뒤로 가지 않고 전진 |

**용어 정의:**
- **보상 가능(compensatable)**: 실패 시 이전 단계를 되돌리는 보상 트랜잭션을 실행할 수 있는 단계.
  예측 생성은 FAILED로 전이하면 "없던 일"이 된다.
- **피벗(pivot)**: Saga의 go/no-go 결정점. 이 단계가 성공하면 뒤로 돌아가지 않고 전진만 한다.
  포인트 차감이 피벗 — 차감 성공 후 환불은 별도 보상 트랜잭션이다.
- **재시도형(retriable)**: 피벗 이후 단계. 실패하면 성공할 때까지 재시도한다.
  가격 확정은 차감 성공 후 반드시 완료되어야 한다.

---

## 3. 상태 전이 다이어그램

```
Market Prediction 상태:

  POINT_PENDING ──────────────────────────────┐
       │                                       │
       │ point.deducted 수신                    │ point.deduction.failed 수신
       │                                       │ 또는 타임아웃 대사 FAILED 확정
       ▼                                       ▼
   CONFIRMED                                FAILED
       │                                       │
       │ 정산 완료                              │ (재참여 가능)
       ▼                                       │
    SETTLED                                    │
                                               │
       │ 마켓 무효화(VOIDED)                    │
       ▼                                       │
   REFUND_PENDING ─── 환불 완료 ──→ REFUNDED   │
```

---

## 4. 이벤트 흐름

```
Market                          Kafka                         Member-Point
  │                               │                               │
  │ ① prediction.created          │                               │
  │ ──────────────────────────▶   │                               │
  │   (outbox → 폴링 발행)        │                               │
  │                               │  ② prediction.created         │
  │                               │ ──────────────────────────▶   │
  │                               │                               │
  │                               │                      spend() 호출
  │                               │                        │
  │                               │                   ┌────┴────┐
  │                               │                 성공       잔액부족
  │                               │                   │          │
  │                               │  ③ point.deducted │          │ point.deduction.failed
  │                               │ ◀────────────────┘          │
  │                               │ ◀──────────────────────────┘
  │  ④ point.deducted             │   (outbox → 폴링 발행)
  │ ◀─────────────────────────── │
  │                               │
  │  confirmPrediction()          │
  │  또는 markPredictionFailed()  │
```

---

## 5. 비정상 조합 매트릭스

현재 상태 × 수신 이벤트 → 대응

| Market 상태 \ 이벤트 | point.deducted | point.deduction.failed |
|----------------------|----------------|------------------------|
| **POINT_PENDING** | → CONFIRMED (정상) | → FAILED (정상) |
| **CONFIRMED** | 멱등 무시 (정상 재수신) | 멱등 무시 (이미 확정) |
| **FAILED** | **환불 트리거** (늦은 성공) | 멱등 무시 (이미 실패) |
| **SETTLED** | 멱등 무시 | 멱등 무시 |
| **REFUNDED** | 멱등 무시 | 멱등 무시 |

### 늦은 성공 레이스 (가장 중요한 비정상 케이스)

```
Timeline:
  t0: Market에서 prediction.created 발행
  t1: Member-Point가 spend() 실행 → 성공 → point.deducted outbox INSERT
  t2: ReconciliationScheduler가 POINT_PENDING 타임아웃 판단
      → 거래 상태 조회 → 아직 PENDING (point.deducted 미발행)
      → NOT_FOUND 또는 타임아웃 → FAILED 확정
  t3: point.deducted가 Kafka에 도착 → Market Consumer가 수신
      → 현재 상태 = FAILED → 환불 트리거!
```

**왜 무시가 아니라 환불인가:**
포인트가 실제로 차감되었으므로 (Member-Point에서 SUCCEEDED) 무시하면 사용자 포인트가 유실된다.
FAILED 상태에서 point.deducted를 수신하면 자동 환불을 트리거하여 포인트를 복구해야 한다.

---

## 6. 환불-원거래 연결

- 환불 키: `REFUND-{원 차감 idempotencyKey}` → 원거래당 환불 1회를 구조적으로 보장
- 환불 금액: 원 차감 금액 이하를 Member-Point가 서버 측에서 검증 (호출자 신뢰 금지)
- 보상 트랜잭션 원칙:
  - (a) 보상은 비즈니스 사유로 실패하지 않도록 설계 — 탈퇴 회원 환불 허용
  - (b) 기술 실패는 성공할 때까지 재시도 (백오프 + DLT + runbook)
  - (c) 보상의 보상은 존재하지 않는다
  - (d) 보상은 멱등이어야 한다

---

## 7. 격리 부재와 완화책

Saga는 ACID의 I(Isolation)가 없다. 적용한 완화책:

| 완화책 | 적용 위치 | 설명 |
|--------|----------|------|
| Semantic Lock | POINT_PENDING 상태 | 처리 중임을 표시하여 다른 연산을 차단 |
| 재확인 (Reread Value) | 정산 직전 CONFIRMED 재검증 | 정산 시점에 상태가 바뀌지 않았는지 확인 |
| 교환 가능 연산 (Commutative) | 적립·차감의 순서 독립성 | 잔액에 대한 atomic UPDATE |

---

## 8. Outbox/Inbox 구조

### Outbox (이벤트 발행 측)

| 서비스 | 발행 이벤트 | 메시지 키 |
|--------|-----------|----------|
| Market | prediction.created | memberId |
| Member-Point | point.deducted, point.deduction.failed | memberId |

### Inbox (이벤트 소비 측)

| 서비스 | 소비 이벤트 | 중복 판정 |
|--------|-----------|----------|
| Member-Point | prediction.created | idempotencyKey (point_history 겸용) |
| Market | point.deducted, point.deduction.failed | processed_event 테이블 (eventId) |

Member-Point는 point_history.idempotency_key가 inbox 역할을 겸한다 (CLAUDE.md 허용).
Market은 별도 processed_event 테이블 필수.

---

## 9. 타임아웃 / 대사

- POINT_PENDING 최대 체류 시간: 5분 (설정 가능)
- ReconciliationScheduler (기존 유지, 역할 변경):
  - 이벤트 유실·지연 시의 안전망
  - POINT_PENDING 5분 초과 → Member-Point 거래 상태 조회
  - PROCESSED → confirmPrediction()
  - FAILED → markPredictionFailed()
  - NOT_FOUND → markPredictionFailed()

---

## 10. 토픽 구조

| 토픽 | 발행자 | 소비자 | 파티션 키 |
|------|--------|--------|----------|
| battle.reward.requested | Battle | Member-Point | battleId |
| prediction.created | Market | Member-Point | memberId |
| point.deducted | Member-Point | Market | memberId |
| point.deduction.failed | Member-Point | Market | memberId |
| *.dlt | 각 서비스 | 운영자 수동 | 원본 키 |

---

## 11. 지연 측정 (Phase 5 예정)

참여 → 확정까지 end-to-end 지연 = outbox 폴링 주기 × 2 (양방향):
- Market outbox 폴링 → Kafka → Member-Point 소비 → spend → Member-Point outbox 폴링 → Kafka → Market 소비
- 폴링 간격 1초로 설정 시 최소 ~2초, p95 목표 5초 이내

최적화 여지: 커밋 직후 즉시 발행 시도 + outbox는 보정용으로만 사용 (Phase 6 고려)

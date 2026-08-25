# LEARNING_NOTES.md — 면접 관점 정리

Phase별로 "이 작업으로 답할 수 있게 된 면접 질문"과 모범 답변 뼈대를 누적한다.

---

## Phase 1: 멱등성 정석화 + Kafka/Outbox

### Q1. 멱등성 API를 어떻게 구현했는가?

**답변 뼈대:**
- point_history 테이블의 idempotency_key UNIQUE 제약을 동시성 락으로 활용했다
- PENDING 선삽입 패턴: status=PENDING으로 먼저 INSERT → 잔액 UPDATE → SUCCEEDED 확정을 단일 트랜잭션에서 수행
- 동일 키 동시 요청은 InnoDB 유니크 인덱스 락에서 직렬화되어, 선행 트랜잭션 커밋 후 후행 요청이 DuplicateKey로 떨어짐
- DuplicateKey catch 후 재조회하여 최초 응답을 그대로 반환 ("같은 요청 → 같은 응답" 원칙, 실패에도 적용)
- Testcontainers(MySQL)에서 10스레드 동시 요청으로 검증: 1건 성공 + 9건 ALREADY_PROCESSED, 5xx 0건

**깊이 질문 대비:**
- "왜 @Transactional 대신 TransactionTemplate을 썼나?" → DataIntegrityViolationException이 Hibernate 트랜잭션을 rollback-only로 마킹하기 때문. 트랜잭션 경계를 예외 발생 지점 안쪽으로 제한해야 catch 후 정상 반환 가능
- "H2로 테스트하면 안 되나?" → H2는 InnoDB의 유니크 인덱스 갭 락, REPEATABLE READ 스냅샷 동작이 다르다. 동시성/멱등성 테스트는 실제 MySQL 필수

### Q2. 어떤 기준으로 동기/비동기(이벤트)를 나눴는가?

**답변 뼈대:**
- 판단 기준: "실패가 원 행위를 막아야 하는가"
- **earn(적립)**: 투표의 부수효과. 보상 실패가 투표를 취소시키지 않음 → 이벤트(비동기) 대상
- **spend(차감)**: 잔액 부족이면 예측 자체가 성립 안 됨. 원 행위가 결과에 의존 → Saga(Phase 2)
- 비동기 전환의 실체: "투표는 Battle DB 커밋으로 즉시 성공, 포인트는 잠시 후 도착" — 결합도를 끊는다는 것의 구체적 의미

### Q3. Transactional Outbox 패턴을 왜, 어떻게 적용했는가?

**답변 뼈대:**
- **왜**: 이중 쓰기(dual write) 문제 해결. DB 커밋과 Kafka 발행을 동시에 보장할 수 없으므로, 이벤트를 DB에 먼저 기록하고 별도 프로세스가 Kafka로 발행
- **구현**: 비즈니스 변경(투표 저장, 정산 확정)과 outbox_event INSERT를 같은 트랜잭션에서 수행. 폴링 퍼블리셔가 5초 간격으로 PENDING 이벤트를 Kafka로 발행 후 PUBLISHED 전이
- **at-least-once 발행**: Kafka 발행 성공 후 DB UPDATE 전에 프로세스가 죽으면 다음 폴링에서 재발행 → 컨슈머 멱등성으로 중복 해소
- **폴링 vs CDC**: 1차는 폴링(단순), Phase 6에서 Debezium CDC로 전환 예정 (발행 지연 감소)

### Q4. 비동기로 바꾸면 사용자 경험은 어떻게 되나?

**답변 뼈대:**
- 최종 일관성(eventual consistency)의 사용자 노출: 투표 직후 잔액 조회 시 아직 반영 전일 수 있음
- 하지만 투표 자체는 즉시 성공하므로 사용자 체감 응답 속도는 오히려 개선
- 기존에는 Member-Point 다운 시 투표 응답까지 느려지거나 실패 경로를 탔지만, 이제는 Battle DB 커밋만으로 완료

### Q5. 컨슈머에서 실패를 어떻게 분류하는가?

**답변 뼈대:**
- 기존 REST의 "4xx 재시도 금지 / 5xx만 재시도" 정책의 이벤트 레벨 확장
- **비즈니스 거절** (MEMBER_NOT_FOUND, POINT_INVALID_AMOUNT 등): 재시도해도 결과 불변 → ack로 종결, 로그 기록
- **기술 실패** (DB 순단, 타임아웃): 재시도 가능 → 백오프 3회 → DLT
- **포이즌 메시지** (JSON 파싱 실패): 재시도 불가 → ack로 종결
- 이 분류가 없으면 탈퇴 회원 이벤트 하나가 재시도를 다 태우고 DLT를 오염시킨다

---

## Phase 2: Market 예측 참여 Saga (Choreography)

### Q6. 왜 spend까지 비동기(Saga)로 했는가?

**답변 뼈대:**
- 일반적으로 spend는 "즉시 결과가 필요"하니 동기가 맞다 (A안). 실무에서 틀린 선택이 아님
- 하지만 이 프로젝트의 목적이 "정석 Saga를 구현하고 증거를 남기는 것"이었기 때문에 B안(전체 Choreography)을 선택
- "즉시 결과" 문제는 POINT_PENDING 선삽입 + 202 Accepted + 상태 폴링으로 해결. 결제 시스템이 실제로 이렇게 동작함
- 트레이드오프를 알고 선택했다. 대안(동기 유지)과 하이브리드도 안다

### Q7. Saga에서 보상 트랜잭션은 어떻게 설계했는가?

**답변 뼈대:**
- 단계 분류: 예측 생성=보상 가능(compensatable), 포인트 차감=피벗(pivot), 가격 확정=재시도형(retriable)
- 피벗은 go/no-go 결정점. 차감 성공 후엔 뒤로 안 가고 전진만 함
- 환불-원거래 연결: 환불 키 = `REFUND-{원 차감 키}` → 원거래당 환불 1회 구조적 보장
- 금액 상한: 환불 금액 ≤ 원 차감 금액을 Member-Point가 서버 측에서 검증 (호출자 신뢰 금지)
- 보상 트랜잭션 4원칙: (a) 비즈니스 사유로 실패하지 않음 (탈퇴 회원 환불 허용) (b) 기술 실패는 재시도 (c) 보상의 보상은 없음 (d) 보상은 멱등

### Q8. 늦은 성공(late success) 레이스를 어떻게 처리하는가?

**답변 뼈대:**
- 상황: 타임아웃 대사가 FAILED 확정 → 그 후 point.deducted가 도착
- 무시하면 포인트 유실. 포인트가 실제로 차감되었으므로 환불해야 함
- 구현: 비정상 조합 매트릭스 — 현재 상태(FAILED) × 수신 이벤트(point.deducted) → 환불 트리거
- 환불 흐름: FAILED → REFUND_PENDING → prediction.refund.requested 발행 → Member-Point가 환불 → point.refunded → REFUNDED

**깊이 질문 대비:**
- "이 레이스는 언제 발생하나?" → outbox 폴링 지연(양방향 합산)이 대사 스케줄러의 타임아웃보다 길 때. 폴링 1초, 타임아웃 5분이면 거의 안 일어나지만, 구조적으로 방어해야 함
- "처리 순서가 보장되나?" → 메시지 키 = memberId, 같은 회원의 차감·환불은 같은 파티션 → 순서 보장

### Q9. Saga에서 격리(Isolation)가 없는 문제를 어떻게 완화했는가?

**답변 뼈대:**
- Saga는 ACID의 I가 없다. 적용한 완화책:
  - **Semantic Lock**: POINT_PENDING 상태로 "처리 중"임을 표시하여 다른 연산 차단
  - **재확인(Reread Value)**: 정산 직전 CONFIRMED 상태 재검증
  - **교환 가능 연산(Commutative)**: 적립·차감의 순서 독립적인 atomic UPDATE
- 면접에서 나올 수 있는 추가 용어: pessimistic view, version file 등 (SAGA_DESIGN.md에 정리)

### Q10. 실패도 이벤트로 발행하는 이유는?

**답변 뼈대:**
- 잔액부족은 "비즈니스 거절"이지만, 이벤트(point.deduction.failed)로 정상 발행한다
- 이유: Market이 "차감이 실패했다"는 사실을 알아야 FAILED로 전이할 수 있다. 이벤트가 없으면 Market은 영원히 POINT_PENDING에 머무름
- 반면 기술 실패(DB 순단)는 이벤트를 발행하지 않는다. 재시도로 해결될 수 있으므로
- 구분: "비즈니스 실패 = 확정적 결과 = 이벤트" vs "기술 실패 = 일시적 = 재시도"

---

## Phase 3: 관측성 (Observability)

### Q11. MSA에서 분산 추적을 어떻게 구현했는가?

**답변 뼈대:**
- Micrometer Tracing + Brave + Zipkin Reporter 조합
- Gateway → 서비스 → Kafka 컨슈머까지 traceId가 자동 전파됨
- Kafka 헤더 전파: B3 propagation 포맷으로 Kafka 메시지에 traceId를 실어서 컨슈머에서 이어받음
- Zipkin UI에서 한 요청의 전체 흐름을 시각화할 수 있음
- 샘플링 비율: 로컬 1.0(전수), 프로덕션은 0.1~0.5 (성능 고려)

### Q12. 어떤 메트릭을 모니터링하는가?

**답변 뼈대:**
- **인프라 메트릭**: JVM 힙, GC, 스레드 수, HikariCP 커넥션 풀 (Micrometer 자동 수집)
- **도메인 메트릭**: point.spend.failed(잔액부족 카운터), point.idempotency.conflict, outbox 발행 지연
- Prometheus가 15초 간격으로 각 서비스의 /actuator/prometheus 엔드포인트를 스크레이핑
- Grafana 대시보드로 시각화

**깊이 질문 대비:**
- "DLT에 메시지가 들어오면 어떻게 아나?" → DLT 유입 수 메트릭 + Grafana 알림 규칙 설정 (Phase 3 이후 확장)

---

## Phase 4: Gateway 보안

### Q13. Gateway에서 헤더 위조를 어떻게 방어했는가?

**답변 뼈대:**
- 문제: Gateway가 JWT 검증 후 X-Member-Id/X-Member-Role 헤더를 설정하는데,
  공개 경로(JWT 검증 없이 통과하는 경로)에서는 클라이언트가 보낸 헤더가 그대로 전달됨
- 해결: SecurityHeaderFilter(order=-10)가 JwtAuthenticationFilter(order=-1)보다 **먼저** 실행되어
  모든 요청에서 X-Member-Id/X-Member-Role을 제거. JWT 인증 경로에서만 검증된 값으로 재설정
- /internal/** 경로도 명시적 403 차단. 라우트에 없어도 방어적으로 차단해야 실수를 방지

**깊이 질문 대비:**
- "왜 JwtAuthenticationFilter에서 remove하면 안 되나?" → 공개 경로는 JWT 필터를 통과하지 않으므로,
  JWT 필터보다 먼저 실행되는 별도 필터에서 제거해야 모든 경로를 커버할 수 있다

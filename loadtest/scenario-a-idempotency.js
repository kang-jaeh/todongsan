/**
 * 시나리오 A: 동일 멱등키 100VU 동시 요청
 *
 * 검증 목표:
 * - 5xx 에러 0%
 * - DB에 point_history 1건만 존재
 * - 전원 동일 응답 (200 OK)
 *
 * 실행:
 *   docker exec -i todongsan-mysql mysql -uroot -p1234 < loadtest/seed-reset.sql
 *   docker run --rm -i --network host grafana/k6 run - < loadtest/scenario-a-idempotency.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const errors5xx = new Counter('errors_5xx');
const alreadyProcessed = new Counter('already_processed');
const firstSuccess = new Counter('first_success');

export const options = {
  scenarios: {
    // Phase 1: 동시 정합성 (같은 키 100VU burst)
    correctness: {
      executor: 'shared-iterations',
      vus: 100,
      iterations: 100,
      maxDuration: '30s',
      exec: 'sameKey',
    },
  },
  thresholds: {
    'errors_5xx': ['count==0'],
    'http_req_failed': ['rate<0.01'],
  },
};

const IDEMPOTENCY_KEY = 'k6:idempotency-test:same-key-001';

export function sameKey() {
  const payload = JSON.stringify({
    memberId: 9003,
    type: 'EARN_VOTE',
    amount: 10.00,
    referenceType: 'BATTLE',
    referenceId: 1,
    reason: 'k6 멱등성 테스트',
  });

  const res = http.post('http://host.docker.internal:8080/internal/api/v1/points/earn', payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': IDEMPOTENCY_KEY,
    },
  });

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  if (res.status >= 500) {
    errors5xx.add(1);
  } else if (res.status === 200) {
    const body = JSON.parse(res.body);
    if (body.message && body.message.includes('이미 처리된')) {
      alreadyProcessed.add(1);
    } else {
      firstSuccess.add(1);
    }
  }
}

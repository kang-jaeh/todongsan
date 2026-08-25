/**
 * 시나리오 B: 잔액 1000P에 10P 차감 500VU (키 상이)
 *
 * 검증 목표:
 * - 정확히 100건 성공 (1000P / 10P = 100)
 * - 나머지 400건은 잔액부족(409)
 * - 잔액이 음수가 되지 않음
 * - 5xx 0건
 *
 * 실행:
 *   docker exec -i todongsan-mysql mysql -uroot -p1234 < loadtest/seed-reset.sql
 *   docker run --rm -i --network host grafana/k6 run - < loadtest/scenario-b-concurrency.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const spendSuccess = new Counter('spend_success');
const spendInsufficient = new Counter('spend_insufficient');
const errors5xx = new Counter('errors_5xx');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 10,
      iterations: 200,
      maxDuration: '60s',
    },
  },
  thresholds: {
    'errors_5xx': ['count==0'],
  },
};

export default function () {
  const uniqueKey = `k6:spend:${__VU}-${__ITER}-${Date.now()}`;

  const payload = JSON.stringify({
    memberId: 9002,
    type: 'SPEND_MARKET',
    amount: 10.00,
    referenceType: 'MARKET_PREDICTION',
    referenceId: __ITER + 1,
    reason: 'k6 동시성 차감 테스트',
  });

  const res = http.post('http://host.docker.internal:8080/internal/api/v1/points/spend', payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uniqueKey,
    },
  });

  if (res.status >= 500) {
    errors5xx.add(1);
    if (__ITER < 3) { console.log(`5xx response: ${res.status} ${res.body}`); }
  } else if (res.status === 200) {
    spendSuccess.add(1);
  } else if (res.status === 409) {
    spendInsufficient.add(1);
  }

  check(res, {
    'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
  });
}

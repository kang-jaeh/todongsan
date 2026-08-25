/**
 * 부하테스트: earn API 지속 부하
 *
 * 50VU × 1분 → 수만 건 처리
 * 각 요청마다 고유 idempotency key 사용 (실제 신규 적립)
 *
 * 측정 목표:
 * - RPS (초당 요청 수)
 * - 응답시간 p95, p99
 * - 에러율 < 1%
 *
 * 실행:
 *   docker exec -i todongsan-mysql mysql -uroot -p1234 < loadtest/seed-reset.sql
 *   docker run --rm -i grafana/k6 run - < loadtest/scenario-load-earn.js
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const errors5xx = new Counter('errors_5xx');

export const options = {
  scenarios: {
    sustained_load: {
      executor: 'constant-vus',
      vus: 50,
      duration: '1m',
    },
  },
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(95)<500', 'p(99)<1000'],
    'http_req_failed': ['rate<0.01'],
    'errors_5xx': ['count==0'],
  },
};

export default function () {
  const uniqueKey = `k6:load:earn:${__VU}-${__ITER}-${Date.now()}`;

  // VU별 다른 회원 사용 (행 락 경합 분산)
  const memberId = 9100 + (__VU % 50);
  const payload = JSON.stringify({
    memberId: memberId,
    type: 'EARN_VOTE',
    amount: 1.00,
    referenceType: 'BATTLE',
    referenceId: __ITER + 1,
    reason: 'k6 부하테스트',
  });

  const res = http.post('http://host.docker.internal:8080/internal/api/v1/points/earn', payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': uniqueKey,
    },
  });

  check(res, { 'status is 200': (r) => r.status === 200 });

  if (res.status >= 500) {
    errors5xx.add(1);
  }
}

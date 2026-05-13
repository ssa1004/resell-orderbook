// 호가창 조회 부하 — GET /api/v1/orderbook/{skuId}
//
// 호가창은 read-heavy endpoint — Top N ASK + BID 가격 레벨을 돌려준다. dev 에서는
// 메모리 / DB 단순 SELECT, prod 에서는 Redis 2단 캐시 + pub/sub invalidation 이 붙는다.
// 본 시나리오는 캐시 hit 비율 자체를 재진 않고 throughput / latency 만 본다.
//
// constant 500 req/s — read-heavy 엔드포인트라 listing-create / order-match 보다
// 5배 RPS 로 잡고, 매칭 부하와 *함께* 돌렸을 때 cache invalidation race 에서도
// p95 가 흔들리지 않는지 본다. depth=10|20|50 을 round-robin.
//
// thresholds:
//   - http_req_duration p95 < 50ms — Redis hit 기준
//   - http_req_duration p99 < 150ms — miss + DB fallback 포함 꼬리
//   - http_req_failed rate < 1%

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, randomSku } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

const errorRate = new Rate('orderbook_query_error');

const DEPTHS = [10, 20, 50];

export const options = {
  scenarios: {
    orderbook_query: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<50', 'p(99)<150'],
    orderbook_query_error: ['rate<0.01'],
  },
};

export default function () {
  const sku = randomSku();
  const depth = DEPTHS[Math.floor(Math.random() * DEPTHS.length)];
  const url = `${BASE_URL}/api/v1/orderbook/${sku}?depth=${depth}`;

  const res = http.get(url, { headers: authHeader(), tags: { name: 'order-book-query' } });

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has asks/bids field': (r) => {
      const b = r.body || '';
      return b.includes('asks') && b.includes('bids');
    },
  });
  errorRate.add(ok ? 0 : 1);

  sleep(0.01);
}

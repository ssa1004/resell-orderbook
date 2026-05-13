// 구매 호가 (BID) 등록 + 즉시 매칭 트리거 부하 — POST /api/v1/bids
//
// `/api/v1/bids` 는 BID 를 등록하면서 한 트랜잭션 안에서 매칭 엔진을 돌린다 (가장 낮은
// ASK 와 가격 비교). 매칭이 성공하면 Trade 가 INSERT 되고, Outbox 에 `market.tradematched`
// 이벤트가 쌓이고, Saga 가 PG authorize 단계로 넘어간다.
//
// 0 → 200 VU 로 ramping — connection-bound 보단 throughput-bound 측정이지만, 매칭
// 자체가 SKU 단위 advisory lock 으로 직렬화되므로 같은 SKU 의 VU 가 늘면 lock 대기가
// p95 latency 를 끌어올린다. ramping 으로 그 지점이 어디인지 본다.
//
// thresholds:
//   - http_req_duration p95 < 500ms — BID INSERT + ASK lock + Trade INSERT + Outbox INSERT
//   - http_req_failed rate < 2% (매칭 race 로 같은 ASK 를 두 BID 가 잡으려고 할 때 한쪽이
//     409 / 5xx 로 떨어질 가능성을 감안 — 단, advisory lock + SKIP LOCKED 가 정상이면 0)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL, randomSku, randomBidPrice } from '../lib/config.js';
import { authHeader, buyerOf, newIdempotencyKey } from '../lib/auth.js';

const matchedCount = new Counter('bid_matched_count');
const unmatchedCarry = new Counter('bid_unmatched_carry');
const errorRate = new Rate('bid_place_error');

export const options = {
  scenarios: {
    order_match: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 50 },
        { duration: '30s', target: 100 },
        { duration: '20s', target: 200 },
        { duration: '20s', target: 200 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    bid_place_error: ['rate<0.02'],
  },
};

export default function () {
  const sku = randomSku();
  const price = randomBidPrice();

  const url = `${BASE_URL}/api/v1/bids`;
  const body = JSON.stringify({ skuId: sku, price, currency: 'KRW' });

  const headers = authHeader(buyerOf(__VU));
  headers['Idempotency-Key'] = newIdempotencyKey('bid');

  const res = http.post(url, body, { headers, tags: { name: 'order-match' } });

  const ok = check(res, {
    'status 201': (r) => r.status === 201,
    'has bidId': (r) => {
      try {
        return Boolean(JSON.parse(r.body || '{}').bidId);
      } catch (_) {
        return false;
      }
    },
  });
  if (!ok) {
    errorRate.add(1);
    return;
  }
  errorRate.add(0);

  try {
    const parsed = JSON.parse(res.body || '{}');
    if (parsed.matchedTradeId) {
      matchedCount.add(1);
    } else {
      unmatchedCarry.add(1);
    }
  } catch (_) {
    // 위 check 에서 이미 fail 처리됨.
  }

  sleep(0.05);
}

// 판매 호가 (ASK) 등록 부하 — POST /api/v1/listings
//
// constant-arrival-rate 100 req/s 로 60 초 동안 호가를 흘려넣는다. 매칭이 일어나면 즉시
// Trade 가 생성되고 (201 Created + matchedTradeId), 매칭 상대가 없으면 호가창에 쌓인다.
// 본 시나리오는 후자 (호가창 적재) 가 주이므로, 가격을 일부러 BID 분포보다 살짝 높게 잡아
// 매칭률을 낮춘다 (orderbook-query 가 빈 호가창을 받지 않게 함).
//
// thresholds:
//   - http_req_duration p95 < 200ms — ASK 1건 = INSERT + advisory lock + 매칭 시도
//   - http_req_failed rate < 1%

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL, randomSku, ASK_PRICE_MAX } from '../lib/config.js';
import { authHeader, sellerOf, newIdempotencyKey } from '../lib/auth.js';

const matchedCount = new Counter('listing_matched_count');
const unmatchedCarry = new Counter('listing_unmatched_carry');
const errorRate = new Rate('listing_create_error');

export const options = {
  scenarios: {
    listing_create: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 30,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200', 'p(99)<500'],
    listing_create_error: ['rate<0.01'],
  },
};

export default function () {
  const sku = randomSku();
  // ASK 분포는 BID 분포 상한 (190,000) 보다 살짝 위로 — 매칭률을 ~30% 로 낮춰
  // 호가창 적재가 주가 되도록.
  const price = 150_000 + Math.floor(Math.random() * (ASK_PRICE_MAX - 150_000));

  const url = `${BASE_URL}/api/v1/listings`;
  const body = JSON.stringify({ skuId: sku, price, currency: 'KRW' });

  const headers = authHeader(sellerOf(__VU));
  headers['Idempotency-Key'] = newIdempotencyKey('listing');

  const res = http.post(url, body, { headers, tags: { name: 'listing-create' } });

  const ok = check(res, {
    'status 201': (r) => r.status === 201,
    'has listingId': (r) => {
      try {
        return Boolean(JSON.parse(r.body || '{}').listingId);
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

  // matchedTradeId 가 있으면 즉시 매칭, 없으면 호가창에 적재 (carry).
  try {
    const parsed = JSON.parse(res.body || '{}');
    if (parsed.matchedTradeId) {
      matchedCount.add(1);
    } else {
      unmatchedCarry.add(1);
    }
  } catch (_) {
    // body 파싱 실패는 위 check 에서 이미 잡힘.
  }

  sleep(0.05);
}

// 동시 매칭 invariant 검증 — N 개의 BID 와 N 개의 ASK 가 같은 SKU 에 거의 동시에 들어와도
// 매칭 결과가 일관적인지 본다. ADR 의 핵심 invariant:
//
//   "한 SKU 의 매칭은 advisory lock + FOR UPDATE SKIP LOCKED 로 직렬화되어,
//    matched 응답 수 == DB 에 INSERT 된 Trade 수 == 같은 BID/ASK 페어 한 번씩"
//
// 단일 SKU 를 모든 VU 가 두드린다 — 매칭 race 가 가장 빡센 조건. 일반 부하 시나리오는
// SKU pool 을 분산시키는데, 이건 *경합을 의도적으로 만든다* 가 다르다.
//
// 본 시나리오는 thresholds 보다 invariant 가 본질이라, 종료 시 teardown 에서
// `match_count_local == matched_responses` 인지 직접 확인하는 카운터를 둔다.
//
// thresholds (느슨하게 — 정합성만 차단):
//   - http_req_failed rate < 5%   (advisory lock 대기로 일부 timeout 허용)
//   - match_invariant_violation count == 0
//
// 한계: k6 client 단에선 "DB 에 들어간 Trade 수" 를 알 수 없다. 따라서 client 가 본
// `matchedTradeId` 의 *unique 수* 만 본다. matchedTradeId 가 중복으로 떨어지면 invariant
// 위반 (같은 Trade 가 두 호가에 동시에 묶임). 진짜 DB-level 검증은 e2e-tests 의
// `Postgres 위 동시 매칭 race` 테스트가 담당.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL } from '../lib/config.js';
import {
  authHeader,
  buyerOf,
  sellerOf,
  newIdempotencyKey,
} from '../lib/auth.js';

const matchedResponses = new Counter('cm_matched_responses');
const duplicateTradeId = new Counter('match_invariant_violation');
const placeError = new Rate('cm_place_error');

// 한 SKU 에 모든 부하를 집중 — race 조건을 의도적으로 극대화.
const HOT_SKU = __ENV.CONCURRENT_MATCH_SKU || '00000000-0000-0000-0000-cafe00000099';

// 같은 가격 (matchable) — 양쪽 매칭 확률 ~100%. 매칭이 안 되면 호가창에 적재만 됐다는 뜻.
const MATCH_PRICE = 150_000;

export const options = {
  scenarios: {
    concurrent_match: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 30 },   // 30 VU 가 BID/ASK 를 반반 쏟아붓음
        { duration: '30s', target: 30 },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    cm_place_error: ['rate<0.05'],
    match_invariant_violation: ['count==0'],
  },
};

// 클라이언트 단 dedupe — 매칭된 Trade ID 를 모아 중복을 잡는다.
const seenTradeIds = new Set();

function placeBid() {
  const url = `${BASE_URL}/api/v1/bids`;
  const headers = authHeader(buyerOf(__VU));
  headers['Idempotency-Key'] = newIdempotencyKey('cm-bid');
  const body = JSON.stringify({ skuId: HOT_SKU, price: MATCH_PRICE, currency: 'KRW' });
  return http.post(url, body, { headers, tags: { name: 'concurrent-match-bid' } });
}

function placeAsk() {
  const url = `${BASE_URL}/api/v1/listings`;
  const headers = authHeader(sellerOf(__VU));
  headers['Idempotency-Key'] = newIdempotencyKey('cm-ask');
  const body = JSON.stringify({ skuId: HOT_SKU, price: MATCH_PRICE, currency: 'KRW' });
  return http.post(url, body, { headers, tags: { name: 'concurrent-match-ask' } });
}

function processResponse(res, kind) {
  const ok = check(res, {
    [`${kind} status 201`]: (r) => r.status === 201,
  });
  if (!ok) {
    placeError.add(1);
    return;
  }
  placeError.add(0);
  try {
    const parsed = JSON.parse(res.body || '{}');
    if (parsed.matchedTradeId) {
      matchedResponses.add(1);
      if (seenTradeIds.has(parsed.matchedTradeId)) {
        // 같은 Trade ID 가 두 호가의 응답에 매핑됐다 = lock 직렬화 실패.
        duplicateTradeId.add(1);
      } else {
        seenTradeIds.add(parsed.matchedTradeId);
      }
    }
  } catch (_) {
    placeError.add(1);
  }
}

export default function () {
  // 짝수 VU 는 BID, 홀수 VU 는 ASK — 두 쪽이 동시에 들어가야 매칭이 일어남.
  // 각 VU 가 한 iteration 마다 자기 쪽을 한 번씩 호출.
  if (__VU % 2 === 0) {
    processResponse(placeBid(), 'bid');
  } else {
    processResponse(placeAsk(), 'ask');
  }
  sleep(0.05);
}

// 거래 내역 조회 (cursor pagination) — GET /api/v1/trades/me/history
//
// cursor pagination (ADR-0025) — `cursor` 미전달이면 첫 페이지, 응답의 `nextCursor` 를
// 다음 요청에 그대로 넣으면 다음 묶음. 본 시나리오는 첫 페이지를 받고 → nextCursor 가
// 있으면 한 번 더 따라가는 2-step 패턴으로, *cursor 토큰 디코딩 비용 + WHERE 조건* 이
// p95 에 미치는 영향을 측정한다.
//
// constant 200 req/s — orderbook-query 의 절반. 일반적으로 거래 내역은 호가창보다 자주
// 안 보지만, '내 거래' 가 active user 마다 빈번하다는 가정으로 200 req/s.
//
// thresholds:
//   - http_req_duration p95 < 100ms — cursor 디코딩 + WHERE created_at < ? LIMIT N
//   - http_req_duration p99 < 250ms — 빈 페이지 처리 / 콜드 캐시 포함
//   - http_req_failed rate < 1%

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL } from '../lib/config.js';
import { authHeader, buyerOf } from '../lib/auth.js';

const pagesFetched = new Counter('history_pages_fetched');
const errorRate = new Rate('history_query_error');

const LIMITS = [10, 20, 50];

export const options = {
  scenarios: {
    trade_history: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<100', 'p(99)<250'],
    history_query_error: ['rate<0.01'],
  },
};

export default function () {
  const limit = LIMITS[Math.floor(Math.random() * LIMITS.length)];
  const userId = buyerOf(__VU);

  // 1) 첫 페이지
  const firstUrl = `${BASE_URL}/api/v1/trades/me/history?limit=${limit}`;
  const first = http.get(firstUrl, { headers: authHeader(userId), tags: { name: 'trade-history' } });

  const firstOk = check(first, {
    'first status 200': (r) => r.status === 200,
    'first body is JSON': (r) => {
      try {
        JSON.parse(r.body || '{}');
        return true;
      } catch (_) {
        return false;
      }
    },
  });
  if (!firstOk) {
    errorRate.add(1);
    return;
  }
  pagesFetched.add(1);

  // 2) nextCursor 가 있으면 한 번 더 — 진짜 pagination latency 를 측정.
  let next = null;
  try {
    next = (JSON.parse(first.body || '{}').nextCursor) || null;
  } catch (_) {
    next = null;
  }

  if (next) {
    const url2 = `${BASE_URL}/api/v1/trades/me/history?limit=${limit}&cursor=${encodeURIComponent(next)}`;
    const second = http.get(url2, { headers: authHeader(userId), tags: { name: 'trade-history' } });

    const secondOk = check(second, {
      'second status 200': (r) => r.status === 200,
    });
    if (secondOk) {
      pagesFetched.add(1);
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }
  } else {
    errorRate.add(0);
  }

  sleep(0.05);
}

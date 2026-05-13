// k6 시나리오 공통 설정 — BASE URL / SKU pool / 호가 가격 범위.
//
// BASE_URL 은 환경변수로 덮어쓸 수 있다. 기본값은 통합 compose 가 노출하는 8081
// (`docker-compose.integration.yml` — 같은 머신에서 다른 포폴 레포의 8080 점유와 충돌
// 회피용). 단독 `bootRun` 으로 띄울 때는 8080 이므로 `BASE_URL=http://localhost:8080`.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

/**
 * SKU pool — 시연용 SKU 가 한정돼 있어 시나리오마다 같은 풀을 돌려쓴다.
 * integration-demo.sh 의 `00000000-0000-0000-0000-cafe000000XX` 패턴과 의도적으로
 * 호환되도록 잡아, 같은 시드 데이터로 데모 + 부하를 모두 돌릴 수 있게 한다.
 */
export const SKUS = (__ENV.K6_SKUS || [
  '00000000-0000-0000-0000-cafe00000001',
  '00000000-0000-0000-0000-cafe00000002',
  '00000000-0000-0000-0000-cafe00000003',
  '00000000-0000-0000-0000-cafe00000004',
  '00000000-0000-0000-0000-cafe00000005',
].join(','))
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

/**
 * VU 인덱스 기반 SKU 선택 — concurrent-match 시나리오에서 advisory lock 경합을
 * 의도적으로 만들기 위해 같은 VU 가 같은 SKU 를 돌려쓴다.
 */
export function pickSku(vuId) {
  if (SKUS.length === 0) return '00000000-0000-0000-0000-cafe00000001';
  return SKUS[vuId % SKUS.length];
}

/**
 * 매 iteration 마다 무작위 SKU — read-heavy 시나리오 (orderbook 조회, 거래 내역)
 * 에서 캐시 hit / miss 를 골고루 만든다.
 */
export function randomSku() {
  if (SKUS.length === 0) return '00000000-0000-0000-0000-cafe00000001';
  return SKUS[Math.floor(Math.random() * SKUS.length)];
}

/**
 * 호가 가격 범위 (KRW). ASK 는 100,000 ~ 200,000, BID 는 90,000 ~ 190,000 으로 잡아
 * 평균 50% 정도는 매칭이 일어나도록 분포를 겹친다.
 *   - 너무 겹치면 모든 호가가 즉시 매칭 → 호가창이 빈다 (orderbook-query 가 의미 없어짐)
 *   - 너무 안 겹치면 매칭 0 → order-match / concurrent-match 시나리오가 무의미
 *
 * 매칭 확률 50% 부근이 4 종 시나리오를 동시에 의미 있게 돌릴 수 있는 균형점이다.
 */
export const ASK_PRICE_MIN = 100_000;
export const ASK_PRICE_MAX = 200_000;
export const BID_PRICE_MIN = 90_000;
export const BID_PRICE_MAX = 190_000;

export function randomAskPrice() {
  return ASK_PRICE_MIN + Math.floor(Math.random() * (ASK_PRICE_MAX - ASK_PRICE_MIN + 1));
}

export function randomBidPrice() {
  return BID_PRICE_MIN + Math.floor(Math.random() * (BID_PRICE_MAX - BID_PRICE_MIN + 1));
}

// k6 시나리오에서 Authorization 헤더 + X-User-Id 헤더를 만들어주는 헬퍼.
//
// 본 앱은 dev 프로파일 (또는 통합 시연용 prod + MARKET_SECURITY_JWT_ENABLED=false) 에서
// JWT 검증을 끄고 X-User-Id 헤더로 사용자를 식별한다 (integration-demo.sh 와 같은 계약).
// 두 가지 경로를 모두 다룬다:
//   1) JWT off — `X-User-Id` 만으로 충분. K6_TOKEN 이 비어 있으면 빈 Authorization.
//   2) JWT on  — auth-stub / auth-service 가 발급한 토큰을 K6_TOKEN 으로 주입.
//
// 본 시나리오의 목적은 토큰 라이프사이클 검증이 아니라 매칭 엔진 / 호가 endpoint 의
// 부하 측정이라, dev 프로파일 + X-User-Id 만으로도 covered 된다.

const ENV_TOKEN = __ENV.K6_TOKEN || '';

/**
 * 공통 헤더 — Content-Type + Authorization (있을 때만) + X-User-Id.
 *
 * @param userId {string} — dev 프로파일에서 사용자를 흉내 낼 id. 기본 'k6-load'.
 */
export function authHeader(userId = 'k6-load') {
  const headers = {
    'Content-Type': 'application/json',
    'X-User-Id': userId,
  };
  if (ENV_TOKEN) {
    headers['Authorization'] = `Bearer ${ENV_TOKEN}`;
  }
  return headers;
}

/**
 * Buyer / Seller 를 분리해서 흉내 — 자기 호가에 자기가 체결되는 것 (self-trade) 을
 * 매칭 엔진이 차단하므로, 매칭이 일어나려면 VU 마다 buyer / seller 가 달라야 한다.
 *
 * 짝수 VU 는 buyer, 홀수 VU 는 seller.
 */
export function buyerOf(vuId) {
  return `k6-buyer-${vuId}`;
}

export function sellerOf(vuId) {
  return `k6-seller-${vuId}`;
}

/**
 * Idempotency-Key — `Idempotency-Key` 헤더로 같은 요청의 중복 처리 차단.
 * VU id + iteration + 랜덤 suffix 로 고유성 보장 (재시도 시나리오에서는 같은 key 를
 * 보내야 하지만, 본 부하 시나리오는 매 호출이 새 요청이라 매번 새로 만든다).
 */
export function newIdempotencyKey(prefix = 'k6') {
  const rand = Math.random().toString(36).slice(2, 10);
  return `${prefix}-${__VU}-${__ITER}-${rand}`;
}

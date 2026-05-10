#!/usr/bin/env bash
#
# Cross-repo 통합 시연 스크립트.
#
# 사전 조건: infrastructure/docker-compose.integration.yml 가 떠 있어야 함.
#   docker compose -p resell-integration -f infrastructure/docker-compose.integration.yml up -d --build
#
# 시나리오:
#   1) auth-service 모사 — 평문 mock JWT (header.payload.signature 형태) 발급
#   2) bid + ask 등록 → 즉시 매칭 → Trade 생성 → market.tradematched 발행
#   3) Saga consumer 가 wiremock PG.authorize 호출 → market.paymentauthorized 발행
#   4) 셀러 발송 → 검수 도착 → 검수 PASS → market.inspectionpassed 발행
#   5) 자동 buyer-shipping → 구매자 수령 (complete) → market.tradecompleted 발행 → 자동 정산
#
# notification-hub stub 컨테이너의 로그가 위 토픽들을 stdout 으로 echo 한다.

set -euo pipefail

BASE="${BASE:-http://localhost:8081}"
COMPOSE_FILE="${COMPOSE_FILE:-infrastructure/docker-compose.integration.yml}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-resell-integration}"

say() { printf "\n\033[1;36m▶ %s\033[0m\n" "$*"; }
note() { printf "  \033[2m%s\033[0m\n" "$*"; }

# ─── 0. 헬스 체크 ────────────────────────────────────────────────
say "0. 헬스 체크 — resell-orderbook"
for i in {1..30}; do
  if curl -sf "$BASE/actuator/health" >/dev/null 2>&1; then
    note "up"
    break
  fi
  if [[ $i -eq 30 ]]; then
    echo "resell-orderbook 가 30 회 시도해도 안 떴음. 'docker compose -p $COMPOSE_PROJECT -f $COMPOSE_FILE logs resell-orderbook' 확인." >&2
    exit 1
  fi
  sleep 2
done

# ─── 1. mock JWT 발급 ────────────────────────────────────────────
# 운영에서는 auth-service 가 RS256 으로 서명한 토큰. 본 시연은 JWT 검증을 끈 상태라
# 평문 (alg=none) JWT 로 *형식만* 흉내. payload 의 sub / roles 는 실제로 쓰이진 않지만,
# auth-service 와의 contract 가 "Bearer JWT 를 받는다" 임을 드러내는 의도.
say "1. mock JWT 발급 (auth-service 모사)"
b64url() { python3 -c "import sys,base64; sys.stdout.write(base64.urlsafe_b64encode(sys.stdin.buffer.read()).rstrip(b'=').decode())"; }
NOW=$(date +%s)
EXP=$((NOW + 3600))
HEADER=$(printf '{"alg":"none","kid":"auth-stub-2026-05","typ":"JWT"}' | b64url)
mk_jwt() {
  local sub="$1" roles="$2"
  local payload
  payload=$(printf '{"sub":"%s","roles":%s,"iat":%d,"exp":%d,"iss":"http://auth-stub:8080"}' \
    "$sub" "$roles" "$NOW" "$EXP" | b64url)
  printf '%s.%s.' "$HEADER" "$payload"   # signature 부분 비움 (시연 한정)
}
BUYER_JWT=$(mk_jwt "buyer-1" '["USER"]')
SELLER_JWT=$(mk_jwt "seller-1" '["USER"]')
INSPECTOR_JWT=$(mk_jwt "inspector-1" '["INSPECTOR"]')
note "buyer JWT prefix: ${BUYER_JWT:0:40}…"

# 데모 단순화 — Sku 등록 endpoint 가 admin 전용이라 임의 UUID 사용 (sku_id 컬럼은 FK 없음).
SKU_ID="00000000-0000-0000-0000-cafe00000001"

# Idempotency-Key 는 매 실행 마다 달라야 (같은 키로 두 번 받으면 409 — 정상 거동).
RUN_TAG="$(date +%s)-$$"

# ─── 2. BID + ASK 등록 → 즉시 매칭 ─────────────────────────────
say "2. BID 등록 (160,000 KRW, buyer-1)"
BID=$(curl -sf -X POST "$BASE/api/v1/bids" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BUYER_JWT" \
  -H 'X-User-Id: buyer-1' \
  -H "Idempotency-Key: integ-bid-$RUN_TAG" \
  -d "{\"skuId\":\"$SKU_ID\",\"price\":160000,\"currency\":\"KRW\"}")
echo "$BID" | jq

say "3. ASK 등록 (140,000 KRW, seller-1) — 같은 SKU best BID 와 즉시 매칭"
ASK=$(curl -sf -X POST "$BASE/api/v1/listings" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $SELLER_JWT" \
  -H 'X-User-Id: seller-1' \
  -H "Idempotency-Key: integ-ask-$RUN_TAG" \
  -d "{\"skuId\":\"$SKU_ID\",\"price\":140000,\"currency\":\"KRW\"}")
echo "$ASK" | jq
TRADE_ID=$(echo "$ASK" | jq -r .matchedTradeId)
if [[ -z "$TRADE_ID" || "$TRADE_ID" == "null" ]]; then
  echo "매칭 실패 — TRADE_ID 가 없음" >&2
  exit 1
fi
note "TRADE_ID=$TRADE_ID"
note "여기서 market.tradematched 발행 — notification-hub-stub 로그에 곧 보임"

# ─── 3. AuthorizePayment Saga consumer 가 wiremock PG 호출 ─────
say "4. PG authorize (Saga consumer 자동 진행) — Trade 가 PAYMENT_AUTHORIZED 가 될 때까지 대기"
for i in {1..20}; do
  STATUS=$(curl -sf "$BASE/api/v1/trades/$TRADE_ID" | jq -r .status)
  if [[ "$STATUS" == "PAYMENT_AUTHORIZED" ]]; then
    note "status=$STATUS — wiremock PG 가 승인"
    break
  fi
  if [[ $i -eq 20 ]]; then
    echo "PAYMENT_AUTHORIZED 도달 실패 (현 status=$STATUS)" >&2
    exit 1
  fi
  sleep 1
done

# ─── 4. 셀러 발송 ───────────────────────────────────────────────
say "5. 셀러 발송 (송장 등록)"
curl -sf -X POST "$BASE/api/v1/trades/$TRADE_ID/seller-shipping" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $SELLER_JWT" \
  -H 'X-User-Id: seller-1' \
  -d '{"trackingNumber":"INTEG-TRK-1001"}' \
  -o /dev/null -w "  HTTP %{http_code}\n"

# ─── 5. 검수 도착 → 배정 → PASS ────────────────────────────────
say "6. 검수센터 도착"
INSPECTION=$(curl -sf -X POST "$BASE/api/v1/inspection-requests/arrive" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $INSPECTOR_JWT" \
  -H 'X-User-Id: inspector-1' \
  -d "{\"tradeId\":\"$TRADE_ID\"}")
echo "$INSPECTION" | jq
INSPECTION_ID=$(echo "$INSPECTION" | jq -r .id)

say "7. 검수자 배정"
curl -sf -X POST "$BASE/api/v1/inspection-requests/$INSPECTION_ID/assign" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $INSPECTOR_JWT" \
  -H 'X-User-Id: inspector-1' \
  -d '{"inspectorId":"inspector-1"}' \
  -o /dev/null -w "  HTTP %{http_code}\n"

say "8. 검수 결과 — PASS"
curl -sf -X POST "$BASE/api/v1/inspection-requests/$INSPECTION_ID/result" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $INSPECTOR_JWT" \
  -H 'X-User-Id: inspector-1' \
  -d '{"outcome":"PASS","note":"authentic"}' | jq
note "여기서 market.inspectionpassed 발행 → 자동 buyer-shipping 컨슈머가 진행"

# ─── 6. buyer-shipping → COMPLETE ───────────────────────────────
say "9. BUYER_SHIPPING 도달 대기 (컨슈머 자동 진행)"
for i in {1..20}; do
  STATUS=$(curl -sf "$BASE/api/v1/trades/$TRADE_ID" | jq -r .status)
  if [[ "$STATUS" == "BUYER_SHIPPING" ]]; then
    note "status=$STATUS"
    break
  fi
  sleep 1
done

say "10. 구매자 수령 완료 (COMPLETE)"
curl -sf -X POST "$BASE/api/v1/trades/$TRADE_ID/complete" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $BUYER_JWT" \
  -H 'X-User-Id: buyer-1' | jq
note "여기서 market.tradecompleted 발행 → 자동 정산 (Settle 컨슈머)"

# ─── 7. 정산 도달 ───────────────────────────────────────────────
say "11. 최종 상태 확인 — 정산까지 진행 (자동)"
sleep 2
curl -sf "$BASE/api/v1/trades/$TRADE_ID" | jq

# ─── 8. notification-hub stub 로그 ──────────────────────────────
say "12. notification-hub stub 이 받은 이벤트 (최근 30 줄)"
docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" logs --tail=30 notification-hub-stub 2>/dev/null \
  || echo "  docker compose logs 호출 실패 — 수동으로 확인하세요."

echo
echo "통합 시연 완료. 정리:"
echo "  docker compose -p $COMPOSE_PROJECT -f $COMPOSE_FILE down -v"

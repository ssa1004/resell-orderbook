#!/usr/bin/env bash
# Resell Market Platform 데모 스크립트.
# 먼저 다른 터미널에서: ./gradlew :market-bootstrap:bootRun
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
say() { printf "\n\033[1;36m▶ %s\033[0m\n" "$*"; }

say "1. 헬스 체크"
curl -s "$BASE/actuator/health" | jq -r '.status' | xargs -I{} echo "  status = {}"

say "2. 상품 등록 — Air Jordan 1 Chicago"
PRODUCT=$(curl -s -X POST "$BASE/api/v1/products" \
  -H 'Content-Type: application/json' \
  -d '{"brand":"Nike","modelName":"Air Jordan 1 Retro High OG Chicago","styleCode":"555088-101","category":"SNEAKERS"}')
echo "$PRODUCT" | jq
PRODUCT_ID=$(echo "$PRODUCT" | jq -r .id)

# 데모 단순화 — Sku 등록 endpoint 가 없어 임의 UUID 사용
SKU_ID="00000000-0000-0000-0000-000000000001"
echo "  (참고: Sku 등록 흐름은 admin 전용이라 데모에서는 임의 UUID 사용)"
echo "  SKU_ID=$SKU_ID"

say "3. BID 등록 (160,000원, buyer-1) — 호가창에 들어감"
BID=$(curl -s -X POST "$BASE/api/v1/bids" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-bid-1' \
  -H 'X-User-Id: buyer-1' \
  -d "{\"skuId\":\"$SKU_ID\",\"price\":160000,\"currency\":\"KRW\"}")
echo "$BID" | jq

say "4. 호가창 조회 — 가장 높은 BID 가 160,000 으로 보임"
curl -s "$BASE/api/v1/orderbook/$SKU_ID?depth=10" | jq

say "5. ASK 등록 (140,000원, seller-1) — 기존 BID 와 즉시 매칭됨 (체결가 160,000, BID 가 maker)"
ASK=$(curl -s -X POST "$BASE/api/v1/listings" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-ask-1' \
  -H 'X-User-Id: seller-1' \
  -d "{\"skuId\":\"$SKU_ID\",\"price\":140000,\"currency\":\"KRW\"}")
echo "$ASK" | jq
TRADE_ID=$(echo "$ASK" | jq -r .matchedTradeId)
echo "  TRADE_ID=$TRADE_ID"

say "6. 매칭 후 호가창 — 체결된 호가는 사라짐"
curl -s "$BASE/api/v1/orderbook/$SKU_ID?depth=10" | jq

say "7. 거래 상세 조회 — 가격, 수수료, 상태"
curl -s "$BASE/api/v1/trades/$TRADE_ID" | jq

say "8. 같은 Idempotency-Key 로 다시 요청 — 409 응답"
curl -s -X POST "$BASE/api/v1/bids" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-bid-1' \
  -H 'X-User-Id: buyer-1' \
  -d "{\"skuId\":\"$SKU_ID\",\"price\":160000,\"currency\":\"KRW\"}" | jq

say "9. Modulith 모듈 진단"
curl -s "$BASE/actuator/modulith" | jq | head -30

say "10. 메트릭 (Prometheus 포맷)"
curl -s "$BASE/actuator/prometheus" | grep -E "http_server_requests|hikaricp_connections" | head

echo
echo "데모 완료. Swagger UI: $BASE/swagger"

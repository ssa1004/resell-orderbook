#!/usr/bin/env bash
# k6 부하 시나리오 5종 일괄 실행.
#
# 단계:
#   1) 본 앱 healthcheck (없으면 compose 를 띄우라고 안내)
#   2) k6 실행 경로 결정 — 우선 로컬 k6, 없으면 docker run
#   3) listing-create → order-match → order-book-query → trade-history → concurrent-match
#   4) 각 결과는 build/k6-reports/{scenario}.json 에 떨군다
#
# 환경변수:
#   BASE_URL — 시나리오의 endpoint base. 기본은 통합 compose 의 8081
#              (단독 bootRun 이면 8080 으로 export)
#   K6_TOKEN — JWT on 일 때만 의미. dev / X-User-Id 경로면 빈 값
#   CONCURRENT_MATCH_SKU — concurrent-match 가 race 를 집중시킬 SKU

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCENARIO_DIR="${ROOT_DIR}/load/k6/scenarios"
REPORT_DIR="${ROOT_DIR}/build/k6-reports"
mkdir -p "$REPORT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8081}"
K6_TOKEN="${K6_TOKEN:-}"
CONCURRENT_MATCH_SKU="${CONCURRENT_MATCH_SKU:-00000000-0000-0000-0000-cafe00000099}"

echo "==> base url: $BASE_URL"

# 1) healthcheck
echo
echo "==> health 확인 ($BASE_URL/actuator/health)"
if ! curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    cat <<EOF
ERROR: $BASE_URL 가 응답하지 않습니다.

먼저 본 앱을 띄우세요:

  1) 단독 bootRun (가벼움):
       ./gradlew :market-bootstrap:bootRun
       BASE_URL=http://localhost:8080 ./scripts/run-load.sh

  2) 통합 compose (PG / Saga / wiremock 포함):
       docker compose -p resell-integration \\
           -f infrastructure/docker-compose.integration.yml up -d --build
       ./scripts/run-load.sh

또는 BASE_URL 를 staging 등으로 덮어쓰세요 (예: BASE_URL=http://staging:8080).
EOF
    exit 1
fi
echo "    UP"

# 2) k6 실행 경로
if command -v k6 >/dev/null 2>&1; then
    K6_EXEC=("k6")
    echo "==> 로컬 k6 사용 ($(k6 version | head -1))"
elif command -v docker >/dev/null 2>&1; then
    # docker 안에서 호스트의 localhost 를 보려면 host.docker.internal 또는 --network host
    if [[ "$BASE_URL" == *"localhost"* || "$BASE_URL" == *"127.0.0.1"* ]]; then
        BASE_URL_DOCKER="${BASE_URL//localhost/host.docker.internal}"
        BASE_URL_DOCKER="${BASE_URL_DOCKER//127.0.0.1/host.docker.internal}"
    else
        BASE_URL_DOCKER="$BASE_URL"
    fi
    K6_EXEC=(docker run --rm -i \
        -v "${ROOT_DIR}/load/k6:/scripts:ro" \
        -e "BASE_URL=${BASE_URL_DOCKER}" \
        -e "K6_TOKEN=${K6_TOKEN}" \
        -e "CONCURRENT_MATCH_SKU=${CONCURRENT_MATCH_SKU}" \
        grafana/k6:0.50.0)
    SCRIPT_PREFIX="/scripts/scenarios"
    echo "==> docker run grafana/k6 사용"
else
    echo "ERROR: k6 도 docker 도 없습니다. brew install k6 또는 docker 설치 후 다시 시도하세요." >&2
    exit 1
fi

# 3) 시나리오 실행 — 한 단계 실패해도 다음 단계는 진행
run_scenario() {
    local name="$1"
    local file="$2"

    echo
    echo "==> [$name] start ($(date +%H:%M:%S))"
    local out="${REPORT_DIR}/${name}.json"
    local rc=0

    if [[ "${K6_EXEC[0]}" == "k6" ]]; then
        export BASE_URL K6_TOKEN CONCURRENT_MATCH_SKU
        set +e
        "${K6_EXEC[@]}" run --summary-export="$out" "$file"
        rc=$?
        set -e
    else
        local docker_file="${SCRIPT_PREFIX}/$(basename "$file")"
        local docker_out="/scripts/${name}.summary.json"
        set +e
        "${K6_EXEC[@]}" run --summary-export="$docker_out" "$docker_file"
        rc=$?
        set -e
        # 호스트에서 결과가 보이도록 마운트 디렉토리에서 build 디렉토리로 옮긴다.
        if [[ -f "${ROOT_DIR}/load/k6/${name}.summary.json" ]]; then
            mv "${ROOT_DIR}/load/k6/${name}.summary.json" "$out"
        fi
    fi

    if [[ $rc -eq 0 ]]; then
        echo "==> [$name] PASSED (report: $out)"
    else
        echo "==> [$name] FAILED rc=$rc (report: $out)"
    fi
}

# 실행 순서:
#   - write-side 먼저 (listing-create + order-match) 로 호가창에 데이터를 쌓고
#   - read-side (order-book-query + trade-history) 가 빈 응답을 받지 않도록
#   - concurrent-match 는 마지막에 — 단일 SKU 에 race 부하라 다른 시나리오와 분리
run_scenario "listing-create"    "${SCENARIO_DIR}/listing-create.js"
run_scenario "order-match"        "${SCENARIO_DIR}/order-match.js"
run_scenario "order-book-query"   "${SCENARIO_DIR}/order-book-query.js"
run_scenario "trade-history"      "${SCENARIO_DIR}/trade-history.js"
run_scenario "concurrent-match"   "${SCENARIO_DIR}/concurrent-match.js"

echo
echo "==> 모든 시나리오 종료. 리포트: $REPORT_DIR"
ls -lah "$REPORT_DIR" 2>/dev/null || true

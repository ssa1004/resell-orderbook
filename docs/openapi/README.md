# OpenAPI spec

`resell-orderbook` 의 REST API 를 OpenAPI 3 spec 으로 build-time export 한다.

## 무엇이 들어가나

- `resell-orderbook.yaml` — 빌드 시 생성되는 OpenAPI 3 문서. 외부 참조 / SDK codegen 의 단일 진실값.
  - 거래 / Bid·Ask 매칭 (`/api/v1/trading`)
  - 거래 라이프사이클 Saga (`/api/v1/trades`)
  - 시세 / 카탈로그 (`/api/v1/market-data`, `/api/v1/catalog`)
  - 검수 / 검수 스케줄링 / 운영 / DLQ (`/api/v1/inspections`, `/api/v1/admin`, `/api/v1/admin/dlq`)

WebSocket 실시간 호가 push 는 OpenAPI 대상이 아니다 (REST endpoint 만 spec 에 포함).

> 이 디렉토리의 `*.yaml` 은 CI 에서 생성·갱신된다. 로컬에서 수기로 편집하지 않는다.

## 생성 방법

`org.springdoc.openapi-gradle-plugin` 을 `market-bootstrap` 모듈에 적용했다.
`generateOpenApiDocs` 태스크가 앱을 부팅한 뒤 `/v3/api-docs.yaml` 을 받아
`docs/openapi/resell-orderbook.yaml` 로 저장한다.

```bash
./gradlew :market-bootstrap:generateOpenApiDocs
```

앱 부팅에 Postgres / Kafka 가 필요하므로, 의존 인프라를 먼저 띄워야 한다.
CI 에서는 service container 를 띄운 잡에서 위 태스크를 실행해 산출된 yaml 을
commit 하거나 아티팩트로 업로드한다.

## 보는 법

- Swagger UI — 앱 실행 후 `http://localhost:8080/swagger-ui.html`
- Redoc — `npx @redocly/cli preview-docs docs/openapi/resell-orderbook.yaml`
- 통합 뷰어 — profile repo `ssa1004/ssa1004` 의 `docs/api/index.html` (9 service spec 드롭다운)

# OWASP API Security Top 10 (2023) — 매핑 및 점검

한정판 거래 마켓 (호가창 매칭 엔진 + 검수 saga + PG 결제) 에서 OWASP API Top 10 의 각
항목이 어떤 코드 경로에 해당하는지 정리하고, 점검 결과와 적용한 보강을 기록한다.

이 문서는 외부 보안 감사 보고서가 아니라 *어디서 어떤 위협을 막고 있는지* 를 한 번에
보기 위한 코드 내비게이션이다. 패치가 들어갈 때마다 함께 갱신.

## API1:2023 — Broken Object Level Authorization (BOLA)

다른 사용자의 호가 / 거래 / 검수 객체에 접근 가능한가.

| 객체 | 검사 위치 | 결과 |
| --- | --- | --- |
| `DELETE /api/v1/listings/{id}` | `Listing.cancel(requestor)` → `ListingOwnershipViolation` | OK |
| `DELETE /api/v1/bids/{id}` | `Bid.cancel(requestor)` → `BidOwnershipViolation` | OK |
| `POST /api/v1/trades/{id}/seller-shipping` | `RecordSellerShippingService` → `UnauthorizedTradeOperationException` | OK |
| `POST /api/v1/trades/{id}/complete` | `CompleteTradeService` → `UnauthorizedTradeOperationException` | OK |
| `GET /api/v1/trades/{id}` | (이전) 검사 없음 → buyer/seller 만 허용 | **fixed** |
| `GET /api/v1/trades/me/history` | repo 가 caller userId 로 필터 (`findByUserCursor`) | OK |
| `POST /api/v1/inspection/appointments/{id}/cancel` | `InspectionAppointmentLifecycleService` → seller-vs-caller 검사 | OK |

TradeId/ListingId/BidId 는 모두 UUIDv4 라 무작위 enumeration 은 사실상 불가능하지만,
ID 가 우연히 노출되더라도 (로그/스크린샷/외부 공유) 제3자가 buyer/seller/체결가를
조회하지 못해야 한다 — `TradeLifecycleController.get` 에 caller-vs-counterparty 검사 추가.

## API2:2023 — Broken Authentication

JWT 검증 / 익명 trust 허용 여부.

- 운영 (`market.security.jwt.enabled=true`): Spring Security OAuth2 Resource Server 가
  발급기관 (`spring.security.oauth2.resourceserver.jwt.issuer-uri`) 의 JWK Set 으로
  서명 검증. `CallerExtractor` 는 JWT 의 `sub` 를 `UserId` 로 매핑.
- dev: JWT 미사용. `X-User-Id` 헤더로 사용자 시뮬레이션 가능하지만 `CallerExtractor`
  가 `market.security.jwt.enabled=true` 인 환경에서는 헤더 폴백을 차단해 임포스터를
  막는다.
- `/actuator/health|info|swagger|v3/api-docs|ws/**` 만 permitAll, 나머지는 모두 인증
  필요. URL 기반 + `@PreAuthorize` 메서드 기반 다중 방어.

## API3:2023 — Broken Object Property Level Authorization

DTO 가 호출자가 보면 안 되는 속성을 흘리지 않는가.

- `TradeResponse` 의 `buyerId / sellerId` 는 본인 (buyer 또는 seller) 만 접근 가능한
  `GET /trades/{id}` 및 본인 history endpoint 에서만 노출 — counterparty 쪽 user-id 가
  drop 되지는 않지만, 거래에 참여한 사람은 상대방 식별 정보를 알 권리가 있다 (운송장
  발송 / CS 분쟁 절차 등).
- `InspectionRequestResponse` 의 `inspectorId / photoUrls` 는 INSPECTOR / ADMIN 만
  접근 가능한 endpoint 에서만 노출.
- 도메인 entity 를 직접 직렬화하지 않고 `*Response` DTO 한 단계를 끼워 entity 내부
  필드 (예: `pgPaymentId`, `feeSnapshot` 내부 raw 값) 가 무심코 따라 나가는 것을 막는다.

## API4:2023 — Unrestricted Resource Consumption

대량 호가 등록 / 거대한 페이지 요청 / regex DoS.

- Token bucket rate limit (`@RateLimited`, ADR-0020):
  - `POST /listings`, `POST /bids` — 20 burst / +5 토큰 per second
  - `POST /trades/buy-now`, `POST /trades/sell-now` — 10 burst / +2 토큰 per second
- Cursor pagination (ADR-0025) — `limit` 은 `@Min(1) @Max(100)` (trades),
  `@Max(1000)` (ticks cursor), `@Max(10_000)` (ticks/ohlc). 더 큰 값은 controller validation 단에서 400.
- `PlaceListing/BidRequest.price` 는 `@Positive @Max(10_000_000_000L)` — overflow 방지.
- 입력에 `@Pattern` regex 없음 — 사용자 입력은 모두 UUID 파싱 또는 enum 변환을 통해
  실패 시 400 으로 떨어짐. ReDoS 표면 없음.
- `Idempotency-Key` 헤더는 `@NotBlank @Size(min=1, max=128)` — 거대한 키로 Redis 를
  채우는 공격 차단.

## API5:2023 — Broken Function Level Authorization

운영자 / 검수자 전용 endpoint 가 일반 사용자에게 열려 있는가.

| Endpoint | 권한 |
| --- | --- |
| `POST /api/v1/admin/refunds/{id}/retry` | `@PreAuthorize("hasRole('ADMIN')")` |
| `POST /api/v1/products` | (이전) 권한 없음 — ADMIN 만 허용으로 변경 **fixed** |
| `POST /api/v1/inspection-requests/**` | `@PreAuthorize("hasAnyRole('INSPECTOR','ADMIN')")` |
| `POST /api/v1/inspection/appointments/{id}/arrive|complete|reject` | `@PreAuthorize("hasAnyRole('INSPECTOR','ADMIN')")` |

`@EnableMethodSecurity` + URL 단 `/api/v1/admin/**` hasRole(ADMIN) 두 군데에서 검사.

## API6:2023 — Unrestricted Access to Sensitive Business Flows

매칭 엔진 / 결제 흐름이 사람 손이 아닌 봇 / 어뷰즈에 의해 남용될 수 있는가.
거래소형 마켓에서 가장 결이 잘 맞는 항목.

- **호가 폭주 (한 사람이 수십만 건 등록 후 즉시 취소)** — rate limit + `Idempotency-Key`
  로 같은 요청 재실행 차단. 단일 사용자가 burst 20 회 / sustain 5 req/s 이상을 넘으면
  HTTP 429.
- **자기 매칭 (self-trade)** — `MatchEngine.matchNewAsk / matchNewBid` 에서
  `bid.buyerId == listing.sellerId` 인 경우 매칭 거부. `BuyNow / SellNow` 도 같은 검사.
  주의: 외부 KYC 없이 같은 사람이 만든 별개 계정 (collusion) 을 막을 수는 없다 — 이는
  보안 요구가 아니라 운영 기준 (가입 본인인증 + 결제 수단 본인 일치) 으로 다뤄야 한다.
- **PG authorize 지연 어뷰즈** — 매칭 후 PG 인증이 늦으면 ASK/BID 가 markMatched 인
  채로 묶이는데, `AutoCancelStaleTradesService` 가 TTL (15분) 지난 CREATED Trade 를
  주기적으로 취소.
- **Outbox + Idempotency** — 같은 Trade 에 결제는 한 번만 (`PgClient.AuthorizeRequest`
  의 idempotencyKey = TradeId). Kafka 재전송으로 인한 중복 PG 호출도 PG 측 idempotency
  로 흡수.

## API7:2023 — Server Side Request Forgery (SSRF)

사용자 입력 URL 로 서버가 외부 호출을 발사하는 흐름.

- `Product.imageUrl`, `InspectionRequest.photoUrls` 는 DB 저장만 — 서버가 직접 fetch
  하지 않는다 (S3PhotoStorage / LocalPhotoStorage 도 storage 쓰기만). SSRF 표면 없음.
- PG 호출 (`RestPgClient`) 은 `market.pg.base-url` (helm value) 만 사용 — 사용자 입력
  으로 host 가 바뀌지 않음.
- PG webhook (PG → 앱) 은 현재 도메인이 없다. 이전엔 helm 에서 `/webhooks/*` ingress
  와 `PG_WEBHOOK_SECRET` 환경변수만 정의돼 있고 Spring 단 endpoint / HMAC 검증
  코드가 없는 *유령 표면* 이었다 — chart 에서 제거 (API9 항목 참조). 도입 시
  HMAC-SHA256 signature 검증 + replay nonce + IP allowlist 를 함께.

## API8:2023 — Security Misconfiguration

운영 환경에 dev 설정 / mock / secret 평문 노출.

- PG secret / DB password: helm `secret.yaml` 은 `existingSecret` 우선이고, 평문이
  남아 있을 때만 chart-managed Secret 을 생성. `values-prod.yaml` 은 평문을 비우고
  `existingSecret` (외부 cluster secret) 만 사용.
- Wiremock: `values.yaml` (dev) 의 `wiremock.enabled: true` 는 `values-prod.yaml`
  에서 `false` 로 오버라이드. helm template `wiremock.yaml` 은 `if .Values.wiremock.enabled`
  로 가드되어 prod 에 배포되지 않음.
- `permissiveSecurityConfig` 는 `market.security.jwt.enabled=false`일 때만 활성 —
  `values-prod.yaml` 이 `market.security.jwt.enabled=true` 로 강제하므로 운영 인스턴스
  에는 절대 활성되지 않는다. dev 만 anonymous.
- Actuator: `health, info, metrics, prometheus, modulith` 노출. health/info 외에는
  JWT 인증 필요 (SecurityConfig).
- WebSocket CORS: `setAllowedOriginPatterns("*")` — 호가창 push 와 STOMP. 인증된
  주식형 호가 데이터는 read-only 라 origin 제약은 운영 ingress / CSP 단에서 처리.
  도메인 push 메시지는 publish 만, 클라이언트 → 서버 STOMP message 는 아직 없음.

## API9:2023 — Improper Inventory Management

쓰지 않는 endpoint / 설정이 남아 표면을 키우고 있는가.

- 코드에는 deprecated endpoint 없음 (controller 단 grep `@Deprecated` 0건).
- **유령 webhook 설정 제거** **fixed**: helm `values.yaml` 의
  `externalPg.webhookSecret` / `webhookSecretExistingSecret`, `/webhooks` ingress
  path, `secret.yaml` 의 `PG_WEBHOOK_SECRET`, `deployment.yaml` 의 env injection 모두
  제거. 실제 Spring 단 `@RestController` 가 한 줄도 없는데 ingress 와 secret 만
  잡혀 있던 표면이라 그대로 두면 향후 누군가가 endpoint 만 추가해도 권한/HMAC 검증
  없이 trust 가 잡힐 위험. webhook 도입 시점에 endpoint + 검증 코드와 함께 다시 추가.

## API10:2023 — Unsafe Consumption of APIs

외부 vendor 응답을 검증 없이 신뢰하는가.

- PG `AuthorizeResult.pgPaymentId` 는 `Trade.authorizePayment` 가 그대로 저장. 길이/
  형식 검증 없음 — TODO: 운영 PG SDK 정해지면 형식 (예: 영숫자 + dash, 길이 ≤ N) 을
  Bean Validation 으로 강제. 현재 path 가 read-only 저장이고 sink 도 DB 컬럼 +
  refund 시 PG 재호출뿐이라 즉각적인 흐름 영향은 없지만 *vendor 측 변경이 우리
  도메인 모델을 깨지 않게* 한 줄 가드.
- Resilience4j Circuit Breaker + Retry (ADR-0009, ADR-0026) — PG 가 죽거나 5xx 폭주
  하면 fallback (`AuthorizeResult.rejected("CB_OPEN", ...)`) 으로 즉시 거절을 받아
  Trade 를 `FAILED` 로 종착시킴. 외부 장애가 우리 매칭 엔진을 막지 않는다.
- Idempotency: 같은 TradeId 로 두 번 authorize 호출이 와도 PG 측 멱등성 (`PG.idempotency-key`)
  과 우리 Trade 상태 체크 (`status==CREATED` 만 진행) 로 중복 결제 차단.

## 후속 / 미해결

- (낮음) `pgPaymentId` Bean Validation. 운영 PG SDK 형식 확정 후.
- (중) Cross-trader collusion (다중 계정으로 자기 거래) — 본인인증 / 결제수단 일치
  로 풀어야 하는 비-기술 영역. 보안 정책 / 약관에서 다룸.
- (낮음) WebSocket CORS 제한 — 운영 ingress 의 origin 제약과 CSP 로 충분히 좁혀짐.
  필요해지면 `setAllowedOriginPatterns` 를 운영 도메인 list 로 좁힌다.

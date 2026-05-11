# ADR-0020: Redis Lua 기반 token bucket rate limiter

## 상태
적용

## 배경

호가 등록 / 즉시 매매 endpoint 에 rate limit 이 없다. 한정판 발매 시점에 한 사용자가 자동화
스크립트로 같은 호가를 초당 수백 번 등록하면:

1. 다른 사용자의 호가창 응답이 느려진다 (DB 컨텍스트 쟁탈)
2. 매칭 advisory lock 의 길이가 늘어 평균 latency 가 튄다
3. Outbox / Saga 의 백프레셔가 다른 흐름까지 번진다

운영 사고 사례에서 흔히 본 패턴이다. 보호하려는 endpoint 4개:

- `POST /api/v1/listings`     — 판매 호가 등록
- `POST /api/v1/bids`         — 구매 호가 등록
- `POST /api/v1/trades/buy-now`   — 즉시 구매
- `POST /api/v1/trades/sell-now`  — 즉시 판매

이 패턴은 [Stripe API rate limits](https://stripe.com/docs/rate-limits) 등 공개된 표준
스펙으로 정리되어 있고 — 클라이언트가 받는 응답 (`429 Too Many Requests` + `Retry-After`)
까지 RFC 6585 / RFC 7231 로 정해져 있어 도입에 모호함이 적다.

## 결정

### 알고리즘 — Token bucket

통(bucket) 에 토큰이 매 `refillInterval` 마다 `refillTokens` 개씩 채워지고, 요청 1건이 토큰
1개를 소비. 토큰 0 이면 거부. 통의 최대 용량 `capacity` 만큼은 burst 를 허용 — 평소 조용한
사용자가 필요 시 capacity 만큼은 즉시 발사 가능, 그 이후엔 refill rate 로 제한.

상태값은 `tokens (현재 잔량) + lastRefillMs (마지막 refill 기준 시각)` 두 개. Redis 에 hash 한
키로 충분.

### Atomicity — Redis Lua EVAL

`SETNX + INCR + EXPIRE` 같은 단순 조합은 race window 가 있다 (read 후 decision 전에 다른 thread
가 같은 토큰을 본다). **Lua 스크립트 한 번에 read → refill → try-consume → write** 를 모두 처리:

```lua
-- KEYS[1] = bucket key
-- ARGV[1..4] = capacity, refillTokens, refillIntervalMs, nowMs
-- 반환 { allowed (1|0), remainingTokens, retryAfterMs }
```

스크립트가 atomic. 동시에 도착한 두 요청은 Redis 의 single-thread 로 직렬화되어 race condition
없음.

### 키 전략

```
key = "<userId>:<ControllerClass>#<methodName>"
```

같은 사용자의 같은 endpoint 별 통. 사용자가 여러 endpoint 를 쓰면 통이 분리 — 호가 폭주가
즉시 매매 토큰을 소진시키지 않음.

URI 가 아니라 handler method 시그니처로 키 — `/api/v1/listings/{id}` 같은 path variable 의
영향이 없다.

JWT 가 비활성인 dev / 익명 사용자 fallback: `anon-<X-Forwarded-For 의 leftmost IP>`. 한 IP 가 모든
토큰을 다 쓰지 않게 IP 별 구분.

### Spring 통합 — `@RateLimited` + HandlerInterceptor

```kotlin
@PostMapping("/listings")
@RateLimited(capacity = 20, refillTokens = 5)   // 1초 당 5개, burst 20
fun place(...) { ... }
```

annotation 을 method 에 부착. interceptor 가 controller 진입 직전에:

1. handler 의 `@RateLimited` 조회 (없으면 통과)
2. `TokenBucketRateLimiter.tryConsume(...)` 호출
3. 거부면 `429 Too Many Requests` + `Retry-After` 헤더 + RFC 7807 problem+json
4. 통과면 `X-RateLimit-Limit`, `X-RateLimit-Remaining` 헤더 부착

### 정책값 (호가 폭주 보호)

| Endpoint | capacity (burst) | refill | 의미 |
|---|---|---|---|
| POST /listings | 20 | 5/sec | 평소 5/s, 가끔 burst 20개까지 OK |
| POST /bids     | 20 | 5/sec | 동일 |
| POST /trades/buy-now  | 10 | 2/sec | 즉시 매매는 좀 더 보수적 |
| POST /trades/sell-now | 10 | 2/sec | 동일 |

수치는 운영 부하 / 사용자 행태 지표를 보면서 튜닝 가능. user-tier (VIP / 일반) 별 차등은 후속.

### Failure mode — fail-open

Redis 다운 시 `tryConsume` 은 throw 하지 않고 **allowed** 로 fallback. 이유:

- 한정판 발매 시점에 redis 까지 함께 죽으면 사용자 전체가 503 — 사고가 더 커진다
- rate limit 은 최적화/보호이지 정확성에 필요한 정책이 아니다. 일시적 우회는 받아들일 수 있다
- 본 시스템의 실제 정합성 (이중 체결 방지) 은 ADR-0005 의 advisory lock + FOR UPDATE SKIP LOCKED
  로 별도 보장 → rate limiter 가 일시 풀려도 정합성 깨지지 않음

보안 민감 endpoint (예: 로그인 시도) 는 다른 정책이 필요 — fail-closed 가 적합. 본 ADR 의 4개
endpoint 는 인증된 사용자가 자기 행위에 대해 호출하므로 fail-open 이 올바른 선택.

### 다른 환경

- **dev (redis 비활성)**: `InMemoryTokenBucketRateLimiter` 가 자동 활성. 인스턴스 1대라 정확.
  TTL 정리는 별도 cleanup 미구현 — 키 cardinality 가 작아 의미 있는 누수 없음.
- **운영 (redis 활성)**: `RedisTokenBucketRateLimiter` (Lua 기반) 활성.

## 대안 검토

- **Sliding window log** — 모든 요청 timestamp 를 정확하게 보관. Redis sorted set + ZRANGEBYSCORE.
  100% 정확하지만 메모리 사용량 (요청 수 비례) + 매 요청 N 개의 timestamp 조작 → token bucket
  보다 무거움. 본 시스템 규모에 과함.
- **Fixed window counter** — 1초 단위 INCR. 단순하지만 경계 burst 에 취약 (예: 0.999s 와 1.001s
  사이에 통의 두 배가 통과).
- **Resilience4j RateLimiter** — JVM 안에서만 동작. 멀티 인스턴스라 한 사용자가 다른 pod 들에
  요청을 흩뿌리면 보호 효과가 인스턴스 수 만큼 약해진다.
- **Bucket4j + Redis-backed** — 외부 라이브러리. 의존성 늘리지 않으려고 자체 Lua 로 구현 — 코드
  ~50 줄 + Lua ~30 줄, 관리 가능 수준.
- **Spring AOP `@Aspect`** — `aspectjweaver` 의존성 추가 필요. AOP 가 짧긴 하지만 controller
  진입 직전이라는 의미상 위치가 명확하지 않음. HandlerInterceptor 가 Spring MVC 의 표준 위치.

## 결과

- (장) atomic — 동시 요청이 통을 같이 빼는 race 없음 (Lua EVAL 보장)
- (장) Retry-After 헤더가 RFC 표준이라 client 측에서 처리하기 쉬움
- (장) annotation 로 endpoint 별 정책 차등 — 어떤 보호가 걸려 있는지 controller method 에서 바로 보임
- (장) Redis 장애에도 fail-open — 가용성 우선
- (단) 분산 환경에서 Redis RTT 가 latency 에 더해짐 (~0.5~1ms). 호가 등록의 latency budget 내라 OK
- (단) user-tier 별 차등 정책 미구현 — 후속
- (단) 통의 capacity / refill 값이 hardcoded annotation argument 라 운영 중 동적 조정 어려움 —
  필요해지면 properties 로 외부화 + bind

## 후속 후보

- user-tier 별 차등 — VIP 사용자에 더 큰 capacity. 토큰 발급 시 user 의 tier 를 룩업하는 hook.
- 동적 정책 — properties 로 capacity / refill 외부화 + 런타임 변경.
- Adaptive throttling — 시스템 부하 (DB CPU, 큐 깊이) 를 보고 capacity 를 동적 축소. 사고 시점
  자동 보호.
- Distributed denial 방어 — IP 단위 별도 rate limit (이미 IP fallback 키가 있어 base 는 마련).
- Metrics — 차단 이벤트를 Prometheus counter / Grafana 알림 → 비정상 트래픽 빠른 감지.

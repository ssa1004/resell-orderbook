# ADR-0019: Multi-tier 캐시 (Caffeine L1 + Redis L2) + cache stampede 보호

## 상태
적용

## 배경

`MarketDataQueryService.currentStats(skuId)` 는 한 SKU 의 *현재 시세 카드* 를 조회하는
read-only 쿼리. 응답은 *최근 체결가 + best bid/ask + 24h 통계* 를 한 번에 묶어 클라이언트가
추가 round-trip 없이 받는다. 사용자 화면의 호가창 / 가격 카드 / 차트 모두 같은 endpoint 를
폴링하므로 *hot SKU 의 반복 조회* 가 매우 잦다.

내부적으로는 *24시간 aggregation* (COUNT/MIN/AVG/MAX) 를 매 호출마다 DB 에서 다시 계산. 같은
SKU 에 대해 1초에 100번 호출이 들어오면 Postgres 가 같은 결과를 100번 만든다. 트래픽이 늘면
이 부담이 그대로 DB 의 CPU 로 전이.

또 한 가지 위험은 **cache stampede** (= "thundering herd"). 단순한 cache-aside 만 적용하고 TTL
임박하면, TTL 이 0 이 되는 그 순간 여러 thread 가 동시에 cache miss 를 보고 모두 DB 로 몰려간다.
일반 트래픽 + 그 순간 N 개의 무거운 같은 쿼리. *DB 가 그 ms 에 죽을 수 있다.*

## 결정

### 2단 캐시: Caffeine L1 + Redis L2

| 단 | 위치 | TTL | hit 시간 | 역할 |
|---|---|---|---|---|
| L1 | in-process (Caffeine) | 1초 | ns ~ μs | 같은 인스턴스의 폭주 흡수 |
| L2 | Redis | 10초 | ms (RTT) | cross-pod 공유, DB 부담 감소 |

조회 흐름:

```
get(sku):
  L1.get() → hit + 신선이면 반환
  L2.get() → hit + 신선이면 L1 채우고 반환
  loader 호출 (실제 DB) → L1, L2 모두 채우고 반환
```

L1 TTL 이 짧은 이유: cross-pod 데이터 불일치를 *인스턴스 한 대가 1초까지 늦게 본다* 정도로 한정.
거래 체결로 시세가 바뀌어도 1초 안에는 모든 인스턴스가 반영. L2 TTL 이 길수록 DB 부하는 줄지만
"신선하지 않은 응답을 보는 시간" 도 늘어 — 이 트레이드오프의 균형점을 1초/10초로 잡았다.

### Cache stampede 방어 — 두 겹

**(1) Probabilistic early refresh (XFetch)**

TTL 만료 직전이 아니라 그 *전에* 확률적으로 갱신을 시도. entry 별로:

```
shouldRefreshEarly(now):
  rand = uniform(0, 1)
  earlyMs = computeMs * beta * (-log(rand))   # 지수 분포 변량
  return now ≥ expiresAt - earlyMs
```

`computeMs` 가 클수록 (= 무거운 쿼리) 더 일찍 시도, `rand` 의 흩뿌림 덕에 동시에 여러 thread 가
같은 ms 에 만료를 트리거하지 않는다. 출처는 *"Optimal Probabilistic Cache Stampede Prevention"
(VLDB 2015)*. beta = 1.0 이 표준값.

**(2) SETNX recompute lock**

XFetch 만으로도 stampede 가 거의 사라지지만 100% 보장은 아니다. 그래서 마지막 가드로 redis SETNX:

```
recompute(sku):
  if SETNX(lock_key, ttl=5s):
    fresh = loader()                  # 진짜 DB 호출 (이 thread 만)
    L2.SET, L1.put, DEL(lock_key)
    return fresh
  else:                                 # 다른 thread/pod 가 갱신 중
    if 가지고 있는 stale 값이 있으면 그것을 반환
    else: 짧은 polling 으로 lock holder 가 채워주기를 기다림
```

lock TTL 5초 = loader 의 p99 + 여유. lock holder 가 죽어도 그 시간 안에 자동 해제 (deadlock 위험
없음).

### Failure mode

Redis 가 다운되면 limiter 와 같은 정책으로 **fail-open**:
- L2 read 실패 → 그 호출만 cache miss 로 처리 (L1 은 살아있어서 다음 호출은 hit)
- L2 write 실패 → loader 결과 반환 + L1 만 채움 (운영 모니터링 alert)
- lock 시도 실패 → loader 직접 호출 (캐시 효과 일시 상실 + DB 부담 일시 증가)

가용성 우선. 캐시는 *최적화 layer* 이지 정확성에 필요한 layer 가 아니다.

### 구현 매개변수 (application.yml)

```
market.cache.market-stats.l1-ttl-ms: 1000
market.cache.market-stats.l2-ttl-ms: 10000
market.cache.market-stats.l1-max-size: 10000
market.cache.market-stats.lock-ttl-ms: 5000
market.cache.market-stats.loader-retry-wait-ms: 50
market.cache.market-stats.loader-retry-attempts: 3
```

### 적용 범위

현재 적용:
- `MarketDataQueryService.currentStats(skuId)` — 가장 자주 불리고 가장 무거운 read.

미적용 (의도):
- `MarketDataQueryService.ticks(...)` — 시간 구간 + limit 가 다양해서 캐시 키 cardinality 가 폭증.
- `MarketDataQueryService.ohlc(...)` — 같은 이유 + OHLC 는 이미 사전 집계 (ADR-0016) 되어 hot path.

## 대안 검토

- **Spring `@Cacheable`** + Caffeine 만 — cross-pod 공유 없음. 인기 SKU 의 같은 응답을 10대 pod
  가 따로따로 만든다. 트래픽이 늘면 결국 DB 부담은 동일.
- **Redis 만** (L1 없음) — 매 호출마다 RTT (대략 0.5~1ms). hot path 의 ns 응답을 못 만든다.
  L1 의 가치는 in-process hit (코어 pin 된 cache line) 의 빠르기.
- **읽기 전용 read replica + 캐시 없음** — DB 분산은 가능하지만 같은 24h aggregation 을 replica
  가 매번 다시 계산. CPU 분산일 뿐 절대량 감소 아님.
- **Pub/Sub 무효화** — 쓰기 (TradeMatched) 시점에 모든 인스턴스 L1 무효화. 본 ADR 은 도입하지 않음.
  L1 TTL 이 짧아 (1초) 자연 신선도가 충분 + 구현/운영 복잡도 증가.
- **Sliding TTL / per-tier different invalidation** — 운영 단순화 위해 fixed TTL 만.

## 결과

- (장) hot SKU 의 동일 read 응답 시간이 ns ~ ms 로 — DB CPU 부담 ~10x 감소 (10초 TTL 가정)
- (장) cache stampede 방어 — TTL 임박 시 폭주 thread 가 DB 로 몰리지 않음
- (장) Redis 장애 시 fail-open 로 가용성 보존
- (단) 1초 (L1) 까지 cross-pod stale 가능 — 시세 카드의 "지금" 표시가 최대 1초 지연
- (단) 캐시 스키마 (Redis JSON) 가 도메인과 별도 — `MarketStatsCacheRecord` 라는 transport DTO
  하나 더. 도메인 (Money, SkuId) 진화 시 양쪽 모두 손봐야 함
- (단) 운영 모니터링 추가 필요 — cache hit ratio, stampede lock contention, L2 timeout 등

## 후속 후보

- *적용 endpoint 확장* — 인기 검색어, 상품 카드 (catalog) 등에 같은 패턴 적용 가능.
- *cache hit ratio 메트릭* — Caffeine 의 stats 와 Redis 의 hit/miss 카운터를 Prometheus 로 노출.
- *write-through* — TradeMatched 같은 강한 trigger 가 있는 path 는 캐시 entry 를 미리 갱신해
  사용자 응답 즉시 반영 (현재는 invalidate 만).
- *Refresh-ahead 를 별도 worker 로* — 인기 top-K SKU 의 캐시를 background scheduler 가 미리
  refresh — 사용자 호출이 항상 hit.

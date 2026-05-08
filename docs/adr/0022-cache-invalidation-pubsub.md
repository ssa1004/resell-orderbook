# ADR-0022: Cache invalidation 의 cross-pod broadcast (Redis pub/sub)

## 상태
적용

## 배경

ADR-0019 의 2단 캐시 (`TwoTierMarketStatsCache`) 는 *Caffeine L1 (in-process) + Redis L2 (분산)*
조합. L2 는 모든 인스턴스가 공유하므로 한 pod 가 갱신하면 다른 pod 도 같은 값을 읽지만,
**L1 은 각 pod 의 in-process 캐시** 라 pod 마다 따로 가지고 있다.

문제 시나리오:

```
t0  : pod-A 가 sku-X 의 시세 카드를 갱신 (L1, L2 모두 새 값 X')
t0+ε: pod-B 가 sku-X 조회 → L1 에 *이전* 값 X 가 살아있어 그것을 반환 (stale)
t0+1s: pod-B 의 L1 hard-TTL (1초) 만료 → L2 에서 X' 를 가져옴
```

즉 *최대 1초 간* pod-B 의 사용자는 pod-A 가 본 새 값과 다른 값을 본다. 본 시스템은 한정판 발매
시점처럼 시세가 빠르게 바뀌는 환경에서는 1초 stale 도 *사용자 입장에선 호가창 갱신이 누락된 것
처럼* 보인다 — UX 저하.

표준 해결책은 **invalidation broadcast** — 한 pod 가 L1/L2 갱신할 때 다른 pod 들에게 "내가
sku-X 를 갱신했어, 너희 L1 도 비워라" 는 신호를 보낸다.

## 결정

### Redis pub/sub 으로 invalidate 메시지 broadcast

Redis 의 `PUBLISH` / `SUBSCRIBE` 명령. 한 pod 가 publish 하면 같은 채널을 구독 중인 모든 pod 가
수신. *fire-and-forget* — 메시지 도달은 best-effort (구독자가 일시적으로 끊기면 메시지 유실).

```
key 갱신 (write):
  pod-A.cache.put(sku-X, X')   # L1, L2 모두 채움
  PUBLISH market:cache:invalidate "{key:sku-X, source:pod-A, at:...}"

다른 pod (pod-B):
  SUBSCRIBE 콜백:
    msg = parseJson()
    if msg.source == self: skip            # round-trip 방지
    else: l1.evict(msg.key)
    # L2 는 건드리지 않음 — L2 는 pod-A 가 이미 새 값으로 갱신
    # pod-B 의 다음 조회는 L1 miss → L2 hit (== pod-A 가 쓴 X')
```

### sourceId — 자기 메시지 round-trip 방지

자기가 publish 한 메시지를 자기 subscriber 가 받아 또 evict 하면 의미 없는 round-trip.
메시지에 `source` 필드 (pod 식별자) 를 박아 자기 메시지는 무시.

pod 식별자 우선순위: env `POD_NAME` (K8s downward API) → `HOSTNAME` → PID + random UUID 8자.
같은 pod 안에서 publish/subscribe 가 동시에 일어나는데, sourceId 비교만으로 충분.

### 실패 모드 — pub/sub 의 at-most-once 한계

Redis pub/sub 은 *fire-and-forget*. 다음 시나리오에서 메시지 유실:

- 구독자 (subscriber) 가 일시적으로 끊겨 있을 때
- pub/sub 이 *persistent* 가 아니라 *in-memory broadcast*

이 한계를 그대로 받아들이고 — **L1 의 짧은 hard-TTL (1초) 이 안전망**. 메시지가 유실돼도 1초
이내에는 stale 이 사라진다. broadcast 는 *평균 stale 시간을 1초 → ms 단위로 압축* 하는
**최적화** 이지 정합성 도구가 아니다.

### 발행 시점

`TwoTierMarketStatsCache.writeBoth` 안에서 fresh 값을 L1/L2 에 채운 직후. `invalidate(SkuId)`
명시 호출 시점에도 publish — 도메인 측이 무효화를 강제할 때.

```java
private void writeBoth(SkuId key, MarketStats v, Instant computedAt, long computeMs) {
    // ... L1, L2 채우기 ...
    broadcastInvalidate(key);
}

private void broadcastInvalidate(SkuId key) {
    if (invalidationPublisher != null) {
        invalidationPublisher.publish(key.value().toString());
    }
}
```

### Wiring

`CacheInvalidationConfiguration` 이 `market.cache.invalidation-broadcast.enabled=true` 일 때
활성:

- `CacheInvalidationPublisher` 빈 — Redis convertAndSend 래퍼.
- `RedisMessageListenerContainer` 빈 — Spring 의 pub/sub 인프라. `CacheInvalidationSubscriber`
  를 ChannelTopic 에 등록.
- `TwoTierMarketStatsCache` 에 setter 로 publisher 주입 — broadcast 비활성 환경에서는 setter 가
  안 불려서 `invalidationPublisher == null` → 그냥 L1 TTL 만으로 동작.

dev (`redis-enabled=false`) 는 무관 — pub/sub 자체가 비활성, 단일 인스턴스라 broadcast 가 무의미.

## 대안 검토

- **Redis Streams (XADD / XREAD)** — persistent + consumer group. 메시지 유실 없음. 하지만 본 용도
  (invalidate 는 idempotent) 에는 *과한* 보장 — 메시지 유실 시 1초 stale 만 더 보는 비용 vs
  consumer offset / DLQ 운영 부담. 단순한 pub/sub 이 본 패턴에 더 어울린다.
- **Kafka topic** — 같은 이유로 과함. + 본 시스템에 이미 Kafka 가 있지만 outbox 이벤트와 다른
  성격 (도메인 이벤트는 영구 보관, cache invalidate 는 휘발성).
- **Redisson distributed cache (RMap with listener)** — Redisson 이 내부적으로 pub/sub 으로
  invalidation 메시지 보내줌. 라이브러리 의존이 한 단 더 깊어지고, Caffeine 과의 직접 통합이
  어려워짐 (Redisson 의 LocalCachedMap 으로 대체해야 함). 본 ADR 의 직접 구현이 라이브러리 의존
  최소화.
- **Database trigger + LISTEN/NOTIFY (Postgres)** — 같은 broadcast 효과. 본 시스템의 hot path 가
  Redis 라 자연스러운 선택은 Redis. + LISTEN/NOTIFY 는 connection 수에 비례해 부하 증가.
- **갱신 시 L1 자체를 안 쓴다** — L2 (Redis) 만 사용. 매 조회 ms 단위 RTT — hot SKU 의 누적 RTT
  부하가 크고, in-process ns 단위 hit 의 가치가 사라짐. L1 은 살리는 게 맞다.

## 결과

- (장) cross-pod L1 stale 평균 시간이 1초 → ms 단위로 압축
- (장) pub/sub at-most-once 의 한계는 L1 짧은 TTL 이 안전망 — 이중 보호
- (장) sourceId 로 자기 메시지 round-trip 방지 — 효율적
- (장) broadcast 비활성 환경 (dev) 에서는 setter 미주입 → 기존 동작 유지
- (단) Redis pub/sub 은 fire-and-forget — *정합성 도구로 쓰면 안 됨*. 본 ADR 은 최적화 위치 명시
- (단) 새 메시지 형식이 추가될 때 envelope 호환성 관리 필요 — versioned key prefix 로 안전망
  (`v1:`)
- (단) listener thread (Spring 의 `RedisMessageListenerContainer`) 가 별 thread 1개 점유

## 후속 후보

- *Hot SKU 동적 TTL* — HyperLogLog 로 sku 별 시간당 unique 조회 수 카운트, top N 만 길게 (10s).
  cold SKU 는 짧게 (1s). invalidate broadcast 는 hot/cold 구분 없이 동작.
- *Versioned cache key* — 도메인 변경 시 prefix 를 바꿔 일괄 무효화 — 이번엔 broadcast 자체가
  필요 없음.
- *지표* — invalidate 메시지 수 / 자기 무시율 / handler 실패율 을 Prometheus 로. broadcast 채널의
  건강 진단.
- *SUBSCRIBE 끊김 감지 + 자동 fail-back* — listener container 의 health 체크 + 끊김 시 L1 TTL
  강제 단축.

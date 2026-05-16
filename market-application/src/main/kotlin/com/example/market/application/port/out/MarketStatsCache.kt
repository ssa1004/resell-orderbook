package com.example.market.application.port.out

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.MarketStats
import java.util.function.Supplier

/**
 * 시세 카드(MarketStats) 의 hot SKU 캐시 (ADR-0019).
 *
 * **왜 필요한가**: hot SKU (인기 한정판) 의 시세 카드 조회는 사용자 화면 폴링 / 호가창
 * 새로고침 / 차트 보기 등으로 매우 자주 호출된다. 매번 DB 로 가면 Postgres 가 같은 SKU 의
 * COUNT/MIN/AVG/MAX (24h aggregation) 를 반복 계산해 압박을 받는다. 자주 읽히고 가끔 갱신되는
 * 파생 read model 은 일반적으로 캐시에 친화적인 워크로드.
 *
 * **2단 구성 (Caffeine L1 + Redis L2)**:
 * - L1 = in-process Caffeine. ns 단위 hit. TTL 1초. cross-pod 불일치는 짧은 시간 허용.
 * - L2 = Redis. ms 단위 hit. TTL 10초. 모든 인스턴스가 같은 SKU 면 같은 값 (cross-pod 일관성).
 * - 둘 다 miss → loader 호출 → L1, L2 모두 채움.
 *
 * **Cache stampede 보호** (ADR-0019): TTL 만료 직후 동시에 N 개 thread 가 cache miss → DB 로
 * 몰려가는 현상. 두 가지 기법 결합:
 * 1. *Probabilistic early refresh* — TTL 의 마지막 일부 구간에 진입하면 `-log(rand) * beta * computeMs`
 *    만큼 일찍 갱신을 시도. 한 thread 가 갱신하는 동안 다른 thread 는 여전히 "유효" 한 stale 값을
 *    받는다. 동시에 여러 thread 가 만료를 트리거할 확률을 지수 분포로 흩뿌리는 효과.
 * 2. *Redis SETNX lock* — 그래도 동시 갱신이 발생할 수 있으니, recompute 진입 직전에
 *    SETNX 로 짧은 lock 을 잡는다. lock 못 잡은 thread 는 지금 cache 에 있는 값을 반환 (stale read).
 *    lock TTL 은 loader 의 99-percentile 시간 + 여유 (예: 5초). lock 자체가 만료되면 다시 풀림.
 *
 * 구현체는 보통 thread-safe — Caffeine 자체가 thread-safe 이고 Redis 는 atomic 명령만 사용.
 */
interface MarketStatsCache {

    /**
     * cache lookup → miss 면 loader 호출 → 채움.
     *
     * loader 는 한 번만 (또는 stampede 보호로 인해 그대로 cached 값) 호출되는 것이 보장. loader
     * 가 던진 예외는 그대로 전파 (캐시는 실패를 캐싱하지 않음).
     */
    fun getOrCompute(key: SkuId, loader: Supplier<MarketStats>): MarketStats

    /**
     * 명시적 무효화 — 도메인 이벤트 (TradeMatched 등) 를 본 곳이 호출. 다음 조회는 강제로
     * loader 를 돌린다. 구현체에 따라 cross-pod L1 무효화는 어려우니 (pub/sub 필요) 보통 짧은
     * L1 TTL 로 자연 해결한다.
     */
    fun invalidate(key: SkuId)
}

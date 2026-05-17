package com.example.market.batch.scheduler

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Spring Batch Job 들의 정기 트리거.
 *
 * 스케줄:
 * - **autoCancelStaleTrades** — 매분. 결제 TTL (Time-To-Live, 유효시간 15분) 을 넘긴
 *   CREATED 거래를 CANCELLED 로 자동 취소
 * - **expireStaleListings** — 매일 03:00 KST. 30일 지난 ACTIVE 판매 호가를 EXPIRED 로
 * - **expireStaleBids** — 매일 03:30 KST. 30일 지난 ACTIVE 구매 호가를 EXPIRED 로
 *
 * 각 스케줄에는 ShedLock (DB 한 행을 잠가 인스턴스 사이의 중복 실행을 막는 라이브러리) 이
 * 적용되어 있어, 인스턴스가 여러 대인 환경에서도 정확히 한 인스턴스만 실행한다.
 */
@Component
@Profile("prod", "scheduler") // 운영 + 명시 활성화 시만. dev/it 에서는 비활성
open class MarketJobScheduler(
    private val jobLauncher: JobLauncher,
    @Qualifier("autoCancelStaleTradesJob") private val autoCancelStaleTradesJob: Job,
    @Qualifier("expireStaleListingsJob") private val expireStaleListingsJob: Job,
    @Qualifier("expireStaleBidsJob") private val expireStaleBidsJob: Job,
    @Qualifier("ohlcOneMinJob") private val ohlcOneMinJob: Job,
    @Qualifier("ohlcFiveMinJob") private val ohlcFiveMinJob: Job,
    @Qualifier("ohlcOneHourJob") private val ohlcOneHourJob: Job,
    @Qualifier("ohlcOneDayJob") private val ohlcOneDayJob: Job,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 매분 실행. 결제 TTL (Time-To-Live, 유효시간 15분) 을 넘긴 거래를 자동 취소.
     * 짧은 주기라 lockAtMostFor (잠금 자동 해제 시간) 도 짧게 (5분) — 한 번 실행이 늘어져도
     * 다음 분에 다른 인스턴스가 이어 받을 수 있다.
     */
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "autoCancelStaleTrades", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    open fun runAutoCancelStaleTrades() {
        launch(autoCancelStaleTradesJob, "autoCancelStaleTradesJob")
    }

    /**
     * 매일 03:00 KST. 30일 만료 listing 정리.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "expireStaleListings", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    open fun runExpireStaleListings() {
        launch(expireStaleListingsJob, "expireStaleListingsJob")
    }

    /**
     * 매일 03:30 KST. 30일 지난 BID 정리. listing 정리(03:00) 와 30분 시간차를 둬서 두 배치가
     * 동시에 DB 부하를 주지 않도록 분산.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "expireStaleBids", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    open fun runExpireStaleBids() {
        launch(expireStaleBidsJob, "expireStaleBidsJob")
    }

    // ── OHLC 차트 데이터 사전 집계 ─────────────────────────────────────────────────
    // 각 cron 은 직전 봉(bucket — 시간이 지나 이미 닫힌 것) 만 집계한다 → 진행 중인 봉을 건들지
    // 않아 데이터 누락 회피. ShedLock 의 lockAtMostFor 는 짧게 잡았다 — 분 단위 배치라 잠금이
    // 풀리면 다음 분에 다른 인스턴스가 이어 처리할 수 있다.

    /** 매분. 직전 1분 봉 집계. 응답 지연이 ms 단위라 클라이언트가 거의 실시간 차트로 받는다. */
    @Scheduled(cron = "5 * * * * *") // 매 분 5초에 — 1분 봉이 닫힌 직후
    @SchedulerLock(name = "ohlcOneMin", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
    open fun runOhlcOneMin() {
        launch(ohlcOneMinJob, "ohlcOneMinJob")
    }

    /** 5분마다. 직전 5분 봉. */
    @Scheduled(cron = "10 */5 * * * *")
    @SchedulerLock(name = "ohlcFiveMin", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    open fun runOhlcFiveMin() {
        launch(ohlcFiveMinJob, "ohlcFiveMinJob")
    }

    /** 매시 정각 + 30초. 직전 1시간 봉. */
    @Scheduled(cron = "30 0 * * * *")
    @SchedulerLock(name = "ohlcOneHour", lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    open fun runOhlcOneHour() {
        launch(ohlcOneHourJob, "ohlcOneHourJob")
    }

    /** 매일 00:01 UTC. 직전 1일 봉. */
    @Scheduled(cron = "0 1 0 * * *") // UTC 자정 + 1분 — 어제 1일 봉이 닫힌 직후
    @SchedulerLock(name = "ohlcOneDay", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    open fun runOhlcOneDay() {
        launch(ohlcOneDayJob, "ohlcOneDayJob")
    }

    private fun launch(job: Job, name: String) {
        try {
            val params = JobParametersBuilder()
                .addLong("triggeredAt", clock.millis())
                .toJobParameters()
            log.info("triggering {}", name)
            jobLauncher.run(job, params)
        } catch (e: Exception) {
            log.error("{} failed", name, e)
        }
    }
}

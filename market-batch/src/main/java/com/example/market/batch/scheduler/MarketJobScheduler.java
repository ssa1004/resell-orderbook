package com.example.market.batch.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Spring Batch Job 정기 trigger.
 *
 * <p>schedule:
 * <ul>
 *   <li><b>autoCancelStaleTrades</b> — 매 분. 결제 TTL (15분) 지난 CREATED 거래를 CANCELLED 로</li>
 *   <li><b>expireStaleListings</b> — 매일 03:00 KST. 30일 지난 ACTIVE listing 만료 처리</li>
 *   <li><b>expireStaleBids</b> — 매일 03:30 KST. 30일 지난 ACTIVE bid 만료 처리</li>
 * </ul>
 *
 * <p>각 schedule 은 ShedLock 으로 분산 lock 을 잡아 multi-instance 환경에서 한 인스턴스만
 * 실행한다.</p>
 */
@Component
@Profile({"prod", "scheduler"})   // 운영 + 명시 활성화 시만. dev/it 에서는 비활성
public class MarketJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketJobScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job autoCancelStaleTradesJob;
    private final Job expireStaleListingsJob;
    private final Job expireStaleBidsJob;
    private final Job ohlcOneMinJob;
    private final Job ohlcFiveMinJob;
    private final Job ohlcOneHourJob;
    private final Job ohlcOneDayJob;
    private final Clock clock;

    public MarketJobScheduler(JobLauncher jobLauncher,
                              @Qualifier("autoCancelStaleTradesJob") Job autoCancelStaleTradesJob,
                              @Qualifier("expireStaleListingsJob") Job expireStaleListingsJob,
                              @Qualifier("expireStaleBidsJob") Job expireStaleBidsJob,
                              @Qualifier("ohlcOneMinJob") Job ohlcOneMinJob,
                              @Qualifier("ohlcFiveMinJob") Job ohlcFiveMinJob,
                              @Qualifier("ohlcOneHourJob") Job ohlcOneHourJob,
                              @Qualifier("ohlcOneDayJob") Job ohlcOneDayJob,
                              Clock clock) {
        this.jobLauncher = jobLauncher;
        this.autoCancelStaleTradesJob = autoCancelStaleTradesJob;
        this.expireStaleListingsJob = expireStaleListingsJob;
        this.expireStaleBidsJob = expireStaleBidsJob;
        this.ohlcOneMinJob = ohlcOneMinJob;
        this.ohlcFiveMinJob = ohlcFiveMinJob;
        this.ohlcOneHourJob = ohlcOneHourJob;
        this.ohlcOneDayJob = ohlcOneDayJob;
        this.clock = clock;
    }

    /**
     * 매 분 실행. payment TTL (15분) 초과한 거래를 자동 취소.
     * 짧은 schedule 이라 lockAtMostFor 도 짧게 (5분) — 행여 lock 해제가 늦어도 큰 영향 없음.
     */
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "autoCancelStaleTrades", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void runAutoCancelStaleTrades() {
        launch(autoCancelStaleTradesJob, "autoCancelStaleTradesJob");
    }

    /**
     * 매일 03:00 KST. 30일 만료 listing 정리.
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "expireStaleListings", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runExpireStaleListings() {
        launch(expireStaleListingsJob, "expireStaleListingsJob");
    }

    /**
     * 매일 03:30 KST. 30일 만료 bid 정리. listing 과 시간차 두어 동시 부하 회피.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "expireStaleBids", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runExpireStaleBids() {
        launch(expireStaleBidsJob, "expireStaleBidsJob");
    }

    // ── OHLC 차트 데이터 사전 집계 ─────────────────────────────────────────────────
    // 각 cron 은 *직전 bucket* (시간 지나서 닫힌 것) 만 집계 → 진행 중 bucket 누락 회피.
    // ShedLock 은 lockAtMostFor 짧게 — 분 단위 batch 라 lock 풀리면 곧 다음 인스턴스가 cover.

    /** 매 분. 직전 1분 bucket 집계. ms 단위 latency 라 클라이언트가 거의 실시간 차트. */
    @Scheduled(cron = "5 * * * * *")        // 매 분 5초에 — 1분 bucket 닫힌 직후
    @SchedulerLock(name = "ohlcOneMin", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
    public void runOhlcOneMin() {
        launch(ohlcOneMinJob, "ohlcOneMinJob");
    }

    /** 매 5분. 직전 5분 bucket. */
    @Scheduled(cron = "10 */5 * * * *")
    @SchedulerLock(name = "ohlcFiveMin", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    public void runOhlcFiveMin() {
        launch(ohlcFiveMinJob, "ohlcFiveMinJob");
    }

    /** 매 시간 정각 + 30초. 직전 1시간 bucket. */
    @Scheduled(cron = "30 0 * * * *")
    @SchedulerLock(name = "ohlcOneHour", lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    public void runOhlcOneHour() {
        launch(ohlcOneHourJob, "ohlcOneHourJob");
    }

    /** 매일 00:01 UTC. 직전 1일 bucket. */
    @Scheduled(cron = "0 1 0 * * *")        // UTC 자정 + 1분 — 어제 1일 bucket 닫힘
    @SchedulerLock(name = "ohlcOneDay", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void runOhlcOneDay() {
        launch(ohlcOneDayJob, "ohlcOneDayJob");
    }

    private void launch(Job job, String name) {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("triggeredAt", clock.millis())
                    .toJobParameters();
            log.info("triggering {}", name);
            jobLauncher.run(job, params);
        } catch (Exception e) {
            log.error("{} failed", name, e);
        }
    }
}

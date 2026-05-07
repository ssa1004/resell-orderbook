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
 * Spring Batch Job 들의 정기 트리거.
 *
 * <p>스케줄:
 * <ul>
 *   <li><b>autoCancelStaleTrades</b> — 매분. 결제 TTL (Time-To-Live, 유효시간 15분) 을 넘긴
 *       CREATED 거래를 CANCELLED 로 자동 취소</li>
 *   <li><b>expireStaleListings</b> — 매일 03:00 KST. 30일 지난 ACTIVE 판매 호가를 EXPIRED 로</li>
 *   <li><b>expireStaleBids</b> — 매일 03:30 KST. 30일 지난 ACTIVE 구매 호가를 EXPIRED 로</li>
 * </ul>
 *
 * <p>각 스케줄에는 ShedLock (DB 한 행을 잠가 인스턴스 사이의 중복 실행을 막는 라이브러리) 이
 * 적용되어 있어, 인스턴스가 여러 대인 환경에서도 정확히 한 인스턴스만 실행한다.</p>
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
     * 매분 실행. 결제 TTL (Time-To-Live, 유효시간 15분) 을 넘긴 거래를 자동 취소.
     * 짧은 주기라 lockAtMostFor (잠금 자동 해제 시간) 도 짧게 (5분) — 한 번 실행이 늘어져도
     * 다음 분에 다른 인스턴스가 이어 받을 수 있다.
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
     * 매일 03:30 KST. 30일 지난 BID 정리. listing 정리(03:00) 와 30분 시간차를 둬서 두 배치가
     * 동시에 DB 부하를 주지 않도록 분산.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "expireStaleBids", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runExpireStaleBids() {
        launch(expireStaleBidsJob, "expireStaleBidsJob");
    }

    // ── OHLC 차트 데이터 사전 집계 ─────────────────────────────────────────────────
    // 각 cron 은 직전 봉(bucket — 시간이 지나 이미 닫힌 것) 만 집계한다 → 진행 중인 봉을 건들지
    // 않아 데이터 누락 회피. ShedLock 의 lockAtMostFor 는 짧게 잡았다 — 분 단위 배치라 잠금이
    // 풀리면 다음 분에 다른 인스턴스가 이어 처리할 수 있다.

    /** 매분. 직전 1분 봉 집계. 응답 지연이 ms 단위라 클라이언트가 거의 실시간 차트로 받는다. */
    @Scheduled(cron = "5 * * * * *")        // 매 분 5초에 — 1분 봉이 닫힌 직후
    @SchedulerLock(name = "ohlcOneMin", lockAtMostFor = "PT2M", lockAtLeastFor = "PT30S")
    public void runOhlcOneMin() {
        launch(ohlcOneMinJob, "ohlcOneMinJob");
    }

    /** 5분마다. 직전 5분 봉. */
    @Scheduled(cron = "10 */5 * * * *")
    @SchedulerLock(name = "ohlcFiveMin", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    public void runOhlcFiveMin() {
        launch(ohlcFiveMinJob, "ohlcFiveMinJob");
    }

    /** 매시 정각 + 30초. 직전 1시간 봉. */
    @Scheduled(cron = "30 0 * * * *")
    @SchedulerLock(name = "ohlcOneHour", lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
    public void runOhlcOneHour() {
        launch(ohlcOneHourJob, "ohlcOneHourJob");
    }

    /** 매일 00:01 UTC. 직전 1일 봉. */
    @Scheduled(cron = "0 1 0 * * *")        // UTC 자정 + 1분 — 어제 1일 봉이 닫힌 직후
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

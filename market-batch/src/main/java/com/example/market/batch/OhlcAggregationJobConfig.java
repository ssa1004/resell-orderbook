package com.example.market.batch;

import com.example.market.application.service.OhlcAggregationService;
import com.example.market.domain.marketdata.OhlcPeriod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * OHLC 집계 batch — 4개 period 별 1개씩 Job 정의.
 *
 * <p>각 Job 의 tasklet 이 {@link OhlcAggregationService#closePreviousBucket} 한 번 호출.
 * 직전에 닫힌 (시간 지난) bucket 만 처리해 진행 중 bucket 누락 회피.</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class OhlcAggregationJobConfig {

    private final OhlcAggregationService ohlcService;
    private final Clock clock;

    @Bean
    public Job ohlcOneMinJob(JobRepository jr, Step ohlcOneMinStep) {
        return new JobBuilder("ohlcOneMinJob", jr).start(ohlcOneMinStep).build();
    }

    @Bean
    public Step ohlcOneMinStep(JobRepository jr, PlatformTransactionManager tx) {
        return aggregationStep("ohlcOneMinStep", OhlcPeriod.ONE_MIN, jr, tx);
    }

    @Bean
    public Job ohlcFiveMinJob(JobRepository jr, Step ohlcFiveMinStep) {
        return new JobBuilder("ohlcFiveMinJob", jr).start(ohlcFiveMinStep).build();
    }

    @Bean
    public Step ohlcFiveMinStep(JobRepository jr, PlatformTransactionManager tx) {
        return aggregationStep("ohlcFiveMinStep", OhlcPeriod.FIVE_MIN, jr, tx);
    }

    @Bean
    public Job ohlcOneHourJob(JobRepository jr, Step ohlcOneHourStep) {
        return new JobBuilder("ohlcOneHourJob", jr).start(ohlcOneHourStep).build();
    }

    @Bean
    public Step ohlcOneHourStep(JobRepository jr, PlatformTransactionManager tx) {
        return aggregationStep("ohlcOneHourStep", OhlcPeriod.ONE_HOUR, jr, tx);
    }

    @Bean
    public Job ohlcOneDayJob(JobRepository jr, Step ohlcOneDayStep) {
        return new JobBuilder("ohlcOneDayJob", jr).start(ohlcOneDayStep).build();
    }

    @Bean
    public Step ohlcOneDayStep(JobRepository jr, PlatformTransactionManager tx) {
        return aggregationStep("ohlcOneDayStep", OhlcPeriod.ONE_DAY, jr, tx);
    }

    private Step aggregationStep(String name, OhlcPeriod period,
                                 JobRepository jr, PlatformTransactionManager tx) {
        return new StepBuilder(name, jr)
                .tasklet((contribution, chunkContext) -> {
                    int written = ohlcService.closePreviousBucket(period, clock.instant());
                    log.info("ohlc {} step finished candlesWritten={}", period, written);
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }
}

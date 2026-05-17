package com.example.market.batch

import com.example.market.application.service.OhlcAggregationService
import com.example.market.domain.marketdata.OhlcPeriod
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock

/**
 * OHLC 집계 batch — 4개 period 별 1개씩 Job 정의.
 *
 * 각 Job 의 tasklet 이 [OhlcAggregationService.closePreviousBucket] 한 번 호출.
 * 직전에 닫힌 (시간 지난) bucket 만 처리해 진행 중 bucket 누락 회피.
 */
@Configuration
class OhlcAggregationJobConfig(
    private val ohlcService: OhlcAggregationService,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun ohlcOneMinJob(jr: JobRepository, ohlcOneMinStep: Step): Job =
        JobBuilder("ohlcOneMinJob", jr).start(ohlcOneMinStep).build()

    @Bean
    fun ohlcOneMinStep(jr: JobRepository, tx: PlatformTransactionManager): Step =
        aggregationStep("ohlcOneMinStep", OhlcPeriod.ONE_MIN, jr, tx)

    @Bean
    fun ohlcFiveMinJob(jr: JobRepository, ohlcFiveMinStep: Step): Job =
        JobBuilder("ohlcFiveMinJob", jr).start(ohlcFiveMinStep).build()

    @Bean
    fun ohlcFiveMinStep(jr: JobRepository, tx: PlatformTransactionManager): Step =
        aggregationStep("ohlcFiveMinStep", OhlcPeriod.FIVE_MIN, jr, tx)

    @Bean
    fun ohlcOneHourJob(jr: JobRepository, ohlcOneHourStep: Step): Job =
        JobBuilder("ohlcOneHourJob", jr).start(ohlcOneHourStep).build()

    @Bean
    fun ohlcOneHourStep(jr: JobRepository, tx: PlatformTransactionManager): Step =
        aggregationStep("ohlcOneHourStep", OhlcPeriod.ONE_HOUR, jr, tx)

    @Bean
    fun ohlcOneDayJob(jr: JobRepository, ohlcOneDayStep: Step): Job =
        JobBuilder("ohlcOneDayJob", jr).start(ohlcOneDayStep).build()

    @Bean
    fun ohlcOneDayStep(jr: JobRepository, tx: PlatformTransactionManager): Step =
        aggregationStep("ohlcOneDayStep", OhlcPeriod.ONE_DAY, jr, tx)

    private fun aggregationStep(
        name: String,
        period: OhlcPeriod,
        jr: JobRepository,
        tx: PlatformTransactionManager,
    ): Step = StepBuilder(name, jr)
        .tasklet({ _, _ ->
            val written = ohlcService.closePreviousBucket(period, clock.instant())
            log.info("ohlc {} step finished candlesWritten={}", period, written)
            RepeatStatus.FINISHED
        }, tx)
        .build()
}

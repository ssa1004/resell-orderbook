package com.example.market.batch

import com.example.market.application.port.`in`.AutoCancelStaleTradesUseCase
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration

/**
 * TTL (default 15분) 지난 CREATED 거래 자동 cancelOnPaymentFailure.
 */
@Configuration
class AutoCancelStaleTradesJobConfig(
    private val useCase: AutoCancelStaleTradesUseCase,
    @Value("\${market.trade.payment-ttl-minutes:15}") private val ttlMinutes: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun autoCancelStaleTradesJob(jobRepository: JobRepository, autoCancelStaleTradesStep: Step): Job =
        JobBuilder("autoCancelStaleTradesJob", jobRepository)
            .start(autoCancelStaleTradesStep)
            .build()

    @Bean
    fun autoCancelStaleTradesStep(jobRepository: JobRepository, tm: PlatformTransactionManager): Step =
        StepBuilder("autoCancelStaleTradesStep", jobRepository)
            .tasklet({ _, _ ->
                var total = 0
                var batch: Int
                do {
                    batch = useCase.cancelStale(Duration.ofMinutes(ttlMinutes), 500)
                    total += batch
                } while (batch > 0)
                log.info("autoCancelStaleTradesJob — total cancelled={}", total)
                RepeatStatus.FINISHED
            }, tm)
            .build()
}

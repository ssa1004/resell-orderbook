package com.example.market.batch

import com.example.market.application.port.`in`.ExpireStaleListingsUseCase
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

/**
 * 만료된 ACTIVE Listing 을 EXPIRED 로 일괄 마킹 — 매 시간 cron 권장.
 *
 * Tasklet 모드 — application use case 가 한 batch 처리. 1000개 단위로 fully done 까지 반복.
 */
@Configuration
class StaleListingExpirationJobConfig(
    private val useCase: ExpireStaleListingsUseCase,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun expireStaleListingsJob(jobRepository: JobRepository, expireStaleListingsStep: Step): Job =
        JobBuilder("expireStaleListingsJob", jobRepository)
            .start(expireStaleListingsStep)
            .build()

    @Bean
    fun expireStaleListingsStep(jobRepository: JobRepository, tm: PlatformTransactionManager): Step =
        StepBuilder("expireStaleListingsStep", jobRepository)
            .tasklet({ contribution, _ ->
                var total = 0
                var batch: Int
                do {
                    batch = useCase.expireBatch(1000)
                    total += batch
                } while (batch > 0)
                contribution.incrementWriteCount(total.toLong())
                log.info("expireStaleListingsJob — total expired={}", total)
                RepeatStatus.FINISHED
            }, tm)
            .build()
}

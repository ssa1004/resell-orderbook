package com.example.market.batch

import com.example.market.application.port.`in`.ExpireStaleBidsUseCase
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

@Configuration
class StaleBidExpirationJobConfig(
    private val useCase: ExpireStaleBidsUseCase,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun expireStaleBidsJob(jobRepository: JobRepository, expireStaleBidsStep: Step): Job =
        JobBuilder("expireStaleBidsJob", jobRepository)
            .start(expireStaleBidsStep)
            .build()

    @Bean
    fun expireStaleBidsStep(jobRepository: JobRepository, tm: PlatformTransactionManager): Step =
        StepBuilder("expireStaleBidsStep", jobRepository)
            .tasklet({ _, _ ->
                var total = 0
                var batch: Int
                do {
                    batch = useCase.expireBatch(1000)
                    total += batch
                } while (batch > 0)
                log.info("expireStaleBidsJob — total expired={}", total)
                RepeatStatus.FINISHED
            }, tm)
            .build()
}

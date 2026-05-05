package com.example.market.batch;

import com.example.market.application.port.in.AutoCancelStaleTradesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

/**
 * TTL (default 15분) 지난 CREATED 거래 자동 cancelOnPaymentFailure.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AutoCancelStaleTradesJobConfig {

    private final AutoCancelStaleTradesUseCase useCase;

    @Value("${market.trade.payment-ttl-minutes:15}")
    private long ttlMinutes;

    @Bean
    public Job autoCancelStaleTradesJob(JobRepository jobRepository, Step autoCancelStaleTradesStep) {
        return new JobBuilder("autoCancelStaleTradesJob", jobRepository)
                .start(autoCancelStaleTradesStep)
                .build();
    }

    @Bean
    public Step autoCancelStaleTradesStep(JobRepository jobRepository, PlatformTransactionManager tm) {
        return new StepBuilder("autoCancelStaleTradesStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    int total = 0;
                    int batch;
                    do {
                        batch = useCase.cancelStale(Duration.ofMinutes(ttlMinutes), 500);
                        total += batch;
                    } while (batch > 0);
                    log.info("autoCancelStaleTradesJob — total cancelled={}", total);
                    return RepeatStatus.FINISHED;
                }, tm)
                .build();
    }
}

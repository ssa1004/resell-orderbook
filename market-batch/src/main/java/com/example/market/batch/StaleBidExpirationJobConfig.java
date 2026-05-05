package com.example.market.batch;

import com.example.market.application.port.in.ExpireStaleBidsUseCase;
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

@Configuration
@RequiredArgsConstructor
@Slf4j
public class StaleBidExpirationJobConfig {

    private final ExpireStaleBidsUseCase useCase;

    @Bean
    public Job expireStaleBidsJob(JobRepository jobRepository, Step expireStaleBidsStep) {
        return new JobBuilder("expireStaleBidsJob", jobRepository)
                .start(expireStaleBidsStep)
                .build();
    }

    @Bean
    public Step expireStaleBidsStep(JobRepository jobRepository, PlatformTransactionManager tm) {
        return new StepBuilder("expireStaleBidsStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    int total = 0;
                    int batch;
                    do {
                        batch = useCase.expireBatch(1000);
                        total += batch;
                    } while (batch > 0);
                    log.info("expireStaleBidsJob — total expired={}", total);
                    return RepeatStatus.FINISHED;
                }, tm)
                .build();
    }
}

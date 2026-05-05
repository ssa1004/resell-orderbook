package com.example.market.batch;

import com.example.market.application.port.in.ExpireStaleListingsUseCase;
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

/**
 * 만료된 ACTIVE Listing 을 EXPIRED 로 일괄 마킹 — 매 시간 cron 권장.
 *
 * <p>Tasklet 모드 — application use case 가 한 batch 처리. 1000개 단위로 fully done 까지 반복.</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class StaleListingExpirationJobConfig {

    private final ExpireStaleListingsUseCase useCase;

    @Bean
    public Job expireStaleListingsJob(JobRepository jobRepository, Step expireStaleListingsStep) {
        return new JobBuilder("expireStaleListingsJob", jobRepository)
                .start(expireStaleListingsStep)
                .build();
    }

    @Bean
    public Step expireStaleListingsStep(JobRepository jobRepository, PlatformTransactionManager tm) {
        return new StepBuilder("expireStaleListingsStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    int total = 0;
                    int batch;
                    do {
                        batch = useCase.expireBatch(1000);
                        total += batch;
                    } while (batch > 0);
                    contribution.incrementWriteCount(total);
                    log.info("expireStaleListingsJob — total expired={}", total);
                    return RepeatStatus.FINISHED;
                }, tm)
                .build();
    }
}

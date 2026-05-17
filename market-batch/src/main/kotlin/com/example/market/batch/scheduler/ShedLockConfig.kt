package com.example.market.batch.scheduler

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

/**
 * ShedLock 활성화 — multi-instance 에서 같은 @Scheduled 가 동시에 실행되지 않게 분산 lock.
 *
 * `defaultLockAtMostFor` = 30분: 가장 긴 batch (만료 호가 정리) 가 그 안에 끝나야
 * 한다. holder Pod 가 이 시간 내 잠시 멈춰도 다음 trigger 까지는 락 유지.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
@Profile("prod", "scheduler")
class ShedLockConfig {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )
}

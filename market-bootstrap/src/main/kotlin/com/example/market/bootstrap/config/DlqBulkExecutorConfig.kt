package com.example.market.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * DLQ bulk worker pool — [com.example.market.application.service.DlqBulkAdminService] 가
 * confirm=true 호출에서 디스패치하는 백그라운드 worker 의 스레드 풀.
 *
 * 왜 별도 풀인가:
 * - 서블릿 컨테이너의 요청 스레드와 격리 — bulk 작업이 길어져도 일반 요청 처리에 영향 X.
 * - 운영자 콘솔이 한꺼번에 여러 bulk 작업을 던져도 풀 크기로 제한 — 한 명의 운영자가
 *   Kafka producer 를 폭주시키는 일을 막는다 (bulkhead 의 가벼운 변형).
 * - 예외가 caller 로 새지 않게 task 마다 try/catch 안에서 처리 — 풀 자체는 깨지지 않는다.
 *
 * 코어 4 / 큐 32 — 한 운영자가 bulk 4개 동시 처리 가능, 그 이상은 큐에 적재. 큐가 가득 차면
 * 기본 reject 정책 ([java.util.concurrent.ThreadPoolExecutor.AbortPolicy]) 으로 caller 에
 * RejectedExecutionException — 컨트롤러가 503 으로 매핑할 수 있다.
 */
@Configuration
open class DlqBulkExecutorConfig {

    @Bean(name = ["dlqBulkWorkerExecutor"])
    open fun dlqBulkWorkerExecutor(): Executor {
        val exec = ThreadPoolTaskExecutor()
        exec.corePoolSize = 4
        exec.maxPoolSize = 4
        exec.queueCapacity = 32
        exec.setThreadNamePrefix("dlq-bulk-")
        exec.keepAliveSeconds = 60
        exec.setWaitForTasksToCompleteOnShutdown(true)
        exec.setAwaitTerminationSeconds(30)
        exec.initialize()
        return exec
    }
}

package com.example.market.application.dlq

/**
 * 단건 DLQ 메시지 조회/액션 시점에 대상이 없는 경우 — controller 가 404 로 매핑.
 */
class DlqMessageNotFoundException(messageId: String) :
    RuntimeException("DLQ message not found: $messageId")

/**
 * Bulk 작업 폴링 시 jobId 가 없는 경우 — controller 가 404 로 매핑.
 */
class DlqBulkJobNotFoundException(jobId: String) :
    RuntimeException("DLQ bulk job not found: $jobId")

/**
 * Bulk 요청에 `confirm=true` 인데 reason / dry-run 결과와 불일치하는 등 사전 검증 실패.
 */
class DlqBulkValidationException(message: String) : IllegalArgumentException(message)

/**
 * Admin rate limit 초과 — controller 가 429 + Retry-After 로 매핑.
 */
class DlqAdminRateLimitedException(val retryAfterSeconds: Long) :
    RuntimeException("admin DLQ scope rate limited, retry after $retryAfterSeconds seconds")

package com.example.market.application.port.out

import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqBulkJob
import com.example.market.application.dlq.DlqBulkStatus
import com.example.market.application.dlq.DlqSource
import java.time.Instant
import java.util.Optional

/**
 * Bulk replay/discard 작업의 진행 상태 저장소.
 *
 * 어댑터 구현은:
 *
 * - dev: in-memory ConcurrentHashMap — 인스턴스 재시작 시 작업이 lost 됨. 운영자 콘솔이
 *   "최근 작업" 만 보여줘도 충분한 dev 시나리오에 적합.
 * - prod: JPA 테이블 / Redis sorted set 등 영속 store — 인스턴스 재시작에도 추적 유지.
 *
 * 인터페이스는 두 환경 모두에서 동작하도록 최소한의 CRUD + 진행률 increment 만 제공.
 */
interface DlqBulkJobRepository {

    /** 신규 작업 생성 — QUEUED 상태로 저장. ID 는 호출자가 미리 부여. */
    fun create(
        id: String,
        action: DlqAction,
        source: DlqSource,
        requestedBy: String,
        totalCount: Long,
        now: Instant,
    ): DlqBulkJob

    fun find(id: String): Optional<DlqBulkJob>

    /** worker 가 RUNNING 으로 전이. */
    fun markRunning(id: String, now: Instant)

    /** 처리 중 increment — chunk 1건 처리 후 호출. firstError 는 한 번만 set. */
    fun recordProgress(id: String, success: Boolean, errorMessage: String?, now: Instant)

    /** SUCCEEDED / FAILED 로 전이. */
    fun markFinished(id: String, status: DlqBulkStatus, now: Instant)
}

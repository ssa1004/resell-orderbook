package com.example.market.adapter.out.persistence.compensation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.Objects

/**
 * SAGA 보상 트랜잭션 1건. `(operation, businessKey)` 합성 PK.
 *
 * 외부 호출 직전 INSERT (status=IN_PROGRESS), 결과를 받아 update.
 */
@Entity
@Table(name = "compensation_log")
@IdClass(CompensationLogJpaEntity.PK::class)
class CompensationLogJpaEntity(

    @Id
    @Column(name = "operation", length = 40, nullable = false)
    var operation: String? = null,

    @Id
    @Column(name = "business_key", length = 100, nullable = false)
    var businessKey: String? = null,

    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    var status: Status? = null,

    @Column(name = "response_code", length = 40)
    var responseCode: String? = null,

    @Column(name = "response_message", length = 500)
    var responseMessage: String? = null,

    @Column(name = "external_id", length = 200)
    var externalId: String? = null,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,
) {

    enum class Status { IN_PROGRESS, COMPLETED, FAILED }

    /** 합성 PK 클래스 — JPA `@IdClass` 가 요구. */
    class PK() : Serializable {
        var operation: String? = null
        var businessKey: String? = null

        constructor(operation: String, businessKey: String) : this() {
            this.operation = operation
            this.businessKey = businessKey
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PK) return false
            return operation == other.operation && businessKey == other.businessKey
        }

        override fun hashCode(): Int = Objects.hash(operation, businessKey)

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}

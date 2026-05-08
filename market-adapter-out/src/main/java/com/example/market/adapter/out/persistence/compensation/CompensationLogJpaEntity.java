package com.example.market.adapter.out.persistence.compensation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * SAGA 보상 트랜잭션 1건. {@code (operation, businessKey)} 합성 PK.
 *
 * <p>외부 호출 직전 INSERT (status=IN_PROGRESS), 결과를 받아 update.</p>
 */
@Entity
@Table(name = "compensation_log")
@IdClass(CompensationLogJpaEntity.PK.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CompensationLogJpaEntity {

    @Id
    @Column(name = "operation", length = 40, nullable = false)
    private String operation;

    @Id
    @Column(name = "business_key", length = 100, nullable = false)
    private String businessKey;

    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "response_code", length = 40)
    private String responseCode;

    @Column(name = "response_message", length = 500)
    private String responseMessage;

    @Column(name = "external_id", length = 200)
    private String externalId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public enum Status { IN_PROGRESS, COMPLETED, FAILED }

    /** 합성 PK 클래스 — JPA {@code @IdClass} 가 요구. */
    public static class PK implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        private String operation;
        private String businessKey;

        public PK() {}
        public PK(String operation, String businessKey) {
            this.operation = operation;
            this.businessKey = businessKey;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK other)) return false;
            return Objects.equals(operation, other.operation)
                    && Objects.equals(businessKey, other.businessKey);
        }
        @Override public int hashCode() { return Objects.hash(operation, businessKey); }
    }
}

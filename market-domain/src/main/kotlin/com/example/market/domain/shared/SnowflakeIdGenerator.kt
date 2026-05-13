package com.example.market.domain.shared

import java.time.Clock
import java.time.Instant

/**
 * 시간 순으로 정렬되는 64bit ID 생성기 (Snowflake 알고리즘 — Twitter 가 2010년에 공개).
 *
 * **비유**: 영수증 번호처럼 시간이 흐를수록 번호가 *항상 커진다*. UUID 는 무작위라
 * "어느 게 더 최근인가" 를 ID 만 보고는 알 수 없는데, snowflake ID 는 ID 를 long 으로
 * 비교만 해도 시간 순서가 나온다 — 차트 / 시계열 시스템에서 인덱스 정렬·페이지네이션 비용을
 * 크게 줄여주는 이유.
 *
 * **비트 배치 (총 64bit, signed long 의 부호 비트는 0 으로 고정)**:
 * ```
 *   |  1 bit  |   41 bit   |    10 bit    |   12 bit   |
 *   | 부호=0  | timestamp  | machine id   | sequence   |
 * ```
 * - timestamp = (현재 ms - epoch ms). 41bit → ~69년 표현 가능
 * - machine id = 인스턴스(=pod)별 고유 번호. 10bit → 최대 1024 인스턴스
 * - sequence = 같은 ms 안에서 1씩 증가. 12bit → 1ms 당 4096개 ID
 *
 * **왜 UUID 가 아니라 snowflake?**
 * - UUID 는 무작위 → 인덱스 page 가 무작위로 부풀어 (write amplification) DB 캐시 효율 떨어짐
 * - UUID 는 정렬해도 시간 순이 아니라 "WHERE id > cursor LIMIT N" 식의 cursor pagination 불가
 * - 16 byte vs 8 byte — 인덱스 절반 크기로 같은 메모리에 2배 들어감
 *
 * **Clock backward 방어**: 시계가 뒤로 가면 (NTP 동기화 등) 같은 ID 가 두 번 나올 수 있어
 * 마지막으로 사용한 timestamp 보다 작은 시각이 들어오면 "마지막 timestamp" 그대로 두고 sequence 만
 * 증가시킨다. 단, sequence 가 한도(=4096) 를 넘으면 다음 ms 까지 spin-wait. 시계가 한참 뒤로
 * 갔다면 (예: 분 단위) 그건 인프라 문제이므로 예외를 던진다 — silent corruption 보단 빠른 실패.
 *
 * **Thread-safe**: 모든 상태를 `@Synchronized` 메서드 안에서 갱신. nextId() 호출은 ns
 * 단위라 락 경합이 사실상 없다 (1ms 당 4096개를 한 thread 가 다 못 채움).
 *
 * 의존성 0 — 순수 도메인. Spring 으로 Bean 등록은 application 모듈 책임.
 */
class SnowflakeIdGenerator(
    @get:JvmName("machineId") val machineId: Long,
    private val clock: Clock,
) {

    private var lastTimestamp: Long = -1L
    private var sequence: Long = 0L

    init {
        require(machineId in 0..MAX_MACHINE_ID) {
            "machineId 는 0 ~ $MAX_MACHINE_ID 범위. 받은 값: $machineId"
        }
    }

    /**
     * 다음 ID 생성. 같은 ms 안에서는 sequence 가 증가 → 항상 lastId < nextId.
     *
     * @throws IllegalStateException 시계가 허용 한도 이상 뒤로 갔을 때
     */
    @Synchronized
    fun nextId(): Long {
        var now = clock.millis()

        if (now < lastTimestamp) {
            // 시계 역행 — 작은 차이 (NTP 보정) 라면 lastTimestamp 그대로 쓴다.
            // sequence 는 그대로 증가하므로 ID 단조 증가는 유지.
            val drift = lastTimestamp - now
            check(drift <= CLOCK_BACKWARD_TOLERANCE_MS) {
                "Clock 이 ${drift}ms 뒤로 갔습니다 — Snowflake 단조 증가 보장 불가. " +
                    "tolerance=${CLOCK_BACKWARD_TOLERANCE_MS}ms"
            }
            now = lastTimestamp
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) and SEQUENCE_MASK.toLong()
            if (sequence == 0L) {
                // 1ms 안에 4096개를 다 썼다 — 다음 ms 까지 대기.
                now = waitNextMillis(lastTimestamp)
            }
        } else {
            sequence = 0L
        }
        lastTimestamp = now

        val elapsed = now - EPOCH_MS
        check(elapsed >= 0) { "현재 시각이 EPOCH_MS 이전입니다. now=$now" }
        return (elapsed shl TIMESTAMP_SHIFT) or
            (machineId shl MACHINE_SHIFT) or
            sequence
    }

    private fun waitNextMillis(currentLast: Long): Long {
        var t = clock.millis()
        while (t <= currentLast) {
            // busy-wait: 한 ms 도 안 되는 대기라 Thread.sleep 보다 정확.
            t = clock.millis()
        }
        return t
    }

    companion object {
        /** 2026-01-01T00:00:00Z. 41bit timestamp 의 0 점 — 이후 ~69년 표현 가능 (~ 2095년). */
        const val EPOCH_MS: Long = 1_767_225_600_000L

        private const val MACHINE_BITS: Int = 10
        private const val SEQUENCE_BITS: Int = 12

        const val MAX_MACHINE_ID: Int = (1 shl MACHINE_BITS) - 1       // 1023
        private const val SEQUENCE_MASK: Int = (1 shl SEQUENCE_BITS) - 1 // 4095

        private const val MACHINE_SHIFT: Int = SEQUENCE_BITS                 // 12
        private const val TIMESTAMP_SHIFT: Int = SEQUENCE_BITS + MACHINE_BITS // 22

        /** 시계가 이 이상 뒤로 가면 silent corruption 위험이 더 크므로 예외. (10s) */
        private const val CLOCK_BACKWARD_TOLERANCE_MS: Long = 10_000L

        /** 디코딩 — 모니터링 / 로그 분석용. */
        @JvmStatic
        fun timestampOf(id: Long): Instant {
            val elapsed = id ushr TIMESTAMP_SHIFT
            return Instant.ofEpochMilli(EPOCH_MS + elapsed)
        }

        /** 디코딩 — 어떤 인스턴스가 발급했는지. */
        @JvmStatic
        fun machineIdOf(id: Long): Long = (id ushr MACHINE_SHIFT) and MAX_MACHINE_ID.toLong()

        /** 디코딩 — 같은 ms 안의 몇 번째 ID 인지. */
        @JvmStatic
        fun sequenceOf(id: Long): Long = id and SEQUENCE_MASK.toLong()
    }
}

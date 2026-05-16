package com.example.market.application.pagination

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * [Cursor] 의 내부 payload 와 외부 opaque token 사이를 변환.
 *
 * ### 왜 opaque 인가
 *
 * 클라이언트가 cursor 의 내용을 보지 못하게 하면 서버 쪽 cursor 의 필드 변경에 클라이언트
 * 호환성이 깨지지 않는다 (예: 단일 id → (time, id) 복합 키 전환). cursor pagination 을 제공하는
 * 공개 API 들이 보편적으로 채택하는 패턴.
 *
 * ### 구현
 *
 * - 형식: `Base64Url(payload)` — payload 는 cursor 종류별로 직렬화 규약을 정함.
 * - [TimeIdCursor]: `"v1|" + epochMillis + "|" + uuid`
 * - long 단일 ID (예: Snowflake): `"v1|" + longValue`
 *
 * 단순 string concat — Jackson 없이도 결정적이고 디버깅 시 base64 풀어서 바로 볼 수 있다.
 * 외부에 노출되지 않으므로 형식은 서버 쪽만 알면 충분.
 *
 * ### 버전 prefix ("v1")
 *
 * cursor 형식이 바뀔 때 (예: epochMillis → epochNanos) 클라이언트가 들고 있던 오래된 cursor
 * 도 한동안 받아주려면 prefix 로 버전을 식별해야 한다. `v1` 만 알면서 시작 — 미래에 v2
 * 추가 시 codec 이 분기.
 */
object CursorCodec {

    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    private const val VERSION = "v1"
    private const val DELIMITER = "|"

    // ── TimeIdCursor (Instant + UUID) ─────────────────────────────────

    @JvmStatic
    fun encode(payload: TimeIdCursor?): Cursor {
        if (payload == null) return Cursor.empty()
        val raw = VERSION + DELIMITER + payload.time.toEpochMilli() + DELIMITER + payload.id
        return Cursor.of(ENCODER.encodeToString(raw.toByteArray(StandardCharsets.UTF_8)))
    }

    @JvmStatic
    fun decodeTimeId(cursor: Cursor?): TimeIdCursor? {
        if (cursor == null || cursor.isEmpty()) return null
        val parts = decodeRaw(cursor)
        if (parts.size != 3 || VERSION != parts[0]) {
            throw InvalidCursorException("invalid cursor format")
        }
        return try {
            val time = Instant.ofEpochMilli(parts[1].toLong())
            val id = UUID.fromString(parts[2])
            TimeIdCursor(time, id)
        } catch (e: DateTimeParseException) {
            throw InvalidCursorException("invalid cursor payload", e)
        } catch (e: IllegalArgumentException) {
            // NumberFormatException 도 IllegalArgumentException 의 자손이라 한 묶음으로 잡는다.
            throw InvalidCursorException("invalid cursor payload", e)
        }
    }

    // ── long 단일 ID (Snowflake 등) ───────────────────────────────────

    @JvmStatic
    fun encodeLong(id: Long): Cursor {
        val raw = VERSION + DELIMITER + id
        return Cursor.of(ENCODER.encodeToString(raw.toByteArray(StandardCharsets.UTF_8)))
    }

    @JvmStatic
    fun decodeLong(cursor: Cursor?): Long {
        if (cursor == null || cursor.isEmpty()) return 0L
        val parts = decodeRaw(cursor)
        if (parts.size != 2 || VERSION != parts[0]) {
            throw InvalidCursorException("invalid cursor format")
        }
        return try {
            parts[1].toLong()
        } catch (e: NumberFormatException) {
            throw InvalidCursorException("invalid cursor payload", e)
        }
    }

    // ── 공통 ─────────────────────────────────────────────────────────

    private fun decodeRaw(cursor: Cursor): Array<String> {
        try {
            val bytes = DECODER.decode(cursor.token)
            val raw = String(bytes, StandardCharsets.UTF_8)
            // Kotlin split(limit=0) = unlimited, trailing empty 보존 (Java split(-1) 과 같은 의미).
            return raw.split(DELIMITER).toTypedArray()
        } catch (e: IllegalArgumentException) {
            throw InvalidCursorException("cursor is not base64", e)
        }
    }

    /** 클라이언트가 깨진 cursor 를 보냈을 때 — 컨트롤러가 400 으로 매핑. */
    open class InvalidCursorException : IllegalArgumentException {
        constructor(msg: String) : super(msg)
        constructor(msg: String, cause: Throwable) : super(msg, cause)
    }
}

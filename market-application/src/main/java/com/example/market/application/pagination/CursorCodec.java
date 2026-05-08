package com.example.market.application.pagination;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * {@link Cursor} 의 내부 payload 와 외부 opaque token 사이를 변환.
 *
 * <h3>왜 opaque 인가</h3>
 *
 * <p>클라이언트가 cursor 의 내용을 *보지 못하게* 하면 서버 쪽 cursor 의 필드 변경에 클라이언트
 * 호환성이 깨지지 않는다 (예: 단일 id → (time, id) 복합 키 전환). GitHub / Slack / Twitter
 * API 모두 같은 이유로 cursor 를 base64 인코딩.</p>
 *
 * <h3>구현</h3>
 *
 * <ul>
 *   <li>형식: {@code Base64Url(payload)} — payload 는 cursor 종류별로 직렬화 규약을 정함.</li>
 *   <li>{@link TimeIdCursor}: {@code "v1|" + epochMillis + "|" + uuid}</li>
 *   <li>long 단일 ID (예: Snowflake): {@code "v1|" + longValue}</li>
 * </ul>
 *
 * <p>단순 string concat — Jackson 없이도 결정적이고 디버깅 시 base64 풀어서 바로 볼 수 있다.
 * 외부에 노출되지 않으므로 형식이 *우리* 만 알면 충분.</p>
 *
 * <h3>버전 prefix ("v1")</h3>
 *
 * <p>cursor 형식이 바뀔 때 (예: epochMillis → epochNanos) 클라이언트가 들고 있던 *오래된* cursor
 * 도 한동안 받아주려면 prefix 로 버전을 식별해야 한다. {@code v1} 만 알면서 시작 — 미래에 v2
 * 추가 시 codec 이 분기.</p>
 */
public final class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String VERSION = "v1";
    private static final String DELIMITER = "|";

    private CursorCodec() {}

    // ── TimeIdCursor (Instant + UUID) ─────────────────────────────────

    public static Cursor encode(TimeIdCursor payload) {
        if (payload == null) return Cursor.empty();
        String raw = VERSION + DELIMITER + payload.time().toEpochMilli() + DELIMITER + payload.id();
        return Cursor.of(ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }

    public static TimeIdCursor decodeTimeId(Cursor cursor) {
        if (cursor == null || cursor.isEmpty()) return null;
        String[] parts = decodeRaw(cursor);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new InvalidCursorException("invalid cursor format");
        }
        try {
            Instant time = Instant.ofEpochMilli(Long.parseLong(parts[1]));
            UUID id = UUID.fromString(parts[2]);
            return new TimeIdCursor(time, id);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            // NumberFormatException 도 IllegalArgumentException 의 자손이라 한 묶음으로 잡는다.
            throw new InvalidCursorException("invalid cursor payload", e);
        }
    }

    // ── long 단일 ID (Snowflake 등) ───────────────────────────────────

    public static Cursor encodeLong(long id) {
        String raw = VERSION + DELIMITER + id;
        return Cursor.of(ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }

    public static long decodeLong(Cursor cursor) {
        if (cursor == null || cursor.isEmpty()) return 0L;
        String[] parts = decodeRaw(cursor);
        if (parts.length != 2 || !VERSION.equals(parts[0])) {
            throw new InvalidCursorException("invalid cursor format");
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("invalid cursor payload", e);
        }
    }

    // ── 공통 ─────────────────────────────────────────────────────────

    private static String[] decodeRaw(Cursor cursor) {
        try {
            byte[] bytes = DECODER.decode(cursor.token());
            String raw = new String(bytes, StandardCharsets.UTF_8);
            return raw.split("\\" + DELIMITER, -1);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("cursor is not base64", e);
        }
    }

    /** 클라이언트가 깨진 cursor 를 보냈을 때 — 컨트롤러가 400 으로 매핑. */
    public static class InvalidCursorException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public InvalidCursorException(String msg) { super(msg); }
        public InvalidCursorException(String msg, Throwable cause) { super(msg, cause); }
    }
}

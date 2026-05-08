package com.example.market.application.pagination;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CursorCodec round-trip + 깨진 cursor 거절.
 */
class CursorCodecTest {

    @Test
    void roundTripsTimeIdCursor() {
        TimeIdCursor original = new TimeIdCursor(
                Instant.parse("2026-05-08T12:34:56.789Z"),
                UUID.fromString("11111111-2222-3333-4444-555555555555"));

        Cursor encoded = CursorCodec.encode(original);
        TimeIdCursor decoded = CursorCodec.decodeTimeId(encoded);

        assertThat(decoded.time()).isEqualTo(original.time());
        assertThat(decoded.id()).isEqualTo(original.id());
    }

    @Test
    void roundTripsLongCursor() {
        long original = 1234567890123456789L;
        Cursor encoded = CursorCodec.encodeLong(original);
        assertThat(CursorCodec.decodeLong(encoded)).isEqualTo(original);
    }

    @Test
    void emptyCursorDecodesAsZeroLong() {
        assertThat(CursorCodec.decodeLong(Cursor.empty())).isZero();
    }

    @Test
    void emptyCursorDecodesAsNullTimeId() {
        assertThat(CursorCodec.decodeTimeId(Cursor.empty())).isNull();
    }

    @Test
    void rejectsCursorThatIsNotBase64() {
        Cursor garbage = Cursor.of("definitely!not!base64!@#$%");
        assertThatThrownBy(() -> CursorCodec.decodeTimeId(garbage))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
    }

    @Test
    void rejectsCursorWithWrongVersion() {
        // 직접 v2 prefix 의 cursor 를 만들어서 — 우리는 v1 만 알므로 reject.
        Cursor v2 = Cursor.of(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v2|123|abc".getBytes()));
        assertThatThrownBy(() -> CursorCodec.decodeTimeId(v2))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
    }

    @Test
    void rejectsCursorWithBrokenPayload() {
        Cursor broken = Cursor.of(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1|notALong|notAUuid".getBytes()));
        assertThatThrownBy(() -> CursorCodec.decodeTimeId(broken))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
    }

    @Test
    void encodedCursorIsUrlSafe() {
        TimeIdCursor c = new TimeIdCursor(Instant.now(), UUID.randomUUID());
        Cursor encoded = CursorCodec.encode(c);
        // base64 url-safe — '+' / '/' / '=' 가 없어야 한다 (URL 에 그대로 넣을 수 있음).
        assertThat(encoded.token()).doesNotContain("+", "/", "=");
    }
}

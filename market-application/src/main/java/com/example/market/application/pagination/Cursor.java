package com.example.market.application.pagination;

import java.util.Objects;

/**
 * 클라이언트가 다음 페이지를 요청할 때 그대로 다시 보내는 *opaque token*.
 *
 * <p>내용은 서버만 해석한다 (Base64 + JSON — {@link CursorCodec} 참고). 클라이언트가 의미를
 * 파헤쳐서 가공하지 않게 한 단계 인코딩으로 감싸면, 서버 쪽 cursor 의 필드가 변경되어도
 * 클라이언트 호환성이 유지된다 (= GitHub / Slack API 의 표준 패턴).</p>
 *
 * <p>{@link #empty()} 는 첫 페이지 요청 — 클라이언트가 아무 cursor 도 안 보냈을 때 사용.</p>
 */
public final class Cursor {

    /** Slack / GitHub API 와 같은 형태 — 빈 cursor 는 *첫 페이지부터*. */
    public static final Cursor EMPTY = new Cursor("");

    private final String token;

    private Cursor(String token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    public static Cursor of(String token) {
        if (token == null || token.isEmpty()) return EMPTY;
        return new Cursor(token);
    }

    public static Cursor empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return token.isEmpty();
    }

    public String token() {
        return token;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cursor c && Objects.equals(token, c.token);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(token);
    }

    @Override
    public String toString() {
        return "Cursor{" + (token.isEmpty() ? "<empty>" : "<token>") + "}";
    }
}

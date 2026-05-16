package com.example.market.application.pagination

/**
 * 클라이언트가 다음 페이지를 요청할 때 그대로 다시 보내는 opaque token.
 *
 * 내용은 서버만 해석한다 (Base64 + JSON — [CursorCodec] 참고). 클라이언트가 의미를
 * 파헤쳐서 가공하지 않게 한 단계 인코딩으로 감싸면, 서버 쪽 cursor 의 필드가 변경되어도
 * 클라이언트 호환성이 유지된다 (cursor 를 opaque token 으로 다루는 표준 패턴).
 *
 * [empty] 는 첫 페이지 요청 — 클라이언트가 아무 cursor 도 안 보냈을 때 사용.
 */
class Cursor private constructor(@get:JvmName("token") val token: String) {

    fun isEmpty(): Boolean = token.isEmpty()

    override fun equals(other: Any?): Boolean = other is Cursor && token == other.token

    override fun hashCode(): Int = token.hashCode()

    override fun toString(): String = "Cursor{" + (if (token.isEmpty()) "<empty>" else "<token>") + "}"

    companion object {
        /** 빈 cursor — 클라이언트가 cursor 없이 호출하면 첫 페이지부터 반환. */
        @JvmField
        val EMPTY: Cursor = Cursor("")

        @JvmStatic
        fun of(token: String?): Cursor {
            if (token.isNullOrEmpty()) return EMPTY
            return Cursor(token)
        }

        @JvmStatic
        fun empty(): Cursor = EMPTY
    }
}

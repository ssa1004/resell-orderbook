package com.example.market.application.context;

import com.example.market.domain.shared.UserId;

import java.util.Set;

/**
 * 인증된 호출자의 컨텍스트. Adapter-in 이 JWT 에서 추출해서 service 메서드에 전달.
 *
 * <p>도메인 레이어에는 안 들어감 (도메인은 UserId 만 알면 충분). Application 의 권한 검사,
 * 로깅, idempotency 키 prefix 등에 사용.</p>
 */
public record Caller(UserId userId, Set<String> roles) {

    public Caller {
        java.util.Objects.requireNonNull(userId, "userId");
        java.util.Objects.requireNonNull(roles, "roles");
        roles = Set.copyOf(roles);
    }

    public static Caller of(String userId, String... roles) {
        return new Caller(UserId.of(userId), Set.of(roles));
    }

    public static Caller anonymous(String fallbackId) {
        return new Caller(UserId.of(fallbackId), Set.of());
    }

    public boolean hasRole(String role) { return roles.contains(role); }
}

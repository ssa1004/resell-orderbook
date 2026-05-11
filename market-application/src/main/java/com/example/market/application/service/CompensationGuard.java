package com.example.market.application.service;

import com.example.market.application.port.out.CompensationLogStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;

/**
 * 외부 호출이 정확히 한 번만 일어나도록 보장하는 helper. SAGA 의 보상 트랜잭션 (refund /
 * payout / 재시도) 에 적용.
 *
 * <h3>왜 필요한가</h3>
 *
 * <p>Refund/Settle 같은 외부 호출형 보상 트랜잭션은 메시지 컨슈머의 at-least-once 특성 + 외부
 * 호출의 응답 유실 가능성으로 인해 실수로 두 번 호출될 수 있다. 결제/송금 도메인의 표준 처방은
 * "외부 호출 시 idempotencyKey 를 함께 보내고, 결과는 자체 DB 에도 함께 저장해 캐시" 형태
 * (Stripe Idempotent Requests 가 대표적인 공개 사례).</p>
 *
 * <h3>흐름</h3>
 *
 * <pre>
 *   runOnce(op, key, action):
 *     기존 = store.find(op, key)
 *     기존.isCompleted  → 캐시 결과 그대로 반환 (외부 호출 안 함)
 *     기존.isInProgress → DuplicateInProgressException (운영자가 확인할 수 있게 throw)
 *     기존.isFailed     → 새로 시작 (FAILED 위에 같은 키로 begin 은 PK 충돌이라 deleteAndBegin)
 *     없음             → store.begin → action 실행 → complete / fail 기록
 * </pre>
 *
 * <h3>begin 시 PK 충돌</h3>
 *
 * <p>두 thread 가 동시에 진입하면 begin 두 번째가 {@link CompensationLogStore.DuplicateBeginException}
 * 으로 떨어진다. 이 시점에 다시 {@link CompensationLogStore#find} → IN_PROGRESS 가 보이면
 * 다른 thread 가 처리 중이므로 {@link DuplicateInProgressException} 으로 호출자에게 신호.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CompensationGuard {

    private final CompensationLogStore store;
    private final Clock clock;

    /**
     * 다른 thread / 메시지가 같은 보상을 지금 진행 중. 일반적으로 메시지 컨슈머가 retry 하면
     * 자연스럽게 해소되므로 호출자는 그대로 throw 시켜도 무방.
     */
    public static class DuplicateInProgressException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DuplicateInProgressException(String op, String key) {
            super("compensation in progress by another worker: op=" + op + " key=" + key);
        }
    }

    /**
     * idempotent 하게 보상 1건을 실행.
     *
     * <p>action 의 반환값은 {@link Outcome} — 성공/실패 + 외부 식별자 + 코드/메시지. 이 정보가
     * compensation_log row 에 박혀 다음 재시도 때 캐시로 재사용됨.</p>
     *
     * @param operation   보상 종류. "REFUND", "SETTLE_PAYOUT" 등 — operationKey 는 같지만 operation
     *                    이 다르면 각자의 row 가 별도로 관리됨.
     * @param businessKey 일반적으로 tradeId. 같은 op + key 의 두 번째 호출은 캐시 결과 반환.
     * @param action      실제 외부 호출. 입력은 캐시된 결과 ({@link Outcome}, {@code null} = 신규).
     */
    public <T> Outcome<T> runOnce(String operation,
                                  String businessKey,
                                  Function<Outcome<T>, Outcome<T>> action) {
        var existing = store.find(operation, businessKey);
        if (existing.isPresent()) {
            var entry = existing.get();
            if (entry.isCompleted()) {
                log.info("compensation 캐시 hit op={} key={} externalId={}",
                        operation, businessKey, entry.externalId());
                @SuppressWarnings("unchecked")
                Outcome<T> cached = (Outcome<T>) Outcome.completed(entry.externalId(),
                        entry.responseCode(), entry.responseMessage(), null);
                return cached;
            }
            if (entry.isInProgress()) {
                throw new DuplicateInProgressException(operation, businessKey);
            }
            // FAILED — 재시도 허용. 같은 PK 의 row 를 update 하는 begin 메서드 의미는 아니므로,
            // store 가 PK 충돌을 받아 fall-through 하지 않게 별도 entry-point 가 필요. 본 라운드는
            // FAILED row 를 그대로 두고, 호출자가 새 businessKey (예: "RETRY:" prefix) 로 진행하는
            // 운영 정책을 권장 — 이는 RetryRefundService 의 기존 흐름과 일치.
            log.warn("compensation 이전 시도가 FAILED 로 남음 — 새 키로 재시도 권장 op={} key={}",
                    operation, businessKey);
            // 이 케이스는 호출자에게 다시 던지지 않고 그대로 캐시 결과 (FAILED 결과) 를 반환.
            // 호출자가 응답 코드를 보고 분기.
            @SuppressWarnings("unchecked")
            Outcome<T> cached = (Outcome<T>) Outcome.failed(entry.responseCode(),
                    entry.responseMessage(), null);
            return cached;
        }

        Instant now = clock.instant();
        try {
            store.begin(operation, businessKey, now);
        } catch (CompensationLogStore.DuplicateBeginException e) {
            // 다른 thread 가 동시에 begin 했다 — 다시 find 해서 분기.
            var raced = store.find(operation, businessKey);
            if (raced.isPresent() && raced.get().isInProgress()) {
                throw new DuplicateInProgressException(operation, businessKey);
            }
            // 가까스로 완료된 경우 — 캐시 결과 반환 (재귀 1회).
            return runOnce(operation, businessKey, action);
        }

        Outcome<T> outcome;
        try {
            outcome = action.apply(null);
        } catch (RuntimeException e) {
            // action 안에서 예외 — fail 로 기록 후 그대로 throw (호출자가 도메인 의미로 처리).
            // store.fail 자체가 또 throw 할 수 있다 (REQUIRES_NEW 트랜잭션이라 DB 장애 시).
            // 그 경우 원래 예외(e) 가 그대로 호출자에게 전달되도록 addSuppressed 로 묶고,
            // fail 기록 실패는 별도로 로그만 남긴다. 그렇지 않으면 도메인 예외가 잠식되어
            // 호출자가 잘못된 분기를 탄다.
            try {
                store.fail(operation, businessKey,
                        "EXCEPTION", e.getClass().getSimpleName(), clock.instant());
            } catch (RuntimeException failEx) {
                e.addSuppressed(failEx);
                log.warn("compensation_log fail 기록 실패 op={} key={} (원인 예외는 그대로 전파)",
                        operation, businessKey, failEx);
            }
            throw e;
        }

        if (outcome.completed()) {
            store.complete(operation, businessKey,
                    outcome.responseCode(), outcome.responseMessage(),
                    outcome.externalId(), clock.instant());
        } else {
            store.fail(operation, businessKey,
                    outcome.responseCode(), outcome.responseMessage(), clock.instant());
        }
        return outcome;
    }

    /**
     * 보상 1건의 결과. completed = true 면 외부 호출 성공으로 간주되어 캐시되며, 같은 키의 다음
     * 호출은 외부 호출 없이 이 값으로 응답.
     *
     * @param result        action 의 추가 결과 (도메인 객체). 캐시 hit 시점에는 null — 호출자가
     *                      외부 식별자 ({@link #externalId()}) 만 있으면 도메인 결과를 재구성할 수
     *                      있는 책임을 진다.
     */
    public record Outcome<T>(
            boolean completed,
            String responseCode,
            String responseMessage,
            String externalId,
            T result
    ) {
        public static <T> Outcome<T> completed(String externalId, String code, String message, T result) {
            return new Outcome<>(true, code, message, externalId, result);
        }

        public static <T> Outcome<T> failed(String code, String message, T result) {
            return new Outcome<>(false, code, message, null, result);
        }
    }
}

package com.example.market.domain.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * OHLC 캔들 시간 단위.
 *
 * <p>차트의 *해상도* 를 결정. 같은 5월 데이터라도:
 * <ul>
 *   <li>{@code ONE_MIN} → 분 단위 1,440개 점 (24h) — *오늘 가격 변동* 차트</li>
 *   <li>{@code ONE_HOUR} → 시간 단위 24개 점 — *지난 며칠* 트렌드</li>
 *   <li>{@code ONE_DAY} → 일 단위 30개 점 — *이번 달* 흐름</li>
 * </ul>
 *
 * <p>Frontend 차트 라이브러리 (Highcharts / TradingView) 가 받아서 캔들스틱 그릴 수 있는
 * 표준 형태. NASDAQ / Binance / Upbit 등 모든 거래소가 동일 패턴 사용.</p>
 */
public enum OhlcPeriod {

    ONE_MIN(Duration.ofMinutes(1)),
    FIVE_MIN(Duration.ofMinutes(5)),
    ONE_HOUR(Duration.ofHours(1)),
    ONE_DAY(Duration.ofDays(1));

    private final Duration duration;

    OhlcPeriod(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    /**
     * 주어진 시각이 속한 bucket 의 *시작 시각* (UTC). bucket alignment 의 기준.
     *
     * <p>예: ONE_HOUR + {@code 2026-05-04T14:23:45Z} → {@code 2026-05-04T14:00:00Z}</p>
     */
    public Instant bucketStart(Instant t) {
        return switch (this) {
            case ONE_MIN  -> t.truncatedTo(ChronoUnit.MINUTES);
            case FIVE_MIN -> truncate5Minutes(t);
            case ONE_HOUR -> t.truncatedTo(ChronoUnit.HOURS);
            // ChronoUnit.DAYS 는 UTC 자정 기준 — 명시.
            case ONE_DAY  -> t.atZone(ZoneOffset.UTC).toLocalDate()
                              .atStartOfDay(ZoneOffset.UTC).toInstant();
        };
    }

    /** {@link #bucketStart} + duration. */
    public Instant bucketEnd(Instant t) {
        return bucketStart(t).plus(duration);
    }

    private static Instant truncate5Minutes(Instant t) {
        Instant minute = t.truncatedTo(ChronoUnit.MINUTES);
        long minutesIntoHour = ChronoUnit.MINUTES.between(
                minute.truncatedTo(ChronoUnit.HOURS), minute);
        long alignedMinutes = (minutesIntoHour / 5) * 5;
        return minute.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofMinutes(alignedMinutes));
    }
}

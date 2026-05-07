/**
 * 시세 / 시장 통계 (read model).
 *
 * <p>거래 (Trade) 가 발생할 때마다 {@link com.example.market.domain.marketdata.PriceTick} 1건이 append-only 로 쌓이고,
 * 운영 화면 / 사용자 차트는 그 시계열을 다양한 형태 (현재 통계 / raw chart / 캔들스틱) 로 조회한다.
 * 주식 시장의 *체결 데이터 → 차트 / 통계 화면* 과 같은 패턴.</p>
 */
@org.springframework.modulith.NamedInterface("marketdata")
package com.example.market.domain.marketdata;

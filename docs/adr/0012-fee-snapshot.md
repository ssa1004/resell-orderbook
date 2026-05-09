# ADR-0012: FeeSnapshot — 거래 시점의 수수료를 거래 데이터에 박아두기

## 상태
적용

## 배경
한정판 리셀 마켓의 수수료는 다음과 같이 구성된다.

- 구매자 수수료 (3.5%) + 검수비 (3,000원) + 배송비 (3,000원) — 구매자 결제액에 포함.
- 판매자 수수료 (3%) + 결제대행 수수료 (1,000원) — 판매자 정산액에서 차감.

이 정책은 시점에 따라 바뀐다 — 운영팀이 마케팅 등의 이유로 조정한다.

문제: 어제 매칭된 거래가 오늘 정산되는데, 어제 정책으로 계산해야 일관성이 맞다.

## 결정
**`Trade.match()` 시점에 `FeePolicy.snapshotFor(price)` 가 그 순간의 수수료 계산서를 그대로
보관한 `FeeSnapshot` 객체를 만들고, Trade 애그리거트가 그것을 같이 들고 다닌다.**

```java
public record FeeSnapshot(
    Money tradeAmount,
    BigDecimal sellerCommissionRate, BigDecimal buyerCommissionRate,
    Money inspectionFee, Money shippingFee, Money fixedProcessingFee,
    Money sellerCommission, Money buyerCommission,    // 계산된 수수료
    Money buyerCharge, Money sellerNet                 // 합계
) {}
```

- `Payout.schedule(snapshot)` 이 snapshot 의 sellerNet 으로 정산.
- `Refund` 환불 금액 = snapshot 의 buyerCharge (검수비/배송비 포함 전액).
- DB 컬럼으로 펼쳐서 저장한다 (JSON 이 아닌 명시적 컬럼) — 정산 query 와 통계에 직접 사용할 수 있다.

## 장단점
- 수수료 정책이 바뀌어도 과거 거래의 정산 금액은 변하지 않는다.
- Payout/Refund 가 정책을 다시 조회할 필요 없이 같은 트랜잭션 내 일관성이 보장된다.
- Trade 테이블 컬럼이 10개 정도 늘어난다 — 정산/통계 query 에 직접 쓸 수 있어 보상된다.
- FeePolicy 자체가 변경되더라도 과거 Trade 의 snapshot 은 그대로 — 의도된 동작이다.

## 검증
`PayoutTest` 가 표준 정책(판매자 수수료 3% / 구매자 수수료 3.5% / 검수비 3,000 / 배송비 3,000 / 결제대행 1,000) 으로 검증한다.

- 150,000원 거래 → 구매자 결제 161,250원, 판매자 정산 144,500원, 플랫폼 수익 14,750원.

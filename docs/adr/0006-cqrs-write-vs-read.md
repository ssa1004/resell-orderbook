# ADR-0006: CQRS — 쓰기는 JPA, 읽기는 필요한 곳만 분리

## 상태
적용

## 배경
- 쓰기는 애그리거트 단위 (Listing/Trade/Payout) — 불변식과 트랜잭션이 중요하다.
- 읽기는 호가창 (top N), 가격 차트 (시계열), 거래 내역 (페이지) 등 — 쓰기와 모양이 많이 다르다.

JPA 로 읽기까지 하면 entity graph 로 N+1 이나 메모리 낭비가 생긴다.

## 결정
- **쓰기**: JPA. 애그리거트가 작아 N+1 위험이 크지 않다.
- **호가창 읽기**: 지금은 Spring Data JPA query 로 충분. 가격대별 집계(top 10 ASK + BID) 는 application 계층에서 `TreeMap` 으로 묶는다.
- **가격 차트 (시계열)**: 도입 시 Postgres TimescaleDB 또는 ClickHouse 같은 별도 read store. 현재는 미구현.

## 다시 검토할 시점
- 호가창 read latency 가 P99 100ms 를 넘기 시작할 때 → JOOQ + projection 도입 검토
- 가격 차트 도입 시점 → 별도 read store 와 Trade 이벤트 컨슈머가 채우는 구조

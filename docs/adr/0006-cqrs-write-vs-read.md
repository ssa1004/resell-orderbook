# ADR-0006: CQRS — 쓰기는 JPA, 읽기는 필요한 곳만 분리

## 상태
적용

## 배경
CQRS (Command Query Responsibility Segregation, 데이터를 바꾸는 명령과 조회를 다른 모델로
분리하는 패턴) 도입 여부와 범위를 정한다.

- 쓰기는 애그리거트 단위 (Listing/Trade/Payout) — 불변식 (항상 지켜져야 하는 규칙) 과
  트랜잭션이 중요하다.
- 읽기는 호가창 (상위 N개), 가격 차트 (시계열), 거래 내역 (페이지) 등 — 쓰기와 모양이 많이
  다르다.

JPA 로 읽기까지 하면 entity graph (연관 엔티티를 같이 가져오는 그래프) 로 N+1 (한 번 조회
했더니 연관 데이터마다 추가 쿼리가 N번 더 나가는 안티패턴) 이나 메모리 낭비가 생긴다.

## 결정
- **쓰기**: JPA. 애그리거트가 작아 N+1 위험이 크지 않다.
- **호가창 읽기**: 지금은 Spring Data JPA query 로 충분. 가격대별 집계 (상위 10개 ASK +
  BID) 는 application 계층에서 `TreeMap` (정렬된 키-값 맵) 으로 묶는다.
- **가격 차트 (시계열)**: 도입 시 Postgres TimescaleDB (시계열 데이터를 위한 PG 확장) 또는
  ClickHouse 같은 별도 읽기 전용 저장소. 현재는 미구현.

## 다시 검토할 시점
- 호가창 read latency 가 P99 (응답 시간 분포의 99 퍼센타일 = 100건 중 가장 느린 1건) 100ms
  를 넘기 시작할 때 → JOOQ + projection (필요한 컬럼만 매핑하는 조회 객체) 도입 검토
- 가격 차트 도입 시점 → 별도 read store 와 Trade 이벤트 컨슈머가 채우는 구조

# Architecture Decision Records

각 결정의 *배경 → 결정 → 장단점* 을 짧게 기록 ([Michael Nygard 형식](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) 참고).

| # | 제목 | 상태 |
|---|---|---|
| [0001](0001-modular-monolith-with-spring-modulith.md) | Spring Modulith 기반 모듈러 모놀리스 | 적용 |
| [0002](0002-hexagonal-architecture.md) | 헥사고날 아키텍처 (port/adapter) | 적용 |
| [0003](0003-ddd-aggregate-boundaries.md) | Listing/Bid/Trade 를 각각 별도 애그리거트로 | 적용 |
| [0004](0004-trade-saga-choreography.md) | 거래 Saga — 코레오그래피 (Outbox + Kafka) | 적용 |
| [0005](0005-concurrency-for-update-skip-locked.md) | 매칭 동시성 — Postgres advisory lock + `FOR UPDATE SKIP LOCKED` | 적용 |
| [0006](0006-cqrs-write-vs-read.md) | CQRS — 쓰기는 JPA, 읽기는 필요한 곳만 분리 | 적용 |
| [0007](0007-outbox-pattern.md) | Outbox 패턴 — 이벤트 발행을 DB 트랜잭션과 묶기 | 적용 |
| [0008](0008-idempotency-redis-nx.md) | 멱등성 처리 이원화 — 사용자 요청은 Redis NX, 시스템 트리거는 상태 체크 | 적용 |
| [0009](0009-resilience4j-pg.md) | PG 호출에 Resilience4j Circuit Breaker 적용 | 적용 |
| [0010](0010-spring-batch.md) | Spring Batch — 만료 호가 정리 + 결제 TTL 초과 거래 자동 취소 | 적용 |
| [0011](0011-websocket-orderbook-stream.md) | 실시간 호가창은 WebSocket 으로 push | ADR-0014로 보완 |
| [0012](0012-fee-snapshot.md) | FeeSnapshot — 거래 시점의 수수료를 거래 데이터에 박아두기 | 적용 |
| [0013](0013-batch-scheduling-shedlock.md) | Batch Job 스케줄링 — `@Scheduled` + ShedLock | 적용 |
| [0014](0014-stomp-over-raw-websocket.md) | STOMP 도입 — raw WebSocket 보완 | 적용 |
| [0015](0015-market-data-time-series.md) | Market Data — 시세 시계열 + 통계 read API | 적용 |

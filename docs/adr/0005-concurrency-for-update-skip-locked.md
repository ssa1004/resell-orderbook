# ADR-0005: 매칭 동시성 — Postgres advisory lock + FOR UPDATE SKIP LOCKED

## 상태
적용

## 배경
매칭은 *원자적인 read-modify-write* 흐름이다.

1. 같은 SKU 의 best 호가(가장 높은 BID 또는 가장 낮은 ASK) 를 select.
2. 가격 비교.
3. 매칭이면 두 호가의 status 를 MATCHED 로 변경 + Trade INSERT.

여러 트랜잭션이 동시에 같은 SKU 에 도달하면, *같은 best 호가* 를 두 번 매칭할 수 있다 → 데이터 일관성이 깨진다.

## 결정
하이브리드로 간다.

1. **`pg_advisory_xact_lock(hash(sku_id))`** — SKU 단위로 직렬화. 트랜잭션 끝까지 자동 해제. 데드락이 발생할 수 없는 구조.
2. **`SELECT ... FOR UPDATE SKIP LOCKED`** — best 호가 한 행만 락. SKIP LOCKED 라 다른 SKU 의 매칭과는 분리된다.

분산 락(Redisson) 은 지금은 도입하지 않는다. DB 트랜잭션과 자연스럽게 묶이는 advisory lock 이 더 단순하다.

## 장단점
- 같은 SKU 의 매칭이 직렬 처리돼 정합성이 보장되고, 다른 SKU 는 병렬이라 처리량은 보존된다.
- DB 외부 의존이 없다 — 한 트랜잭션 안에서 락 획득과 해제가 모두 끝난다.
- Postgres 전용이라 H2 dev 환경에서는 `market.advisory-lock.enabled=false` 로 끈다 (단일 인스턴스라 race 없음).
- hot SKU 매칭 빈도가 초당 100건 넘어가는 시점에는 DB 락 경합이 생길 수 있다 — 그 때 Redisson 분산락 + 호가 캐싱 도입을 검토한다.

## 다른 선택지를 안 쓴 이유
- **Redisson 분산락만 쓰기**: DB 트랜잭션과 별개라 락 release 시점이나 leak 을 신경써야 한다. 더 복잡하다.
- **비관적 락만 (advisory 없이)**: 두 SKU 의 호가를 락 잡는 순서가 트랜잭션마다 달라 데드락이 가능하다. 코드 패턴에 의존하게 되고 깨지기 쉽다.

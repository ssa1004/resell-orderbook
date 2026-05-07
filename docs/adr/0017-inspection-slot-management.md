# ADR-0017: Inspection Slot Management — capacity 기반 검수 예약

## 상태
적용

## 배경

한정판 sneaker 리셀에서 정품 검수 단계가 필요. 셀러가 매물을 검수센터로 보내면 검수원이
가품 / 손상 여부를 판정. 이 검수가 시간이 걸리고 (한 켤레당 ~30분), 검수원 수에 한계가 있어
*예약 시스템* 이 필요. 매일 검수 capacity 가 한정되어 있는데 거래 트래픽이 몰리면 *언제 매물을
가져올 수 있는지* 사용자가 미리 알아야 셀러가 발송 일정 잡을 수 있음.

기존 {@link com.example.market.domain.inspection.InspectionRequest} 는 *검수 작업 자체* (PENDING →
DECIDED) 만 다룸. 이번 ADR 은 그 *전 단계* — *언제 어디서* 검수받을지 예약하는 시스템.

## 결정

### 도메인

```
InspectionCenter (aggregate)
  - parallelCapacity : int    동시 검수 가능 인원 (= 검수원 수)
  - slotDuration     : Duration (보통 1h)
  - bookingLeadTime  : Duration (예: 30분 — 너무 임박한 예약 거절)

InspectionAppointment (aggregate)
  - tradeId, centerId, slotStart, slotEnd, status
  - status: RESERVED → ARRIVED → COMPLETED / REJECTED
            └ CANCELLED / NO_SHOW
  - isOccupyingCapacity(): RESERVED + ARRIVED 만 자리 점유

SlotAvailability (read VO)
  - centerId, slotStart, totalCapacity, bookedCount, bookable
```

### Slot 모델 — 미리 만들어두지 않음

흔한 대안: 매일 배치가 향후 30일치 슬롯 row 를 미리 만들어둠 (예: 9~18시 매시간 = 하루 9개
× 30일 × 센터 N개 = 수천 행). 거부.

대신 슬롯은 *암묵적* 으로 존재한다 — `(centerId, slotStart)` 튜플 자체가 슬롯의 키. 예약이
들어올 때마다 도메인 메서드 `slotStartFor(desiredTime)` 가 시간을 슬롯 시작으로 정렬
(14:23 → 14:00). 슬롯 row 가 없어도 capacity (수용 인원) 검사는 *해당 (센터, 슬롯시작) 의
active appointment (현재 자리를 차지하고 있는 예약) 카운트* 로 한다.

장점: row 폭증 / 미리 만든 슬롯 정리 배치 모두 불필요.
단점: 빈 슬롯까지 화면에 표시하려면 애플리케이션이 시간을 한 칸씩 짚어가며(walk) 슬롯
리스트를 만들어야 함 (`AvailableSlotsQueryService`). — GROUP BY 쿼리 1번 + walk 라 빠름.

### Over-booking (정원 초과 예약) 방지 — advisory lock

SELECT COUNT 와 INSERT 사이에 경쟁 구간 (race window) 이 생긴다:

```
Thread A: COUNT → 4 (capacity 5)
Thread B: COUNT → 4
Thread A: INSERT → ok
Thread B: INSERT → ok   ← 6 → over-booking
```

해결: `pg_advisory_xact_lock(hash(centerId, slotStart))` — PostgreSQL 의 응용 락 (테이블/행이
아닌 임의의 정수에 거는 락). 같은 슬롯에 대한 동시 시도를 *한 번에 하나씩* 직렬 처리.
트랜잭션 커밋/롤백 시 자동 해제. ADR-0005 의 매칭 동시성 패턴과 같다.

```
Service.book():
  acquireSlotLock(centerId, slotStart)         ← 같은 슬롯 동시 시도 줄세우기
  count = countActive(centerId, slotStart)
  if count >= capacity: throw SlotFullException
  appointments.save(new InspectionAppointment(...))
  // commit 시 lock 자동 해제
```

UNIQUE 인덱스만으로는 정원 N개를 표현할 수 없다 (UNIQUE 는 같은 키 1개만 허용 — 정원 N개와
다름). 행 단위 비관적 락 (slot row 를 실제로 만들어 잠그기) 도 가능하지만 row 폭증 단점이
더 큼 (위).

### Booking lead time (예약 가능 최소 여유 시간)

너무 임박한 슬롯 (예: 5분 후) 은 거절. 검수원 준비 시간 + 셀러 이동 시간 부족. 도메인 메서드
`isWithinLeadTime(slotStart, now)` 가 검사. 기본 30분 — 센터별 다르게 설정 가능.

### 한 trade 당 active 1개

같은 trade 가 두 슬롯에 동시에 예약될 수 없음 (사용자 실수 / 중복 클릭 방지). `findActiveByTrade`
가 RESERVED + ARRIVED 카운트 — 1 이상이면 `AlreadyBookedException`. 예약을 다른 시간으로
옮기려면 (reschedule) 기존을 취소한 뒤 재예약.

### REST

```
GET  /api/v1/inspection/centers
GET  /api/v1/inspection/centers/{id}/slots?from=...&to=...    캘린더 뷰
POST /api/v1/inspection/appointments                          예약 (Idempotency-Key)
POST /api/v1/inspection/appointments/{id}/cancel              셀러 취소
POST /api/v1/inspection/appointments/{id}/arrive              센터 직원이 도착 확인
POST /api/v1/inspection/appointments/{id}/complete            검수원 통과 결정
POST /api/v1/inspection/appointments/{id}/reject              검수원 거부 결정 (가품/손상)
```

## 대안 검토

- **Slot row 를 미리 INSERT 해두기** — 매일 배치가 30일치 슬롯 INSERT. 거부. row 폭증 +
  정리 배치 필요 + 캘린더 길이를 늘릴 때 (60일로) 또 배치.
- **UNIQUE 인덱스만으로 capacity 강제** — `UNIQUE(center, slot, position)` 같은 식. 거부.
  position 컬럼 의미 모호 + 도메인 규칙이 DB 트릭에 의존하게 됨.
- **행 단위 비관적 락** — `SELECT FOR UPDATE` 로 슬롯 row 를 잠금. 슬롯 row 미리 만들기와
  같은 단점. advisory lock 이 더 가볍 (row 자체를 잡을 필요 없음).
- **낙관적 락 + 재시도** — 충돌 (race) 이 발생하면 client 가 다른 슬롯 시도. 사용자 경험
  나쁨 — "예약 성공" 이라고 해놓고 가끔 정원 초과 알림.
- **외부 캘린더 SaaS (Calendly / Cal.com) 사용** — 외부 의존 + 검수 도메인과 깊이 결합하기
  어려움. 본 프로젝트 수준에선 자체 구현이 단순.

## 결과

- Slot row 폭증 없이 capacity 정확 강제 — advisory lock 으로 over-booking race 차단
- 한 trade 당 1개 active 보장 — 중복 예약 방지
- Booking lead-time 으로 임박 예약 거절 — 검수원 준비 시간 보장
- 캘린더 query 1번의 GROUP BY + walk — 빠름
- (단점) advisory lock 은 PG 전용 — H2 dev 환경엔 fallback 필요 (대부분 단일 워커라 race 안 일어남)
- (단점) 운영 시간 / 휴일 / 동적 capacity 변경 안 함 — 후속

## 후속 후보

- *No-show batch* — slot_end 지난 RESERVED 자동 NO_SHOW (capacity 회수 + 셀러에 페널티)
- *운영 시간 / 휴일 모델* — 특정 요일/시간에만 슬롯 가능
- *동적 capacity* — 검수원 출근/퇴근 / 휴가에 따른 시간대별 capacity 조정
- *기존 InspectionRequest 통합* — Appointment.markCompleted / markRejected 시 InspectionRequest 자동 생성
- *Reschedule API* — cancel + book 두 호출 대신 한 번에 (트랜잭션 1개)
- *셀러 알림* — 예약 1시간 전 / 30분 전 reminder (BudgetAlertRule 같은 패턴)

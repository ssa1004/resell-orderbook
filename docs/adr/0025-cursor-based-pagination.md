# ADR-0025: Cursor-based pagination — OFFSET 의 뒷페이지 절벽 회피

## 상태
적용

## 배경

거래 내역 / 시세 차트 / 알림 목록 — 어느 listing endpoint든 page 번호로 OFFSET 페이지네이션을
시작하면 *데이터가 쌓일수록 뒷페이지가 점점 느려진다*. SQL 의 OFFSET 100000 LIMIT 20 은
DB 가 *0~99999 번 row 까지 모두 정렬·읽고 버린 뒤* LIMIT 20 만 잡는다 — 페이지가 뒤로 갈수록
read I/O 가 선형으로 늘어 *p99 절벽* 형태의 latency 가 나타난다. 메모리 한도 안에서 정렬할
수 없으면 temp-file sort 까지 들어가 더 악화.

또 한 가지 — 새 row 가 *목록의 앞에 자주 끼어들면* OFFSET 페이지네이션은 *같은 row 가 두 번
보이거나 영영 안 보이는* edge case 가 흔하다. 사용자가 페이지 1 을 본 직후 새 row 가 들어와
page 2 의 첫 줄이 *방금 본 row* 가 되거나, 반대로 한 row 가 페이지 사이 경계로 밀려나 영영
스킵되는 경우.

이 두 문제는 페이지가 깊어질수록 *동시에* 일어난다. 무한 스크롤 형태의 timeline 을 다루는
서비스들이 보편적으로 cursor pagination 으로 해결하는 패턴 — 공개 API 표준 문서로는
[GitHub REST API pagination](https://docs.github.com/en/rest/guides/using-pagination-in-the-rest-api)
의 cursor 형식이 좋은 참고가 된다.

## 결정

### Cursor pagination 의 핵심

마지막으로 본 row 의 *정렬 키* 를 cursor 로 받아 다음 페이지를 *점프* 로 잡는다.

```sql
-- OFFSET pagination (느려짐)
SELECT * FROM trades
WHERE seller_id = ?
ORDER BY created_at DESC, id DESC
LIMIT 20 OFFSET 100000;       -- 100000 row 를 읽고 버린다

-- Cursor pagination (일정 latency)
SELECT * FROM trades
WHERE seller_id = ?
  AND (created_at, id) < (?, ?)    -- 직전 페이지 마지막 row 의 정렬 키
ORDER BY created_at DESC, id DESC
LIMIT 21;                          -- N+1 — 다음 페이지 존재 판정용
```

`(created_at, id)` 인덱스가 있으면 *index seek* 로 즉시 점프 — 페이지 위치와 무관하게 동일한
latency. 인덱스가 *왜* (created_at, id) 복합이어야 하는지: 같은 millisecond 에 두 row 가
생성되었을 때 created_at 만으로는 strict 정렬이 깨져 cursor 결정성이 무너진다. id 까지 묶어야
*tie-breaker* 가 되어 *어느 row 가 직전 페이지의 마지막인지* 가 모호하지 않다.

### Opaque token

cursor 의 내용은 클라이언트에 *노출하지 않는다*.

```
Cursor = Base64Url("v1|<epochMillis>|<uuid>")
```

| 이유 | 설명 |
|---|---|
| 호환성 | cursor 의 필드가 바뀌어도 (예: epochMillis → epochNanos, 단일 id → 복합 키) 외부 API 형식은 그대로. 기존 클라이언트가 들고 있던 cursor 도 한동안 받아주려면 *버전 prefix* (`v1`) 로 분기. |
| 캡슐화 | 클라이언트가 임의로 cursor 를 가공해 *없는 페이지* 를 만들지 못하게. opaque 라 형식을 모르면 가공 자체가 불가능. |
| 보안 | 정렬 키에 민감한 정보 (예: 다른 사용자 id) 가 들어가도 인코딩 안에 묻혀 직접 보이지 않는다. |

### N+1 패턴 — 다음 페이지 존재 판정

```
limit + 1 행을 query → limit + 1 번째가 있으면 nextCursor = 마지막 *반환* row 의 키
                       limit 이하면 nextCursor = null (마지막 페이지)
```

별도 `SELECT COUNT(*)` 없이 한 번의 query 로 끝. count 자체가 큰 테이블에서 비싼 query 라 —
cursor 패턴은 *총 페이지 수를 의도적으로 노출하지 않음* 으로써 count 부담을 회피.

### 본 시스템의 적용 위치

| Endpoint | Cursor 종류 | 정렬 키 |
|---|---|---|
| `GET /api/v1/trades/me/history` | (Instant, UUID) 복합 | (created_at DESC, id DESC) |
| `GET /api/v1/market/ticks/{skuId}/cursor` | long 단일 | id ASC (Snowflake — 시간 단조 증가) |

PriceTick 은 ADR-0018 의 Snowflake long ID 가 *발급 시각* 단조 증가라 single-key cursor 만으로
충분 — UUID tie-breaker 불필요. trades 같은 UUID 식별자는 시간 단조성이 없어 (createdAt, id)
복합 키 필요.

### Limit 상한

- Trade history: `1 ~ 100` (default 20). 모바일 한 화면 + 데이터 페이로드 균형.
- PriceTick: `1 ~ 1000` (default 200). 차트 한 화면이 보통 100~500 점.

상한이 없으면 *한 요청* 으로 DB 부하를 일으키는 abuse 가능 — 항상 cap.

### 인덱스 설계

기존 인덱스가 cursor 패턴과 잘 맞는지 확인.

| 테이블 | 기존 인덱스 | cursor 적합성 |
|---|---|---|
| trades | `ix_trade_seller (seller_id)`, `ix_trade_buyer (buyer_id)` | seller/buyer 구분 검색은 가능하지만 *(created_at, id)* 정렬은 별 정렬 단계가 필요. cursor 호출 빈도가 잦아지면 `(seller_id, created_at, id)` 복합 인덱스 추가 검토 |
| price_ticks | (sku_id, id) — Snowflake id PK | id 단조 증가라 cursor pagination 이 *직접* 인덱스 seek 로 점프 |

본 ADR 에서는 인덱스 추가는 안 한다 — 1차로는 기존 인덱스로 P95 충분. 트래픽 증가 시
추가 인덱스 도입 (별도 ADR / 마이그레이션).

### 컨트롤러 / 서비스 레이어 분리

- 컨트롤러: cursor 를 *opaque string* 으로 받음. 검증은 `@Min/@Max` (limit) 만.
- 서비스: `Cursor` → `CursorCodec.decodeXxx` 로 payload 풀고 repo 호출. 깨진 cursor 는
  `CursorCodec.InvalidCursorException` (extends `IllegalArgumentException`) → `GlobalExceptionHandler`
  가 400 (`INVALID_CURSOR`) 으로 매핑.

### Cursor 안정성 가정

- 정렬 키 (created_at) 가 *변경되지 않는다* 가 전제. mutable 컬럼으로 정렬하면 cursor 가
  *같은 row 를 두 번* 또는 *영영 안* 보이게 만들 수 있음. 본 도메인에선 created_at 은 INSERT
  이후 immutable 이라 안전.
- *DELETE 가 일어나는 테이블* 에서는 cursor 가 *이미 사라진 row* 를 가리킬 수 있다 — 이때 SQL
  의 `(created_at, id) < (?, ?)` 가 그대로 동작 (DELETE 된 row 는 query 결과에 없을 뿐). 결과의
  *그 다음 row* 가 자연스럽게 잡힘.

## 대안 검토

- **OFFSET / LIMIT** — 단순. 하지만 데이터가 늘면 뒷페이지 절벽 + 새 row 끼어듦 시 중복/누락.
  소규모 admin 화면이라면 OK 지만 사용자용 listing 엔 부적합.
- **page-number + keyset 보조** — page 1~10 까지는 OFFSET, 그 이후엔 cursor. 구현 복잡, 두 모드
  사이 *페이지 1 의 nextCursor 가 무엇이냐* 같은 corner case 가 늘어남. 실익 미미.
- **자체 cursor without opaque** — `?since_id=12345` 같은 형태. 단순하지만 cursor 형식 변경 시
  외부 호환성 깨짐. opaque token 으로 시작하면 *나중에 단순 노출로 바꾸는* 건 쉬워도 *반대*
  는 어려움.
- **GraphQL Relay Cursor** — 표준 (edge / node / pageInfo) 가 정해져 있지만 본 시스템은 REST.
  표준 구조만 차용해서 (`{ items, nextCursor }`) REST 답게 단순화.

## 결과

- (장) 페이지 깊이와 무관한 일정한 latency — *뒤페이지 절벽* 사라짐
- (장) 새 row 끼어듦 시 *같은 row 가 두 번 보이는* 또는 *영영 누락* 같은 edge case 회피
- (장) opaque token — 서버 cursor 형식 변경에 클라이언트 영향 없음
- (장) `SELECT COUNT(*)` 회피 — count 큰 테이블에서 부담 줄어듦
- (단) *총 페이지 수 모름* — UI 가 "1 / 100 페이지" 같은 표시를 못 함. 사용자 입장에선 무한
  스크롤의 자연스러운 행동이라 실무엔 큰 단점 아님 (timeline 형태의 화면 일반 패턴)
- (단) *임의 페이지로 점프 못 함* — "37 페이지로 가기" 같은 패턴 불가. 본 도메인 (거래 내역,
  차트) 엔 그런 요구가 없음. admin 화면이 필요하면 별도 OFFSET endpoint 추가
- (단) `created_at` 같은 정렬 키가 *immutable* 이어야 안전. mutable 컬럼으론 cursor 사용 금지

## 후속 후보

- *역방향 페이지네이션* — `prevCursor` 추가. 대부분의 timeline UX 는 forward only 라 우선 미적용
- *Bidirectional Relay style* — `before` / `after` / `first` / `last`. GraphQL 도입 시점에 검토
- *(seller_id, created_at, id) 복합 인덱스* — 한 사용자 history 호출이 잦아지고 P99 가 흔들리면 추가
- *Listing(ASK) / Bid 의 사용자별 history* — Trade history 와 같은 패턴으로 확장. cursor codec 은
  공용이라 새 query 만 추가
- *Etag / If-None-Match* — cursor 응답에 Etag 를 같이 — 사용자가 *같은 페이지를 다시 보는* 경우
  304 로 응답. 모바일 환경에서 효과

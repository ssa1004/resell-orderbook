# ADR-0011: 실시간 호가창은 WebSocket 으로 push

## 상태
적용 후 ADR-0014 로 보완

## 배경
KREAM 같은 마켓의 핵심 사용자 경험은 *호가가 실시간으로 깜빡깜빡 갱신* 되는 호가창 화면이다.
클라이언트가 5초마다 GET 으로 폴링 (주기적으로 새 데이터 있는지 묻기) 하면 실시간성이
떨어진다.

## 결정
1차 구현은 **Native WebSocket (Spring `WebSocketHandler` — STOMP 같은 추가 프로토콜 없이
서버-클라이언트 양방향 채널만 사용하는 방식)** 으로 간다.

- 클라이언트가 `/ws/orderbook?skuId=<uuid>` 로 연결한다.
- 연결 시점에 즉시 현재 호가창 스냅샷 (그 순간의 ASK/BID 묶음) 을 한 번 보낸다.
- SKU 변경 이벤트 (TradeMatched / ListingPlaced / BidPlaced / *Cancelled) 가 발생하면 해당
  SKU 구독자에게 새 스냅샷을 서버가 직접 보낸다 (push).

`OrderBookEventBroadcaster` (Kafka 컨슈머) 가 변경 이벤트를 받아
`OrderBookWebSocketHandler.broadcastChange(skuId)` 를 호출한다.

## 장단점
- 매칭 발생 시 100ms 내에 모든 구독자에게 push 가 도달한다.
- 백엔드는 SKU 별로 연결된 세션 집합만 관리하면 돼서 단순하다.
- 1차 구현에서는 STOMP/SockJS 같은 상위 프로토콜은 쓰지 않는다. 이후 ADR-0014 에서 한
  연결로 여러 SKU 구독과 사용자별 알림을 처리하기 위해 STOMP 를 추가했다.
- 세션이 누적되면 메모리가 늘어난다 — `removeIf(!isOpen)` 로 정리.
- 인스턴스가 여러 대일 때, A 인스턴스가 broadcast 한 메시지가 B 인스턴스에 붙은 구독자에게는
  안 가는 문제는 Kafka 컨슈머가 모든 인스턴스에서 동시에 수신하므로 자동으로 해결된다
  (각자 자기에게 붙은 구독자에게만 broadcast 하면 됨).

## 다른 선택지
- **SSE (Server-Sent Events, 서버 → 클라이언트 단방향 스트림)**: 단방향이면 충분하지만,
  나중에 클라이언트 → 서버 메시지 (예: 동적 구독/해제) 가 필요해질 때 WebSocket 이 유리.

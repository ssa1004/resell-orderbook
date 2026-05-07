# ADR-0014: STOMP 도입 — raw WebSocket 보완

## 상태
적용

## 배경

호가창 push 를 위해 raw WebSocket (STOMP 같은 상위 프로토콜 없이 그대로 쓰는 WebSocket,
`/ws/orderbook?skuId=<uuid>`) 을 운영 중. 단점이 드러남:

1. **클라이언트 1개 × SKU N개 = 연결 N개** — 사용자가 호가창 5개를 보면 연결 5개. 브라우저
   동시 연결 한도 (도메인당 6개) 와 충돌, 모바일 배터리 소비 큼.
2. **구독/해제 프로토콜 부재** — 클라이언트가 SKU 변경하려면 연결을 끊고 다시 맺어야 함
   (handshake = 새 연결 시 주고받는 초기 메시지) 비용.
3. **사용자별 알림 채널 없음** — 거래 / 검수 / 정산 같은 사용자 알림은 별도 엔드포인트와
   프로토콜이 필요.
4. **재연결/하트비트 (살아있음 신호) 를 클라이언트가 직접 구현** — 표준화 안 됨.

## 결정

STOMP (WebSocket 위에서 동작하는 메시지 지향 프로토콜. 구독/발행, 헤더, 하트비트 표준 제공)
도입. Spring 의 `@EnableWebSocketMessageBroker` 사용.

```
Endpoint: /ws (STOMP handshake, SockJS fallback — WebSocket 못 쓰는 환경에서 long-polling 등으로 흉내)

SUBSCRIBE /topic/orderbook/{skuId}     ← 호가창 push (broadcast: 같은 토픽 구독자 모두에게)
SUBSCRIBE /user/queue/notifications    ← 사용자별 거래 알림 (point-to-point: 본인에게만)
```

raw WebSocket (`/ws/orderbook`) 도 일정 기간 유지 (옛 클라이언트 호환). 신규 클라이언트는
STOMP 로 유도.

### 핵심 변경

| 영역 | Before | After |
|---|---|---|
| 연결 | SKU 1개 = 연결 1개 | 연결 1개로 SKU N개 SUBSCRIBE |
| 구독 추적 | `OrderBookWebSocketHandler` 가 `Map<SkuId, Set<Session>>` 직접 관리 | STOMP broker 가 자동 처리 |
| 하트비트 | 미구현 | 프로토콜 단에서 10초 양방향 |
| 사용자 알림 | 없음 | `convertAndSendToUser(userId, "/queue/notifications", payload)` |
| 재연결 | 클라이언트가 직접 재수립 | STOMP / SockJS 가 자동 |
| Broadcast 코드 | `handler.broadcastChange(skuId)` 직접 순회 | `SimpMessagingTemplate.convertAndSend(...)` 한 줄 |

### 대안 검토

- **Server-Sent Events (SSE, 서버 → 클라이언트 단방향 스트림)** — 구독 모델이 약함. 호가창
  에는 적합하지만 사용자 알림은 부족.
- **gRPC streaming** — 모바일/브라우저 호환성 문제.
- **단순 폴링** — 호가창은 빈번 업데이트라 부적합.

### 운영 고려

- 메모리 안에서 동작하는 (in-memory) STOMP 브로커 한계: 단일 인스턴스에서 약 10,000 연결.
  수만 도달 시 RabbitMQ STOMP 또는 ActiveMQ Artemis 같은 외부 relay broker (STOMP 메시지를
  중계하는 별도 브로커) 로 교체. 코드는 그대로 (브로커만 바뀜).
- 인스턴스가 여러 대일 때 broadcast 일관성: 각 인스턴스가 같은 Kafka 메시지를 수신하므로
  자기에게 붙은 세션에만 broadcast. 자연스럽게 fan-out (한 이벤트가 모든 인스턴스의 구독자
  에게 퍼지는 형태).

## 결과

- 클라이언트 연결 수 N → 1 (모바일/데스크탑 모두)
- 사용자별 거래 알림이 같은 STOMP 연결로 도착 (UX 개선)
- broadcast 코드 단순화 (template 한 줄)
- (단점) STOMP 학습 곡선 — 클라이언트도 sockjs-client + stompjs 같은 STOMP 라이브러리 사용
  필요. 마이그레이션 가이드 필요.
- (단점) 프로토콜 오버헤드 약간 (WebSocket text frame 안에 STOMP frame 한 겹 더) — 실측은
  필요하나 HTTP/2 같은 압축이 적용되어 영향 적음.

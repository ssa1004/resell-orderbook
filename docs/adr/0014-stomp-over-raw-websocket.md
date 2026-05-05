# ADR-0014: STOMP 도입 — raw WebSocket 보완

## 상태
적용

## 배경

호가창 push 를 위해 raw WebSocket (`/ws/orderbook?skuId=<uuid>`) 을 운영 중. 단점이
드러남:

1. **1 client × N SKU = N connection** — 사용자가 호가창 5개를 보면 5개 connection.
   브라우저 connection 한도 (도메인당 6개) 와 충돌, 모바일 배터리 소비 큼.
2. **Subscribe / Unsubscribe protocol 부재** — 클라이언트가 SKU 변경하려면 connection
   재수립. handshake 비용.
3. **Per-user 알림 채널 없음** — 거래 / 검수 / 정산 같은 사용자 알림은 별도 endpoint /
   protocol 필요.
4. **Reconnect / heartbeat 직접 구현** — 클라이언트마다 별도 코드, 표준화 안 됨.

## 결정

STOMP over WebSocket 도입. Spring 의 `@EnableWebSocketMessageBroker` 사용.

```
Endpoint: /ws (STOMP handshake, SockJS fallback)

SUBSCRIBE /topic/orderbook/{skuId}     ← 호가창 push (broadcast)
SUBSCRIBE /user/queue/notifications    ← 사용자별 거래 알림 (point-to-point)
```

raw WebSocket (`/ws/orderbook`) 도 일정 기간 유지 (legacy 클라이언트 호환). 신규 클라이언트
는 STOMP 로 유도.

### 핵심 변경

| 영역 | Before | After |
|---|---|---|
| Connection | 1 SKU = 1 connection | 1 connection 으로 N SKU SUBSCRIBE |
| 구독 추적 | `OrderBookWebSocketHandler` 가 `Map<SkuId, Set<Session>>` 직접 관리 | STOMP broker 가 자동 처리 |
| Heartbeat | 미구현 | protocol 단에서 10초 양방향 |
| User 알림 | 없음 | `convertAndSendToUser(userId, "/queue/notifications", payload)` |
| Reconnect | 클라이언트가 직접 재수립 | STOMP / SockJS 가 자동 |
| Broadcast 코드 | `handler.broadcastChange(skuId)` 직접 iterate | `SimpMessagingTemplate.convertAndSend(...)` 한 줄 |

### 대안 검토

- **Server-Sent Events (SSE)** — 서버 → 클라이언트 단방향. 구독 모델이 약함. 호가창에는
  적합하지만 사용자 알림은 부족
- **gRPC streaming** — 모바일 / 브라우저 호환성 문제
- **단순 polling** — 호가창은 빈번 업데이트라 부적합

### 운영 고려

- in-memory broker 한계: 단일 인스턴스에서 약 10,000 connection. 수만 도달 시 RabbitMQ
  STOMP 또는 ActiveMQ Artemis relay broker 로 교체. 코드는 그대로 (broker 만 바뀜).
- multi-instance 환경에서 broadcast 일관성: 각 인스턴스가 같은 Kafka 메시지를 수신하므로
  자기에게 붙은 session 에만 broadcast. 자연스럽게 fan-out.

## 결과

- 클라이언트 connection 수 N → 1 (mobile / 데스크탑 모두)
- 사용자별 거래 알림이 같은 STOMP connection 으로 도착 (UX 개선)
- broadcast 코드 단순화 (template 한 줄)
- (단점) STOMP 학습 곡선 — 클라이언트 (sockjs-client + stompjs) 도 STOMP 라이브러리 사용
  필요. 마이그레이션 가이드 필요
- (단점) protocol overhead 약간 (text frame 안에 STOMP frame 한 겹) — 실측은 필요하나
  HTTP/2 같은 압축이 적용되어 영향 적음

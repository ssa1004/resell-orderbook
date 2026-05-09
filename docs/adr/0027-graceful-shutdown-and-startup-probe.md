# ADR-0027: K8s rolling restart 트래픽 손실 0 — graceful shutdown + startup probe + preStop

## 상태
적용 (Spring `server.shutdown=graceful`, k8s startupProbe / preStop / terminationGracePeriodSeconds)

## 배경

K8s 의 rolling restart 는 pod 를 한 대 죽이고 한 대 띄우는 것을 반복한다. 평소엔 잘
돌지만 *세 가지 누락* 이 있으면 트래픽 / 메시지가 손실된다.

```
시나리오 — pod-A 를 종료하고 pod-B 로 교체하는 중

1) Service endpoints 에서 pod-A 가 빠지는 작업 (kube-proxy 의 iptables 갱신)
   과 pod-A 컨테이너에 SIGTERM 보내는 작업은 *비동기* — 보통 수 초 차이.
   → SIGTERM 이 먼저 도착하면 그 사이 도착한 트래픽은 connection refused.

2) 컨테이너가 SIGTERM 직후 즉시 죽으면 처리 중이던 HTTP 요청이 중도 끊김.
   Kafka consumer 가 메시지를 polling 한 직후 commit 전에 죽으면 같은 메시지를
   다음 인스턴스가 한 번 더 가져옴 (at-least-once 라 비즈니스 로직은 멱등성으로
   견뎌도, "오늘만 100건 두 번 처리됨" 같은 운영 잡음).

3) Spring Boot cold start (~ 20~40s) 가 liveness probe 의 initialDelaySeconds 보다
   길면 부팅 도중 liveness fail → K8s 가 "죽었네" 판정 → 재시작 → 또 부팅 중 fail
   → *crashloop*. 이건 OOM 회복 시점에 특히 잘 터진다.
```

본 도메인은 Saga 가 7~10 단계로 길어 *중도 끊김* 비용이 크다. 결제 승인 직후
정산 단계에서 끊기면 보상 트랜잭션이 돌아야 하고 (ADR-0023), 호가창 WebSocket
연결도 깨끗이 닫아주지 않으면 클라이언트가 stale 상태로 남는다.

## 결정

세 가지를 합쳐 운영한다.

### 1. Spring graceful shutdown

`application.yml`:

```yaml
server:
  shutdown: graceful           # SIGTERM 받으면 신규 요청 거부, in-flight 는 끝까지
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # 한 shutdown phase 의 상한
```

도메인 트랜잭션 평균 ~100ms, outbox relay 한 batch (100건) ~ 수 초 → 30s 면 깔끔히
끝남. Spring 이 phase 별로 (web → broker → 일반 빈) 순서대로 닫아주므로 Kafka
consumer 도 commit 까지 마치고 멈춘다.

### 2. K8s preStop sleep + terminationGracePeriodSeconds

`infrastructure/k8s/deployment.yaml`:

```yaml
spec:
  terminationGracePeriodSeconds: 60   # preStop 10s + Spring 30s + 여유
  containers:
    - name: market
      lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "sleep 10"]
```

preStop 이 끝나고 나서야 SIGTERM 이 컨테이너에 전달된다. 그 10s 동안 endpoint
propagation (kube-proxy 가 모든 노드의 iptables 를 갱신할 시간) 가 끝나, SIGTERM
직후엔 이 pod 로 새 트래픽이 더 이상 들어오지 않는다.

`terminationGracePeriodSeconds` 는 preStop + Spring shutdown 합보다 커야 한다 —
이 시간 안에 정리되지 않으면 K8s 가 SIGKILL 로 강제 종료. 60s 는 보수적인 값.

### 3. startupProbe 분리

```yaml
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  failureThreshold: 30          # 30 × 2s = 최대 60s 부팅 허용
  periodSeconds: 2
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  periodSeconds: 10
  failureThreshold: 3
```

startup probe 가 1회라도 성공해야 liveness/readiness 가 시작된다. 부팅이 60s 까지
걸려도 liveness 가 fail 로 잡지 않으니 cold start 가 무한 재시작에 걸리지 않는다.
startup 성공 후의 liveness 는 짧은 주기로 빠르게 응답성을 모니터링.

## 장단점

### 좋은 점
- rolling restart 시 트래픽 손실 0 + Saga 중도 끊김 0
- cold start 가 길어도 crashloop 안 들어감 (startup probe 가 그동안 보호)
- liveness 자체는 빠른 주기 (10s) 로 두어 부팅 후엔 응답성을 즉시 확인

### 비용
- pod 종료가 항상 최소 10s + Spring shutdown 만큼 느려짐 — emergency 라면 `kubectl
  delete --grace-period=0 --force` 로 우회 (의도적으로 엄격)
- terminationGracePeriodSeconds 가 60s 라 cluster autoscaler 의 drain 도 그만큼 느려짐.
  하지만 PDB minAvailable 2 로 가용성은 별도 보장.

### 다시 검토할 시점
- Saga 한 단계 평균 시간이 5s 를 넘게 되면 timeout-per-shutdown-phase 도 같이 키움.
- Kubernetes 가 sidecar container ordering 을 정식 지원하면 (>= 1.29) sidecar 패턴
  으로 init/shutdown 순서를 더 깔끔히 풀 수 있음 — 그때 재검토.

## 참고
- [Kubernetes — Pod Lifecycle: Pod termination](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination)
- [Spring Boot — Graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)
- [Kubernetes — startup probe](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#define-startup-probes)

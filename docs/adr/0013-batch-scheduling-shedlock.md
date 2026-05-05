# ADR-0013: Batch Job 스케줄링 — `@Scheduled` + ShedLock

## 상태
적용

## 배경

market-batch 모듈의 Spring Batch Job 들 (autoCancelStaleTradesJob, expireStaleListingsJob,
expireStaleBidsJob) 은 정의만 있고 launch 주체가 없었음. 운영에 들어가려면 누군가 trigger
해야 함.

선택지:
- **K8s CronJob**: 별도 Pod, 매니페스트 추가 필요
- **`@Scheduled`**: 코드만으로, 단순
- **외부 워크플로 (Argo Workflows / Airflow)**: 단순 batch 엔 과함

## 결정

`@Scheduled` + ShedLock.

### 이유

1. 현재 batch 가 가벼움 (단일 step, 분 단위). K8s CronJob 분리 이득 작음
2. ShedLock 으로 multi-instance 중복 실행 방지
3. 같은 deploy artifact 라 운영 단순화
4. 향후 무거워지면 K8s CronJob 으로 전환 가능 (Job 정의 그대로 재사용)

### 구현

- `MarketJobScheduler` 가 cron 기반 trigger
- `@SchedulerLock` 으로 분산 lock (DB 시계 기준)
- shedlock 테이블 V2 Flyway

```java
@Scheduled(cron = "0 * * * * *")
@SchedulerLock(name = "autoCancelStaleTrades", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
public void runAutoCancelStaleTrades() {
    jobLauncher.run(autoCancelStaleTradesJob, params);
}
```

### Schedule 설계

| Job | cron | lockAtMostFor | 빈도 근거 |
|---|---|---|---|
| autoCancelStaleTrades | `0 * * * * *` (매분) | 5분 | 결제 TTL 15분이라 분 단위 polling 적절 |
| expireStaleListings | `0 0 3 * * *` (매일 03:00 KST) | 30분 | 만료 listing 은 일 단위로 충분 |
| expireStaleBids | `0 30 3 * * *` (매일 03:30 KST) | 30분 | listing 과 시간차 두어 동시 부하 분산 |

## 결과

- 운영 환경에서 batch job 이 자동 실행됨
- multi-instance 에서도 정확히 1번만 실행 (ShedLock)
- (한계) API Pod 와 같은 프로세스 — 무거운 batch 추가 시 별도 Profile 로 분리 권장
- (한계) `spring.batch.job.enabled=false` 가 application.yml 에 필수 (시작 시 자동 실행 방지)

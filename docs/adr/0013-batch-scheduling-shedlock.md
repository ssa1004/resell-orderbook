# ADR-0013: Batch Job 스케줄링 — `@Scheduled` + ShedLock

## 상태
적용

## 배경

market-batch 모듈의 Spring Batch Job 들 (autoCancelStaleTradesJob, expireStaleListingsJob,
expireStaleBidsJob) 은 정의만 있고 실행을 시작해주는 주체가 없었다. 운영에 들어가려면 누군가
주기적으로 트리거해야 한다.

선택지:
- **K8s CronJob (정해진 시간마다 새 Pod 를 띄워 실행하는 쿠버네티스 오브젝트)**: 별도 Pod,
  매니페스트 추가 필요
- **`@Scheduled` (Spring 의 cron 기반 메서드 실행 어노테이션)**: 코드만으로, 단순
- **외부 워크플로 엔진 (Argo Workflows / Airflow 같은 DAG 기반 스케줄러)**: 단순 batch 엔
  과함

## 결정

`@Scheduled` + ShedLock (여러 인스턴스 중 하나만 실행하도록 DB 한 행을 잠가서 막아주는
라이브러리).

### 이유

1. 현재 batch 가 가벼움 (단일 step, 분 단위). K8s CronJob 분리 이득 작음
2. ShedLock 으로 인스턴스가 여러 대일 때 중복 실행 방지
3. 같은 배포 산출물 (deploy artifact) 안에 있어서 운영 단순화
4. 향후 무거워지면 K8s CronJob 으로 전환 가능 (Job 정의 그대로 재사용)

### 구현

- `MarketJobScheduler` 가 cron 표현식 기준으로 트리거
- `@SchedulerLock` 으로 인스턴스 사이 잠금 (DB 시계 기준 — 모든 인스턴스가 같은 DB 시간을
  보고 잠금 만료를 판단)
- shedlock 테이블은 Flyway 마이그레이션 V2 로 추가

```java
@Scheduled(cron = "0 * * * * *")
@SchedulerLock(name = "autoCancelStaleTrades", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
public void runAutoCancelStaleTrades() {
    jobLauncher.run(autoCancelStaleTradesJob, params);
}
```

### 스케줄 설계

| Job | cron | lockAtMostFor | 빈도 근거 |
|---|---|---|---|
| autoCancelStaleTrades | `0 * * * * *` (매분) | 5분 | 결제 TTL 15분이라 분 단위 폴링 적절 |
| expireStaleListings | `0 0 3 * * *` (매일 03:00 KST) | 30분 | 만료 listing 은 일 단위로 충분 |
| expireStaleBids | `0 30 3 * * *` (매일 03:30 KST) | 30분 | listing 과 시간차 두어 동시 부하 분산 |

## 결과

- 운영 환경에서 batch job 이 자동 실행됨
- 인스턴스가 여러 대여도 정확히 1번만 실행 (ShedLock)
- (한계) API Pod 와 같은 프로세스 — 무거운 batch 추가 시 별도 Spring profile 로 분리 권장
- (한계) `spring.batch.job.enabled=false` 가 application.yml 에 필수 (앱 시작 시 자동 실행
  방지 — 대신 우리가 명시적으로 트리거)

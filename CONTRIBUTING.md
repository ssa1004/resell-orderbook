# Contributing

본 저장소의 개발 흐름과 commit 규칙을 정리한 문서입니다.

## 브랜치 전략

GitHub Flow 를 따릅니다. 작은 규모의 프로젝트이므로 git-flow 와 같은 복잡한 모델은 사용하지
않습니다.

```
main (protected)
  ├── feature/match-engine          ← 기능 브랜치
  ├── fix/h2-lock-difference
  └── docs/update-readme
```

`main` 에서 새 브랜치를 생성하고 (`git checkout -b feature/<짧은-설명>`), 작업 후 PR 을
열어 코드 리뷰를 받고, CI 통과 후 Squash and merge 합니다. 머지 후 feature 브랜치는
삭제합니다. `main` 은 항상 배포 가능한 상태로 유지됩니다.

## Commit 메시지

Conventional Commits 형식을 따릅니다.

```
<type>(<scope>): <짧은 설명, 50자 이내>

<상세 설명, 한 줄에 72자 이내>
- 무엇이 / 왜 변경되었는지
- 영향받는 모듈
```

자주 사용하는 type 은 다음과 같습니다.

- `feat`: 새 기능 추가
- `fix`: 버그 수정
- `refactor`: 동작 변경 없는 코드 정리
- `test`: 테스트 추가 / 수정
- `docs`: 문서만 변경
- `chore`: 빌드 / 설정 / 의존성 변경
- `perf`: 성능 개선

scope 에는 모듈 이름 (`domain`, `application`, `adapter-out` 등) 이 들어갑니다. 호가 매칭
도메인의 특성상 `feat(domain)` 과 `fix(adapter-out)` 의 빈도가 높습니다.

### 예시

```
feat(domain): MatchEngine 의 buyNow / sellNow 추가

- BuyNow: BID 등록 없이 Lowest ASK 즉시 매수, 가격 = ASK 가격
- SellNow: ASK 등록 없이 Highest BID 즉시 매도
- 본인 호가에 대한 self-trade 차단
```

```
fix(adapter-out): H2 의 FOR UPDATE 동작 차이로 dev 매칭 실패하던 문제

dev 환경 (H2) 에서 PESSIMISTIC_WRITE 가 다른 트랜잭션의 commit 된 행을
다르게 처리하여 매칭이 깨지던 문제. advisory-lock 비활성 시 락 없는 단순
read 사용. prod (Postgres) 에서는 advisory lock + FOR UPDATE 그대로 유지.
```

## Commit 단위

한 commit 은 한 가지 논리적 변경만 담는 것을 원칙으로 합니다. 50개 파일을 초과하는 commit
은 거의 항상 분리 가능합니다. WIP commit 은 PR 머지 전에 squash 합니다.

## 테스트

PR 전 `./gradlew test` 통과가 필수입니다.

- 도메인 변경 후 빠른 검증: `:market-domain:test`
- 통합 시나리오 (Postgres Testcontainer 필요): `:e2e-tests:test`
- 모듈 경계 검증 (Spring Modulith verify): `:market-bootstrap:test`

## 코드 스타일

- Java: Google Java Format 또는 IntelliJ default
- Kotlin: ktlint
- 주석 / 문서는 자연스러운 한국어 (영어 직역체 지양)

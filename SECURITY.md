# Security Policy

## 지원 버전

본 저장소는 포트폴리오/연구용 단일 라인이며, `main` 브랜치만 보안 패치를 적용합니다.

| 버전 | 지원 여부 |
|---|---|
| `main` | 지원 |
| 그 외 브랜치 / 태그 | 지원 안 함 |

## 취약점 보고

취약점을 발견하면 GitHub 의 [Private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
기능을 사용해 주세요.

- 저장소: <https://github.com/ssa1004/resell-orderbook>
- 경로: **Security** 탭 → **Report a vulnerability**

공개 issue 에는 취약점 상세를 적지 마세요.

## 응답 시점

- 접수 확인: 영업일 기준 7일 이내
- 영향 평가 / 패치 계획: 14일 이내

## 적용 범위

본 저장소 안의 코드 — `market-*` 모듈, `infrastructure/`, `helm/`, `scripts/`,
GitHub Actions workflow — 만 대상입니다. 외부 의존 라이브러리의 취약점은
업스트림에 직접 보고해 주세요.

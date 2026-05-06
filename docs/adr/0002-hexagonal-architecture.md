# ADR-0002: 헥사고날 아키텍처 (port/adapter)

## 상태
적용

## 배경
도메인 코드가 인프라(JPA, Kafka, Redis, S3, OAuth2) 에 직접 의존하면 몇 가지 문제가 생긴다.

- 도메인 단위 테스트가 무거워진다 (Spring 컨텍스트 필요).
- 인프라 교체 비용이 커진다.
- 도메인 모델이 ORM 어노테이션이나 직렬화 규칙에 침해된다.

## 결정
**Port–Adapter 로 분리한다.**

```
adapter-in  ─→ application (port.in 호출)
                  ↓
              domain (순수)
                  ↑
adapter-out ─→ application (port.out 구현)
```

- `domain` — 도메인 로직은 Spring/JPA/Kafka API 를 사용하지 않는다.
- `application` — Spring `@Service`/`@Transactional` 만 사용하고, 외부는 `port.out` 인터페이스로 추상화.
- `adapter-out` — JPA 엔티티, 매퍼, 어댑터가 도메인을 엔티티로 변환하고 다시 복원한다.

## 장단점
- 도메인 단위 테스트가 `Money`, `Listing`, `Trade` 만으로 가능 — Spring 컨텍스트가 필요 없어 millisecond 단위로 끝난다.
- JPA 를 JOOQ 또는 R2DBC 로 바꿀 때 도메인 코드는 변경하지 않아도 된다.
- 매퍼 반복 코드가 늘어난다.
- 신규 인원이 "엔티티와 도메인이 왜 따로 있는지" 학습 비용이 있다.

## 다시 검토할 시점
도메인이 거의 CRUD 만 하는 단순 모듈이라면 구조가 과할 수 있다. 그런 모듈만 단순 계층형 구조로 갈 수도 있다.

---
paths: ["src/main/**/domain/**/*.kt", "src/main/**/*Entity.kt", "src/main/kotlin/com/depromeet/piki/admin/**/*.kt", "src/main/resources/db/migration/**"]
---

# DB 스키마 (외래 키 · 마이그레이션)

`CLAUDE.md` 의 `## DB 스키마` 스텁이 불변식을 갖고, 이 파일이 상세 규약이다. 엔티티(`*/domain/**`·`*Entity.kt`·admin 패키지)와 마이그레이션 파일을 다룰 때 자동 로드된다. `@Entity` 22개 중 6개가 `domain/` 밖(admin 2·product 3·common 1)에 있어 패턴을 셋으로 둔다.

## 테이블 간 외래 키

**DB `FOREIGN KEY` 제약을 두지 않는다.** 테이블 간 관계는 논리적으로만 연결한다.

### 규칙
- 마이그레이션에 `CONSTRAINT ... FOREIGN KEY` 를 추가하지 않는다. 엔티티는 raw ID 필드(`itemId: Long` 등)로 다른 테이블을 참조하며, JPA 연관관계 어노테이션(`@ManyToOne` 등)도 쓰지 않는다.
- 조회 성능을 위한 인덱스(`KEY idx_*`)는 FK 와 무관하므로 그대로 둔다.
- 참조 무결성은 애플리케이션 코드(서비스 계층의 존재 검증 등)가 책임진다.

## DB 마이그레이션

**도구**: Flyway. **위치**: `src/main/resources/db/migration/`. 상세 규약(네이밍·out-of-order·commutative·forward-only·destructive 단계 배포)은 그 디렉터리의 `CLAUDE.md` 에 있고, 마이그레이션 파일을 다룰 때 자동으로 로드된다. FK 제약은 추가하지 않는다 (위 외래 키 규칙).

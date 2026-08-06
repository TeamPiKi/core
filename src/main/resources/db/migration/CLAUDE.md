# DB 마이그레이션

**도구**: Flyway. **네이밍**: `V{YYYYMMDDHHmmss}__{snake_case_description}.sql` (예: `V20260521143015__add_index_on_wishes_user_id.sql`). KST 기준, 파일을 만들 때의 현 시각을 prefix 로 부여한다 (`date +%Y%m%d%H%M%S`, `HH` 는 24시간).

## 규칙

- **이미 적용된 마이그레이션 파일은 수정·삭제하지 않는다.** Flyway 는 적용 시점에 checksum 을 저장하고, 이후 파일 내용이 바뀌면 다음 부팅에서 실패한다. (삭제는 `ignore-migration-patterns: "*:missing"` 덕에 부팅 자체는 되지만, 신규·CI 환경의 스키마가 운영과 달라진다. 레거시 정리는 `create_init_schema` 로 squash 된 것에 한해 예외다.) 컬럼·제약을 바꿔야 하면 **새 timestamp 로 추가 마이그레이션** 을 작성한다.
- **timestamp 재발급은 불필요하다 — `out-of-order: true`.** 마이그레이션이 전부 additive(순서 무관)이므로 작업 PR 의 timestamp 가 `dev` 최신보다 작아 순서가 어긋나도 Flyway 가 그대로 적용한다. 파일 생성 시각 prefix 를 머지까지 그대로 둔다.
- **마이그레이션은 commutative(순서 무관)하게 유지한다.** `ADD COLUMN` · `CREATE INDEX` · `CREATE TABLE` 같은 additive 는 어느 순서로 적용해도 결과가 같다. 반대로 **순서 의존 변경**(컬럼 rename, 기존 컬럼 `NOT NULL` 화, 같은 컬럼을 두 PR 이 동시 변경, 데이터 `UPDATE` backfill 등)은 out-of-order 에서 적용 순서가 결과를 바꾸므로, 아래 destructive 항목처럼 단계 배포로 분리해 한 배포 사이클 안에서 순서를 보장한다.
- **동시 작업 충돌은 머지 게이트가 잡는다.** branch protection 의 "Require branches to be up to date before merging" 으로, `dev` 가 갱신되면 PR 은 최신 `dev` 와 합쳐 CI 를 다시 통과해야 머지된다. 합쳐서 SQL 이 서로 깨지는 충돌(같은 컬럼 중복 추가 등)은 이 재실행에서 걸린다. 단 CI(빈 DB)는 버전순으로만 적용하므로 "둘 다 SQL 은 성공하나 적용 순서가 결과를 바꾸는" 경우는 못 잡는다 — 그 사각은 위 commutative 규율로 메운다.
- **FK 제약 절대 추가 금지.** (자세한 이유는 루트 `CLAUDE.md` 의 `## 테이블 간 외래 키` 섹션) 조회 인덱스(`KEY idx_*`) 는 그대로 둔다.
- **Forward-only.** Flyway down migration / 롤백 SQL 을 작성하지 않는다. 잘못된 마이그레이션을 되돌리려면 **새 timestamp 로 보정 마이그레이션** 을 추가한다.
- **DROP / RENAME 류 destructive 작업은 단계적으로.** 단일 마이그레이션에서 끝내면 (a) 데이터 손실 위험, (b) 배포 중 옛 코드와 새 스키마가 잠시 공존하는 동안 깨진다. 가능한 한 **add → backfill → drop** 3단계로 나눠 배포한다.
- 변경 의도가 한눈에 드러나는 description 을 쓴다.

-- 뺀 후보를 다시 담으면 500 이 났다(#1246).
-- 빼기는 deleted_at 만 채우는 soft delete 인데 uk_tournament_items (tournament_id, snapshot_id) 는
-- 삭제 표시된 행까지 센다. 담기의 중복 검사는 살아 있는 행만 보므로 통과하고, 위시가 가리키는
-- snapshot 이 그대로라 같은 조합으로 insert 해 Duplicate entry 로 터진다. 전용 처리기가 없어 500 이 나갔다.
--
-- 해법은 유일성 판정을 활성 행으로 좁히는 것이다. MySQL 은 partial unique index 를 지원하지 않으므로
-- generated column 으로 흉내낸다 (uk_tournaments_active_invite_code 와 같은 수단).
-- 활성 행(deleted_at IS NULL): active_snapshot_id = snapshot_id → 같은 후보의 중복 출전 차단
-- 삭제 행(deleted_at IS NOT NULL): active_snapshot_id = NULL → MySQL unique 는 NULL 여러 개 허용
--
-- 세 단계를 세 파일로 나눈 이유: MySQL DDL 은 트랜잭션으로 묶이지 않는다. 한 파일에 세 문장을 두면
-- 두 번째·세 번째가 실패할 때(메타데이터 잠금 대기 초과·배포 중단 등) 앞 문장의 변경은 남는데
-- 그 파일은 success=0 으로 기록돼, repair 후 재실행하면 이 컬럼 추가에서 다시 실패한다.
-- 파일을 나누면 성공한 단계는 완료로 남고 실패한 파일만 복구하면 된다.
-- 세 파일은 순서 의존이지만 Flyway 가 한 실행에서 버전 순으로 적용하므로 out-of-order 와 무관하다.
--   1) 이 파일: 가상 컬럼 추가
--   2) uk_tournament_items_active 추가
--   3) 옛 uk_tournament_items 제거
--
-- VIRTUAL 인 이유: STORED 는 테이블을 재작성하며 그 동안 DML 을 막아, 마이그레이션이 도는 사이
-- 후보 담기·빼기가 멈춘다. 값은 인덱스가 들고 있고 컬럼을 직접 조회하지 않으므로 STORED 의 이점이 없다.
-- 선례(tournaments.active_invite_code)는 STORED 였고, 그 선택은 따르지 않는다.
--
-- ALGORITHM·LOCK 을 명시하는 이유: 온라인으로 가능하다는 가정을 DB 가 검증하게 한다. 가정이 틀리면
-- 조용히 재작성으로 떨어지는 대신 문장이 실패해 배포 전에 드러난다.
ALTER TABLE tournament_items
    ADD COLUMN active_snapshot_id BIGINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, snapshot_id, NULL)) VIRTUAL,
    ALGORITHM = INPLACE,
    LOCK = NONE;

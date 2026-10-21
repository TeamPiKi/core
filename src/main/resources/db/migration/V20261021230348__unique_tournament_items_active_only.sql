-- 뺀 후보를 다시 담으면 500 이 났다(#1246).
-- 빼기는 deleted_at 만 채우는 soft delete 인데 uk_tournament_items (tournament_id, snapshot_id) 는
-- 삭제 표시된 행까지 센다. 담기의 중복 검사는 살아 있는 행만 보므로 통과하고, 위시가 가리키는
-- snapshot 이 그대로라 같은 조합으로 insert 해 Duplicate entry 로 터진다. 전용 처리기가 없어 500 이 나갔다.
--
-- MySQL 은 partial unique index 를 지원하지 않으므로 generated column 으로 흉내낸다
-- (uk_tournaments_active_invite_code 와 같은 수단).
-- 활성 행(deleted_at IS NULL): active_snapshot_id = snapshot_id → 같은 후보의 중복 출전 차단
-- 삭제 행(deleted_at IS NOT NULL): active_snapshot_id = NULL → MySQL unique 는 NULL 여러 개 허용
--
-- VIRTUAL 이고 세 문장으로 나눈 이유: STORED 는 테이블을 재작성하며 그 동안 DML 을 막아, 마이그레이션이
-- 도는 사이 후보 담기·빼기가 멈춘다. 가상 컬럼 추가는 다른 ALTER 동작과 같은 문장에 둘 수 없어
-- (그렇게 두면 다시 재작성으로 떨어진다) 문장을 분리한다. 값은 인덱스가 들고 있고 컬럼을 직접
-- 조회하지 않으므로 STORED 의 이점이 없다. 선례(tournaments)와 다른 선택이고, 이쪽이 그 선례보다 낫다.
--
-- ALGORITHM·LOCK 을 명시하는 이유: 셋 다 온라인으로 가능하다는 가정을 DB 가 검증하게 한다. 가정이
-- 틀리면 조용히 재작성으로 떨어지는 대신 문장이 실패해 배포 전에 드러난다.
--
-- 옛 키는 같은 마이그레이션에서 뗀다. 그 키가 곧 결함의 원인이라 남기면 고쳐지지 않는다.
-- destructive 를 단계 배포하라는 규약의 두 근거가 여기엔 걸리지 않는다.
--   데이터 손실: 인덱스만 떼므로 없음
--   배포 중 공존: 유니크 제약을 떼는 것은 제한을 푸는 방향이라 옛 코드가 깨지지 않음
-- 조회 경로도 잃지 않는다 - idx_tournament_items_tournament_id 와 idx_tournament_items_snapshot_id 가
-- 따로 있고, 새 키의 선행 컬럼도 tournament_id 로 같다.
--
-- 기존 데이터는 새 키를 이미 만족한다 - 옛 키가 더 강한 제약이었다(삭제 행까지 유일 판정).
ALTER TABLE tournament_items
    ADD COLUMN active_snapshot_id BIGINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, snapshot_id, NULL)) VIRTUAL,
    ALGORITHM = INPLACE,
    LOCK = NONE;

ALTER TABLE tournament_items
    ADD UNIQUE KEY uk_tournament_items_active (tournament_id, active_snapshot_id),
    ALGORITHM = INPLACE,
    LOCK = NONE;

ALTER TABLE tournament_items
    DROP INDEX uk_tournament_items,
    ALGORITHM = INPLACE,
    LOCK = NONE;

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
-- 옛 키를 같은 마이그레이션에서 떼는 이유: 그 키가 곧 결함의 원인이라 남기면 고쳐지지 않는다.
-- destructive 를 단계 배포하라는 규약의 두 근거가 여기엔 걸리지 않는다.
--   데이터 손실: 인덱스만 떼므로 없음
--   배포 중 공존: 유니크 제약을 떼는 것은 제한을 푸는 방향이라 옛 코드가 깨지지 않음
-- 조회 경로도 잃지 않는다 - idx_tournament_items_tournament_id 와 idx_tournament_items_snapshot_id 가
-- 따로 있고, 새 키의 선행 컬럼도 tournament_id 로 같다.
--
-- 기존 데이터는 새 키를 이미 만족한다 - 옛 키가 더 강한 제약이었다(삭제 행까지 유일 판정).
ALTER TABLE tournament_items
    ADD COLUMN active_snapshot_id BIGINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, snapshot_id, NULL)) STORED,
    ADD UNIQUE KEY uk_tournament_items_active (tournament_id, active_snapshot_id),
    DROP INDEX uk_tournament_items;

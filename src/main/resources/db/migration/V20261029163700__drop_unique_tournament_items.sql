-- #1246 3단계. 배경과 단계 분리 이유는 V20261029163658 의 주석에 있다.
--
-- 삭제 행까지 세던 옛 키를 뗀다. 그 키가 곧 결함의 원인이라 남기면 고쳐지지 않는다.
-- destructive 를 단계 배포하라는 규약의 두 근거가 여기엔 걸리지 않는다.
--   데이터 손실: 인덱스만 떼므로 없음
--   배포 중 공존: 유니크 제약을 떼는 것은 제한을 푸는 방향이라 옛 코드가 깨지지 않음
-- 조회 경로도 잃지 않는다 - idx_tournament_items_tournament_id 와 idx_tournament_items_snapshot_id 가
-- 따로 있고, 2단계에서 세운 키의 선행 컬럼도 tournament_id 로 같다.
ALTER TABLE tournament_items
    DROP INDEX uk_tournament_items,
    ALGORITHM = INPLACE,
    LOCK = NONE;

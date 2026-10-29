-- #1246 2단계. 배경과 단계 분리 이유는 V20261029163658 의 주석에 있다.
--
-- 활성 행만 유일하게 판정하는 키를 세운다. 옛 키는 3단계에서 뗀다 - 먼저 새 키를 세워야
-- 둘 사이에 유일성 보호가 비는 구간이 없다.
--
-- 기존 데이터는 이 키를 이미 만족한다 - 옛 키가 더 강한 제약이었다(삭제 행까지 유일 판정).
ALTER TABLE tournament_items
    ADD UNIQUE KEY uk_tournament_items_active (tournament_id, active_snapshot_id),
    ALGORITHM = INPLACE,
    LOCK = NONE;

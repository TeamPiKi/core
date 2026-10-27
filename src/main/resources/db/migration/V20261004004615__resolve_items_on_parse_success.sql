-- 아이템은 파싱 성공 때 확정되므로 위시·토너먼트 아이템의 아이템 칸은 확정 전 null 이다.
-- 버전을 가리키던 snapshot_id 와 버전 status 는 더 쓰지 않는다. 기존 행 백필(#1176 10단계) 전까지 지우지 않고 null 만 허용한다.
ALTER TABLE wishes
    MODIFY COLUMN item_id BIGINT NULL,
    MODIFY COLUMN snapshot_id BIGINT NULL;

ALTER TABLE tournament_items
    ADD COLUMN item_id BIGINT NULL,
    MODIFY COLUMN snapshot_id BIGINT NULL,
    ADD KEY idx_tournament_items_item_id (item_id);

-- 기존 출전은 버전으로만 상품을 가리켰다. 새 아이템 칸을 그 버전의 아이템으로 채우지 않으면 아이템도 요청도 없는 카드가 된다
UPDATE tournament_items t
JOIN item_snapshots s ON s.id = t.snapshot_id
SET t.item_id = s.item_id;

-- 출전 값은 따로 고정하지 않고 시작 시각 기준으로 계산한다.
ALTER TABLE tournaments
    ADD COLUMN started_at DATETIME(6) NULL;

ALTER TABLE item_snapshots
    MODIFY COLUMN status VARCHAR(16) NULL;

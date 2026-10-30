-- 옛 큐가 버전 행을 상태로 집던 인덱스. 큐는 item_parse_outbox 로 옮겼고 버전의 status 를 읽는 조회가 없다
ALTER TABLE item_snapshots
    DROP INDEX idx_item_snapshots_status_created_at,
    DROP INDEX idx_item_snapshots_status_updated_at;

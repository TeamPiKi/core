-- 파싱 전환(#1176) 뒤 매핑·쿼리 참조가 0 인 옛 구조. prod 의 v1.10.1 도 언급하지 않아 배포 겹침 동안에도 안전하다
-- wishes.memo 는 V20261014204931 이 wish_memos 로 옮겼다
DROP TABLE item_links, parse_requests;

ALTER TABLE item_snapshots
    DROP COLUMN status,
    DROP COLUMN attempt_count,
    DROP COLUMN extracted_at;

ALTER TABLE items
    DROP INDEX uk_items_source_image_key,
    DROP COLUMN source_url,
    DROP COLUMN source_image_key;

ALTER TABLE wishes
    DROP INDEX idx_wishes_snapshot_id,
    DROP COLUMN snapshot_id,
    DROP COLUMN memo;

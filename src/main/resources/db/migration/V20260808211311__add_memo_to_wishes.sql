-- 위시 개인 메모(#900) — item·snapshot 은 여러 사용자가 공유하므로 개인 기록은 user 소유인 wishes 행에 둔다.
-- additive — FK 없음(프로젝트 정책). forward-only.
ALTER TABLE wishes ADD COLUMN memo VARCHAR(100) NULL AFTER snapshot_id;

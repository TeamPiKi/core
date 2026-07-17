-- N일 자동삭제(created_at < cutoff 하드삭제)가 풀스캔하지 않도록 created_at 단일 인덱스 추가.
-- age 기준 배치는 유저 무관 전역 스캔이라 (user_id, ...) 인덱스로는 커버되지 않는다.
CREATE INDEX idx_notifications_created_at ON notifications (created_at);

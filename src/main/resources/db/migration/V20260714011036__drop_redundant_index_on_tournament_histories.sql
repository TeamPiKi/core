-- idx_tournament_histories_tournament_id (tournament_id) 는
-- 복합 인덱스 idx_tournament_histories_tid_tuid (tournament_id, tournament_user_id) 의
-- leftmost prefix 로 완전히 커버되는 중복 인덱스다. write 비용만 늘리므로 제거한다.
-- 복합 인덱스는 유지한다 — hot read (tournament_id, tournament_user_id) 를 그대로 커버.
ALTER TABLE tournament_histories
    DROP INDEX idx_tournament_histories_tournament_id;

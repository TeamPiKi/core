CREATE TABLE wish_memos (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    wish_id    BIGINT        NOT NULL,
    content    VARCHAR(100)  NULL,
    written_at DATETIME(6)   NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    deleted_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_wish_memos_wish_id_written_at (wish_id, written_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 쓴 시각이 남아 있지 않아 위시를 마지막으로 고친 시각으로 둔다
INSERT INTO wish_memos (wish_id, content, written_at, created_at, updated_at)
SELECT id, memo, updated_at, NOW(6), NOW(6)
FROM wishes
WHERE memo IS NOT NULL AND memo <> '';

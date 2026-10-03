CREATE TABLE item_parse_outbox (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    item_snapshot_id BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    deleted_at       DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_parse_outbox_item_snapshot_id (item_snapshot_id),
    KEY idx_item_parse_outbox_status_created_at (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

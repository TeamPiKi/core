CREATE TABLE item_parse_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    requester_type   VARCHAR(32)   NOT NULL,
    requester_id     BIGINT        NOT NULL,
    source_url       VARCHAR(2048) NULL,
    -- 대소문자만 다른 단축 코드가 같은 요청으로 묶이지 않게 바이트 단위로 비교한다
    source_url_without_tracking VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    source_image_key VARCHAR(255)  NULL,
    status           VARCHAR(16)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    deleted_at       DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_item_parse_outbox_requester (requester_type, requester_id),
    UNIQUE KEY uk_item_parse_outbox_source_image_key (source_image_key),
    KEY idx_item_parse_outbox_status_created_at (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE item_parse_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    requester_type   VARCHAR(32)   NOT NULL,
    requester_id     BIGINT        NOT NULL,
    purpose          VARCHAR(16)   NOT NULL,
    input_type       VARCHAR(16)   NOT NULL,
    input_value      VARCHAR(768)  NOT NULL,
    -- 대소문자만 다른 단축 코드가 같은 요청으로 묶이지 않게 바이트 단위로 비교한다
    url_without_tracking VARCHAR(768) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    -- 링크는 여러 번 요청할 수 있어 input_value 에 유니크를 못 건다. 이미지일 때만 값이 생기는 칸을 두고 거기에 건다
    image_key        VARCHAR(255) AS (IF(input_type = 'IMAGE', input_value, NULL)) VIRTUAL,
    status           VARCHAR(16)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    deleted_at       DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_item_parse_outbox_requester (requester_type, requester_id),
    UNIQUE KEY uk_item_parse_outbox_image_key (image_key),
    KEY idx_item_parse_outbox_status_created_at (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

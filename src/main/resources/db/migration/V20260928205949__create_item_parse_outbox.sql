CREATE TABLE item_parse_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    requester_type   VARCHAR(32)   NOT NULL,
    requester_id     BIGINT        NOT NULL,
    purpose          VARCHAR(16)   NOT NULL,
    input_type       VARCHAR(16)   NOT NULL,
    input_value      VARCHAR(768)  NOT NULL,
    -- 같은 요청자의 중복 요청 판정 키. 링크는 추적 파라미터를 뗀 URL, 이미지는 키 그대로
    -- 대소문자만 다른 단축 코드가 같은 요청으로 묶이지 않게 바이트 단위로 비교한다
    canonical_input  VARCHAR(768)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    status           VARCHAR(16)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    deleted_at       DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_item_parse_outbox_requester (requester_type, requester_id),
    KEY idx_item_parse_outbox_status_created_at (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

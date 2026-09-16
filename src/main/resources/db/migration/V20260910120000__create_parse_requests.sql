-- 파싱 작업 상태를 버전 이력(item_snapshots)에서 떼어낼 요청 테이블(#1073). 이 단계는 적재만 기록하고 아무도 읽지 않는다.
-- 상태·시각 컬럼을 지금 다 두는 이유는 마이그레이션을 한 번으로 끝내기 위해서다. FK 제약은 두지 않는다(프로젝트 정책).
CREATE TABLE parse_requests (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    item_id            BIGINT       NOT NULL,
    requested_by       BINARY(16)   NOT NULL,
    trigger_type       VARCHAR(16)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    attempt_count      INT          NOT NULL DEFAULT 0,
    -- item_snapshots.updated_at 하나가 겸하던 집기·박동·종결 시각을 나눈다. 마감 시계는 created_at.
    claimed_at         DATETIME(6)  NULL,
    heartbeat_at       DATETIME(6)  NULL,
    finished_at        DATETIME(6)  NULL,
    failure_reason     VARCHAR(32)  NULL,
    result_snapshot_id BIGINT       NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    deleted_at         DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_parse_requests_status_created_at (status, created_at),
    KEY idx_parse_requests_status_heartbeat_at (status, heartbeat_at),
    KEY idx_parse_requests_item_id_status (item_id, status),
    -- 요청 하나가 버전 하나를 만든다.
    UNIQUE KEY uk_parse_requests_result_snapshot_id (result_snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

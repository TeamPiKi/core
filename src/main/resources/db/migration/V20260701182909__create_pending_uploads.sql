-- 이미지 등록 v2(presigned) 의 "발급~등록 사이" 대기 업로드 매핑. 발급 시 여기에 맥락(요청자·경로)을 기록하고,
-- confirm(클라 호출) 또는 폴링 백스톱이 S3 존재를 확인하면 이 행을 claim(삭제)하며 PENDING 아이템으로 등록한다.
-- claim 은 image_key UNIQUE + 행 삭제로 원자화한다 — confirm 과 폴링이 같은 key 를 다퉈도 삭제에 성공한 한쪽만 등록한다(멱등).
-- FK 는 두지 않는다(프로젝트 컨벤션 — 관계는 논리적으로만). 조회 인덱스만 둔다.
CREATE TABLE pending_uploads
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    image_key     VARCHAR(255) NOT NULL COMMENT 'S3 raw object key (items/raw/{uuid}.{ext})',
    user_id       BINARY(16)   NOT NULL COMMENT 'UUID V4 — 발급 요청자',
    context       VARCHAR(16)  NOT NULL COMMENT 'WISH / TOURNAMENT',
    tournament_id BIGINT       NULL COMMENT 'TOURNAMENT 일 때 대상 토너먼트(WISH 는 NULL)',
    expires_at    DATETIME(6)  NOT NULL COMMENT '이 시각 넘게 안 올라온 매핑은 폴링이 정리',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    deleted_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_uploads_image_key (image_key),
    KEY idx_pending_uploads_expires_at (expires_at)
) COMMENT '이미지 등록 v2 발급~등록 대기 매핑(outbox)';

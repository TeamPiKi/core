ALTER TABLE pending_uploads
    ADD COLUMN next_check_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        COMMENT '폴링이 다음에 업로드 여부를 확인할 시각. 확인이 헛돌 때마다 발급 후 경과 시간만큼 뒤로 밀린다';

CREATE INDEX idx_pending_uploads_next_check ON pending_uploads (expires_at, next_check_at);

-- 사용자 수 마일스톤 알림의 발송 기록. 임계값을 PK 로 두어 INSERT IGNORE 로 "임계값당 정확히 1회" 발송을 보장한다
-- (동시 가입으로 리스너가 여러 번 돌아도 처음 claim 한 호출만 affected=1 을 받아 발송한다). FK 는 두지 않는다(컨벤션).
CREATE TABLE user_milestone_announcements
(
    threshold    BIGINT      NOT NULL COMMENT '도달한 사용자 수 임계값',
    announced_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '알림 발송(claim) 시각',
    PRIMARY KEY (threshold)
) COMMENT '사용자 수 마일스톤 알림 발송 기록 — 임계값당 1회 발송 보장';

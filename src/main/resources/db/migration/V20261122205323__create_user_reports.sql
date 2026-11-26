-- 사용자 신고(#1291). 운영자가 확인할 기록이라 지우지 않고 쌓는다.
--
-- 탈퇴해도 신고 기록은 유지한다 — 차단·친구와 다르다. 신고는 두 사람 사이의 관계가 아니라
-- 운영 판단의 근거이고, 신고된 사람이 탈퇴로 기록을 지울 수 있으면 제재 이력이 사라진다.
--
-- reason 은 코드 문자열로 박는다(enum 이름). 사유 목록이 늘어도 스키마를 안 건드린다.
-- detail 은 사유가 기타일 때만 채워지고 300자 상한은 입력 경계가 본다.
--
-- deleted_at 칸은 LongBaseEntity 의 컬럼 규약이라 두되 쓰지 않는다(늘 NULL).
-- FK 는 두지 않는다(컨벤션).
CREATE TABLE user_reports (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    -- 신고한 사람
    reporter_id  BINARY(16)   NOT NULL,
    -- 신고된 사람
    reported_id  BINARY(16)   NOT NULL,
    reason       VARCHAR(50)  NOT NULL,
    -- 사유가 기타일 때만. 비어 있어도 된다
    detail       VARCHAR(300) NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    deleted_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- "이 사람이 받은 몇 번째 신고인지" 와 백오피스 상세가 이 방향으로 읽는다
    KEY idx_user_reports_reported_id (reported_id),
    KEY idx_user_reports_reporter_id (reporter_id),
    -- 백오피스 목록이 최근순으로 100건을 끊어 읽는다. 없으면 신고가 쌓일수록 전수를 읽고 정렬한다
    KEY idx_user_reports_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

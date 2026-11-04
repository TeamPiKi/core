-- 추출 경로가 대상 몰에 밝히는 신원의 백오피스 설정 (#1276). 배포 없이 값을 바꾼다.
-- 행이 최대 하나인 설정 테이블이고(item_quota_settings 와 같은 구조), 행이 없으면 extractor·renderer 의 기본 신원이다.
CREATE TABLE extraction_identity_settings (
    id         TINYINT     NOT NULL DEFAULT 1,
    identity   VARCHAR(40) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_extraction_identity_settings_single_row CHECK (id = 1)
);

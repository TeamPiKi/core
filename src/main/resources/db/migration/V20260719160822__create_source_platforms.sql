-- 출처 커머스몰 표시명 레지스트리 (#766). 위시 응답의 sourcePlatform 을 도메인별로 백오피스에서 배포 없이 관리한다
-- (extraction_platform_policies 와 같은 동적 설정 패턴 — 조회 시 유도라 수정이 과거 item 에도 즉시 소급된다).
-- domain 은 정규형(소문자, trailing dot 없음)이며 서브도메인 포함 suffix 매칭의 기준이다. 행이 없는 도메인은
-- URL host 에서 유도한 임시 표시명(PSL 기반 등록 가능 도메인의 첫 라벨)으로 fallback 한다.
-- 시드 없음 — 정식 브랜드 표기는 운영자가 백오피스에서 등록한다 (등록 전까지는 fallback 이 커버).
CREATE TABLE source_platforms (
    domain       VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (domain)
);

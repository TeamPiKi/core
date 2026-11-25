-- 몰별 추출 규칙의 원본(#1309). extractor 가 읽는 배포본은 Redis 의 키 하나이고, 이 테이블에서 만들어 올린다.
-- 모양과 동작 규칙의 정본은 TeamPiKi/infra 의 contracts/extraction.proto(ExtractionRule)와 extraction-api.md 7절.
--
-- rule_json 은 계약의 ExtractionRule JSON 을 id 만 빼고 그대로 둔다. 필드를 컬럼으로 쪼개면 계약에 출처 종류가
-- 늘 때마다 스키마를 바꿔야 한다. TEXT 라 길이 상한은 입력 경계가 본다.
--
-- deleted_at 칸은 LongBaseEntity 의 컬럼 규약이라 두되 쓰지 않는다(늘 NULL). 삭제는 행을 지우고 이력에 남긴다.
CREATE TABLE extraction_rules (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    -- 사람이 읽는 이름(예: 올리브영). 추출에는 쓰이지 않는다
    label       VARCHAR(100)  NOT NULL,
    rule_json   TEXT          NOT NULL,
    -- 저장 전 시험 추출에 쓴 상품 주소. 몰의 구조가 바뀌었을 때 같은 주소로 다시 시험한다
    sample_url  VARCHAR(2048) NOT NULL,
    -- 꺼진 규칙은 배포본에서 빠진다
    enabled     TINYINT(1)    NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    deleted_at  DATETIME(6)   NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 저장·삭제마다 그 시점의 본문을 한 줄씩 쌓는다. 감사 로그(detail 500자)는 규칙 본문을 담지 못해, 규칙이 틀린 값을
-- 내기 시작했을 때 무엇이 바뀌었는지 보고 이전 본문으로 되돌릴 근거가 여기뿐이다.
-- 마지막 id 가 배포본의 판 번호다. 규칙이 지워져도 번호가 뒤로 가지 않는다.
-- FK 는 두지 않는다(컨벤션).
CREATE TABLE extraction_rule_revisions (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    extraction_rule_id  BIGINT        NOT NULL,
    -- SAVED | DELETED. enum 이름 문자열
    change_type         VARCHAR(16)   NOT NULL,
    label               VARCHAR(100)  NOT NULL,
    rule_json           TEXT          NOT NULL,
    sample_url          VARCHAR(2048) NOT NULL,
    enabled             TINYINT(1)    NOT NULL,
    -- 백오피스 작업자 이름
    actor               VARCHAR(50)   NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    deleted_at          DATETIME(6)   NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

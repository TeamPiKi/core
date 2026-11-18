-- Spring Modulith 의 이벤트 발행 기록. 모양의 정본은 spring-modulith-events-jdbc 의 schemas/v2/schema-mysql.sql.
-- 라이브러리의 쿼리가 대문자 이름을 쓰고 리눅스 MySQL 은 테이블명의 대소문자를 구분한다.
CREATE TABLE EVENT_PUBLICATION
(
    ID                     VARCHAR(36)  NOT NULL,
    LISTENER_ID            VARCHAR(512) NOT NULL,
    EVENT_TYPE             VARCHAR(512) NOT NULL,
    SERIALIZED_EVENT       VARCHAR(4000) NOT NULL,
    PUBLICATION_DATE       TIMESTAMP(6) NOT NULL,
    COMPLETION_DATE        TIMESTAMP(6) NULL DEFAULT NULL,
    STATUS                 VARCHAR(20),
    COMPLETION_ATTEMPTS    INT,
    LAST_RESUBMISSION_DATE TIMESTAMP(6) NULL DEFAULT NULL,
    PRIMARY KEY (ID),
    INDEX EVENT_PUBLICATION_BY_COMPLETION_DATE_IDX (COMPLETION_DATE)
);

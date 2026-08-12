-- 헤드리스 허가 화이트리스트 원장. 브라우저(헤드리스)로 페이지를 여는 것은 플랫폼의 명시적 허가를 받은 도메인에만
-- 허용한다 — 기본은 거부이므로 컬럼 기본값이 FALSE 이고, 행이 아예 없는 도메인도 거부다("행 없음 = 기본 = 불가").
-- 기존 시드 행(전부 UNSUPPORTED)은 기본값 FALSE 로 남아 동작이 바뀌지 않는다.
--
-- permission_ref / permission_granted_at 은 감사추적이다: 어떤 근거로(메일 스레드·수신일·담당자 등) 언제 허가가
-- 켜졌는지. 허가는 사람이 받아 오는 것이라 근거를 원장에 함께 남기지 않으면 "누가 언제 왜 열었나"를 되짚을 수 없다.
ALTER TABLE extraction_platform_policies
    ADD COLUMN headless_allowed      BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN permission_ref        VARCHAR(255) NULL,
    ADD COLUMN permission_granted_at DATETIME(6)  NULL;

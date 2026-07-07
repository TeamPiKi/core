-- 플랫폼(host)별 추출 라우팅 정책 (#9 디스패처). 코드 하드코딩(ProductLink.UNSUPPORTED_HOSTS)을 DB 로 옮겨
-- 백오피스에서 배포 없이 차단 추가·해제할 수 있게 한다 (notification_templates.push_enabled 와 같은 동적 설정 패턴).
-- domain 은 정규형(소문자, trailing dot 없음)이며 서브도메인 포함 suffix 매칭의 기준이다. 행이 없는 도메인은 기본
-- 추출 체인(구조화 -> LLM, 차단 시 헤드리스 에스컬레이트)을 탄다. route 는 ExtractionRoute enum 문자열.
CREATE TABLE extraction_platform_policies (
    domain     VARCHAR(255) NOT NULL,
    route      VARCHAR(32)  NOT NULL,
    reason     VARCHAR(255) NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (domain)
);

-- 시드: 기존 하드코딩 UNSUPPORTED_HOSTS 를 그대로 옮긴다 (2026-06 dev 실측 근거는 reason 에 요약).
INSERT INTO extraction_platform_policies (domain, route, reason, updated_at) VALUES
    ('kream.co.kr',      'UNSUPPORTED', '2026-06-16 실측: 직접 GET 500(no body), 봇 차단', NOW(6)),
    ('coupang.com',      'UNSUPPORTED', '2026-06-16 실측: 403 봇 차단', NOW(6)),
    ('naver.com',        'UNSUPPORTED', '2026-06-16 실측: 쇼핑 418·스토어 CAPTCHA(429/490), 차단 변동적', NOW(6)),
    ('naver.me',         'UNSUPPORTED', '네이버 공유 단축 도메인 (본 도메인과 함께 차단)', NOW(6)),
    ('oliveyoung.co.kr', 'UNSUPPORTED', '2026-06-18 실측: 전 UA 403 JS 챌린지, 상품 데이터 0', NOW(6)),
    ('oy.run',           'UNSUPPORTED', '올리브영 공유 단축 도메인 (어느 경로로도 상품 데이터 없음)', NOW(6)),
    ('a-bly.com',        'UNSUPPORTED', '2026-06-18 실측: 실페이지 전 UA 403, 가격 OG 부재 (헤드리스로 이름·이미지는 가능)', NOW(6));

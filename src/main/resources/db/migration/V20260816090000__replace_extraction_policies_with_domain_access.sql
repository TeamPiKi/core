-- 플랫폼 정책 테이블을 "허용/차단" 한 축으로 교체한다.
--
-- 왜 이관하지 않고 새로 만드나: 옛 route 3값(SUPPORTED·HEADLESS_FIRST·UNSUPPORTED)과 허가 boolean 은
-- 서로 다른 질문(등록을 받나 / 어떻게 가져오나 / 확인했나 / 브라우저를 써도 되나)에 한 축으로 답하고 있어
-- 기계적 매핑이 성립하지 않는다. 같은 HEADLESS_FIRST 라도 kream 은 차단이고 store.kakao 는 차단이 아니라
-- 갈 곳이 다르다. 그래서 값을 옮기지 않고 비운 채 시작하며, 판단이 선 도메인만 백오피스에서 다시 넣는다.
--
-- 시드를 두지 않는 것도 같은 이유의 의도된 결정이다. 쿠팡·네이버 등이 당분간 실패로 흐르지만, 그 실패는
-- "아직 판단하지 않았다"는 사실의 정직한 반영이다 — 자동 차단 감지(후속)나 메일 회신으로 판단이 서면 그때
-- BLOCKED 로 넣는다. 반대로 근거 없이 미리 채우면 옛 테이블이 좀비 행을 갖게 된 경로를 그대로 반복한다.
--
-- ALLOWED 는 "플랫폼의 명시적 허락을 받았다"는 뜻이고, 그 결과로 렌더 서비스의 우회 수단(지문 보정·프록시)이
-- 열린다. 허락은 사람이 받아 오는 것이라 근거(permission_ref) 없이는 켤 수 없다 — 입력 경계가 그걸 강제한다.
CREATE TABLE domain_access_policies (
    domain         VARCHAR(255) NOT NULL,
    -- ALLOWED | BLOCKED. 값 이름을 enum 이 아니라 문자열로 두는 이유는 옛 테이블과 같다 — 이 바이너리가
    -- 모르는 값 한 행이 findAll 하이드레이션을 깨 부팅을 죽이는 함정을 피하고, 읽는 쪽이 tolerant 하게 진다.
    access         VARCHAR(16)  NOT NULL,
    -- 왜 그렇게 판단했나. BLOCKED 면 무엇으로 막혔는지(403·앱 브릿지·로그인 필요), ALLOWED 면 어떤 허락인지.
    reason         VARCHAR(255) NULL,
    -- 허락 근거(메일 스레드·수신일·담당자). ALLOWED 행에는 필수이며 입력 경계가 검증한다.
    permission_ref VARCHAR(255) NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (domain)
);

-- 옛 테이블은 이 마이그레이션에서 지우지 않는다. 코드가 참조를 끊은 배포가 한 바퀴 돈 뒤 별도 마이그레이션으로
-- 제거한다 — blue-green 공존 구간에서 구버전 인스턴스가 아직 옛 테이블을 읽기 때문이다(단계 배포).

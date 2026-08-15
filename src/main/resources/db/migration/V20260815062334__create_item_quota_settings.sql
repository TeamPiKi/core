-- 아이템 등록 한도의 백오피스 조절 (#934). 배포 없이 한도를 조이거나 푼다
-- (extraction_models · extraction_platform_policies 와 같은 동적 설정 패턴).
--
-- 이 테이블이 없을 때 한도는 application.yml 과 환경변수(ITEM_QUOTA_*)에만 있어, 비용이 튀어 급히 조여야 할 때도
-- 한도가 낮아 정상 사용자가 막힐 때도 배포나 재시작을 기다려야 했다.
--
-- **행이 최대 하나인 설정 테이블이다.** id 를 상수 1 로 못박아(CHECK) 두 행이 생기는 것을 스키마가 막는다.
-- 축(target·domain)별로 갈리는 extraction_models 와 달리 이 값들은 서비스 전체에 하나뿐이라 키가 필요 없다.
--
-- **모든 값 컬럼이 nullable 이고 NULL 은 "이 노브는 env 기본값을 쓴다" 는 뜻이다.** 행 자체가 없으면 전부 기본값이다
-- ("행 없음 = 기본" 규약 — extraction_models 의 model 과 같다). 부분 오버라이드를 허용해, 상한 하나만 급히
-- 내리려고 나머지 값까지 화면에서 다시 적어 넣는 일이 없게 한다.
--
-- window(창 길이)는 여기 두지 않는다. 바꾸면 이미 돌고 있는 카운터는 옛 TTL 로 만료되고 새 카운터만 새 창을 쓰는데,
-- 사용자마다 창 시작 시점이 달라 "지금 어떤 상태인가" 를 설명할 수 없다. 창 변경은 드문 일이라 배포로 남긴다.
CREATE TABLE item_quota_settings (
    id                     TINYINT     NOT NULL DEFAULT 1,
    -- 끄면 차감·판정을 통째로 건너뛴다. 한도가 잘못 잡혀 정상 사용자를 막을 때 되돌리는 스위치.
    enabled                BOOLEAN     NULL,
    -- 계정 하나의 창당 몫. 위시 등록·토너먼트 아이템 추가(게스트가 넣은 것 포함)가 전부 여기서 깎인다.
    user_limit             INT         NULL,
    -- 서비스 전체의 창당 상한. 넘으면 503 으로 흘려보낸다.
    capacity_limit         INT         NULL,
    -- 전역 상한의 몇 %에서 경고 로그를 남길지. 상한에 닿으면 이미 늦으므로 이 지점이 실질 방어선이다.
    capacity_alert_percent INT         NULL,
    updated_at             DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_item_quota_settings_single_row CHECK (id = 1)
);

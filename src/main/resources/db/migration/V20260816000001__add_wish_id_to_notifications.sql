-- 파싱 알림의 위시 상세 딥링크 대상(wishId)을 평탄화해 저장할 컬럼(#933).
-- TOURNAMENT 라우팅이 tournament_id·tournament_item_id 를 싣듯, WISH 라우팅은 이 컬럼에 wishId 를 싣는다.
-- 수신자별로 다른 값이며, 컬럼 도입 전 과거 행은 NULL 로 남는다(백필하지 않는다 — 클라가 refId(itemId)로
-- 역추적하는 기존 폴백이 있고, 알림 보존기간이 지나면 자연히 사라진다).
-- 테이블 간 FK 는 두지 않는다(프로젝트 규약) — 참조 무결성은 애플리케이션이 책임진다.
ALTER TABLE notifications
    ADD COLUMN wish_id BIGINT NULL COMMENT '위시 상세 딥링크 대상(wishId). WISH 라우팅에서만 채워지며 과거 행은 NULL.';

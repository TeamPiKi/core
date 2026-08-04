-- 상품 별칭 매핑(#825): 지금까지 본 모든 링크 모양(사용자 원본·귀결점)을 item 으로 잇는 조회 공간.
-- 등록 즉시 원본(정규화 후)이 기록되어, 파싱이 끝나기 전(pending 창)에 같은 문자열이 재등록되면 기존 item 에
-- 즉시 붙는다(등록의 21% 가 동일 문자열 재등록 — 단체방 공유 링크를 여러 친구가 여는 패턴, 2026-07-30 실측).
-- 파싱 완료 시 귀결점도 별칭으로 추가되어, 다음부터는 어떤 모양으로 들어와도 조회 한 번으로 매칭된다.
--
-- url_hash unique 는 동시 등록 경합의 직렬화 장치를 겸한다 — 같은 링크를 동시에 등록하면 별칭 insert 는
-- 한쪽만 성공하고, 진 쪽은 그 행을 읽어 같은 item 에 합류한다(#826 의 바닥).
-- url_hash 는 canonical_hash 와 같은 이유(utf8mb4 768자 인덱스 상한 < URL 2048자)의 SHA-256 hex 대리키.
--
-- item_id 는 raw 참조(FK 제약 없음 — 프로젝트 정책). idx_item_links_item_id 는 병합 시 별칭 이관
-- (UPDATE ... WHERE item_id = 임시item)용 역방향 조회를 받친다.
CREATE TABLE item_links (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    url        VARCHAR(2048) NOT NULL,
    url_hash   CHAR(64)      NOT NULL,
    item_id    BIGINT        NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    deleted_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_item_links_url_hash (url_hash),
    KEY idx_item_links_item_id (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

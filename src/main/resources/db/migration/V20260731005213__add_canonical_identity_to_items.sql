-- 상품 정체성 공유(#825)의 정체성 키. canonical_url 은 리다이렉트를 따라간 최종 URL 을 정규화
-- (추적 파라미터·fragment 제거 + 몰별 규칙, CanonicalLink)한 귀결점으로, "링크 하나 = item 하나"의 단일 진실이다.
-- 파싱 성공 시점에 확정되므로 NULL 허용이고, 기존 행은 소급하지 않는다(forward-only —
-- MySQL unique 는 NULL 중복을 허용해 기존 행과 공존한다).
--
-- canonical_hash 는 unique 인덱스용 고정 길이 대리키(SHA-256 hex). utf8mb4 인덱스 키 상한(3072B = 768자)이
-- VARCHAR(2048) 직접 unique 를 막고, cafe24 계열의 퍼센트 인코딩 경로가 수백 자라 컬럼 축소도 위험하다.
-- 이 unique 가 "같은 귀결점의 item 은 하나"를 DB 가 강제한다 — 서로 다른 단축링크가 같은 상품을 동시에
-- 처음 파싱하는 경합에서 한쪽만 canonical 을 얻고, 진 쪽은 충돌을 받아 병합(재부모화) 경로로 빠진다.
--
-- additive·commutative: 컬럼 추가 + 인덱스 추가만이라 적용 순서가 결과를 바꾸지 않는다.
ALTER TABLE items
    ADD COLUMN canonical_url  VARCHAR(2048) NULL,
    ADD COLUMN canonical_hash CHAR(64)      NULL,
    ADD UNIQUE KEY uq_items_canonical_hash (canonical_hash);

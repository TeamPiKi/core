-- 정규화 URL 을 768자로 줄이고 유니크를 값에 직접 건다. 768 은 utf8mb4 인덱스 키 상한(3072B)이고 prod 실측 최대는 603자
-- 해시 대리키는 조회 때 앱이 같은 식으로 해시를 만들어야 해 DB 식에 코드가 묶인다
-- 대소문자만 다른 경로가 같은 상품으로 묶이지 않게 바이트 단위로 비교한다
ALTER TABLE items DROP INDEX uq_items_canonical_hash;
ALTER TABLE items DROP COLUMN canonical_hash;
ALTER TABLE items
    MODIFY COLUMN canonical_url VARCHAR(768) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD UNIQUE KEY uq_items_canonical_url (canonical_url);

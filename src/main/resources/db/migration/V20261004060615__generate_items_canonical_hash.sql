-- 유니크용 해시를 앱이 계산해 저장하던 것을 DB 가 원문에서 만들게 한다. 원문과 해시가 어긋날 수 없고 인덱스가 절반이 된다
-- 원문에서 다시 계산되는 파생값이라 칸을 바꿔도 잃는 데이터가 없다
ALTER TABLE items DROP INDEX uq_items_canonical_hash;
ALTER TABLE items DROP COLUMN canonical_hash;
ALTER TABLE items
    ADD COLUMN canonical_hash BINARY(32) AS (UNHEX(SHA2(canonical_url, 256))) STORED,
    ADD UNIQUE KEY uq_items_canonical_hash (canonical_hash);

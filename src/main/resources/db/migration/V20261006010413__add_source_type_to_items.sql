-- 이미지 아이템도 정체 값(이미지 키)을 갖게 한다. 값이 비었는지로 링크와 이미지를 가르던 것을 종류 칸으로 옮긴다
ALTER TABLE items ADD COLUMN source_type VARCHAR(16) NULL;

UPDATE items
SET source_type   = IF(source_image_key IS NULL, 'LINK', 'IMAGE'),
    canonical_url = COALESCE(source_image_key, canonical_url);

ALTER TABLE items MODIFY COLUMN source_type VARCHAR(16) NOT NULL;

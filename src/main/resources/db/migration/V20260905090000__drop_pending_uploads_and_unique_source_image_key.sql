DROP TABLE pending_uploads;

ALTER TABLE items
    MODIFY COLUMN source_image_key VARCHAR(255) NULL
        COMMENT '이미지 등록 경로의 입력. S3 raw object key(items/raw/{uuid}.{ext}). 같은 업로드를 두 번 확정해도 한 item 만 생기도록 unique';

CREATE UNIQUE INDEX uk_items_source_image_key ON items (source_image_key);

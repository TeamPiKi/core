-- 할인 전 정가. NULL 은 모름(정가를 노출하지 않는 몰·이미지 등록·이 컬럼 이전의 버전)
ALTER TABLE item_snapshots ADD COLUMN original_price INT NULL AFTER price;

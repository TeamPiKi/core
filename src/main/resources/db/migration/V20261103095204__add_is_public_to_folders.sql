-- 폴더 공개 여부(#1274). 공개 프로필 작업에서 쓴다. 정책상 기본값은 공개
ALTER TABLE folders
    ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT TRUE AFTER name;

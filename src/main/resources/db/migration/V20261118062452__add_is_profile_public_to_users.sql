-- 내 프로필 공개 설정(#1286). 끄면 폴더 설정과 관계없이 다른 사람에게 폴더·위시를 보이지 않는다. 기본값은 공개
ALTER TABLE users
    ADD COLUMN is_profile_public BOOLEAN NOT NULL DEFAULT TRUE AFTER identity_type;

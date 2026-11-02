-- 위시 폴더(#1264). 위시를 복사하지 않고 가리키기만 한다
-- name 은 글자 수(이모지 1자)로 20자를 재므로 코드 포인트 기준 칸을 넉넉히 둔다
CREATE TABLE folders (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    user_id    BINARY(16)    NOT NULL,
    name       VARCHAR(100)  NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    deleted_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_folders_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 빼면 행을 지운다. 유니크 키가 같은 폴더에 같은 위시가 두 번 들어가는 것을 막는다
-- created_at 이 폴더에 넣은 시각이고, 폴더 안 정렬과 표지의 기준이다
CREATE TABLE folder_wishes (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    folder_id  BIGINT       NOT NULL,
    wish_id    BIGINT       NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    deleted_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_folder_wishes_folder_id_wish_id (folder_id, wish_id),
    KEY idx_folder_wishes_wish_id (wish_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

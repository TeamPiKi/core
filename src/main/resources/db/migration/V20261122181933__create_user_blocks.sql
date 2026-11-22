-- 사용자 차단(#1289). 차단하면 서로의 위시를 볼 수 없다 - 효과는 양방향이지만 행은 한 방향이다.
-- 누가 차단했는지를 알아야 "차단한 사용자입니다"(차단한 쪽)와 비공개 프로필(차단당한 쪽)을 가를 수 있다.
--
-- 차단 해제는 행을 지운다(하드). 친구 관계(#1288)와 같은 이유다 - 남겨 둘 이력 가치가 없고,
-- 삭제 표시만 남기면 유니크가 그 행까지 세어 재차단이 막힌다.
-- deleted_at 칸은 LongBaseEntity 의 컬럼 규약이라 두되 쓰지 않는다(늘 NULL).
--
-- FK 는 두지 않는다(컨벤션). 사용자 존재 확인은 서비스가 한다.
CREATE TABLE user_blocks (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    -- 차단한 사람
    user_id     BINARY(16)  NOT NULL,
    -- 차단된 사람
    blocked_id  BINARY(16)  NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    -- 컬럼 규약상 존재할 뿐 쓰지 않는다
    deleted_at  DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- 같은 상대를 두 번 차단하지 못하게 한다. 재차단은 멱등 성공이라 서비스가 먼저 거른다
    UNIQUE KEY uk_user_blocks_user_id_blocked_id (user_id, blocked_id),
    -- "나를 차단한 사람이 있나" 를 묻는 경로(노출 판정·친구 추가 거부·탈퇴 정리)가 이 방향으로 조회한다
    KEY idx_user_blocks_blocked_id (blocked_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 단방향 친구(#1288). 요청·수락이 없어 내가 추가하면 내 목록에만 생기고 상대에게 알림도 가지 않는다.
-- 그래서 한 행이 한 방향이다 - A→B 와 B→A 는 서로 독립이고, 둘 다 있으면 서로 친구인 상태일 뿐이다.
--
-- 친구 삭제는 행을 지운다(하드). "그 관계가 없던 것으로" 가 전부라 남겨 둘 이력 가치가 없고,
-- 삭제 표시만 남기면 유니크가 그 행까지 세어 재추가가 막힌다(#1246 과 같은 함정).
--
-- 그래도 deleted_at 칸은 둔다 - LongBaseEntity 가 모든 엔티티에 그 칸을 매핑해 ddl-auto=validate 가 요구한다.
-- 이 테이블에서는 늘 NULL 이다(쓰는 코드가 없다).
--
-- FK 는 두지 않는다(컨벤션). 사용자 존재 확인은 서비스가 한다.
CREATE TABLE friendships (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    -- 추가한 사람. 이 사람의 친구 목록에 아래 사람이 뜬다
    user_id      BINARY(16)  NOT NULL,
    -- 추가된 사람
    friend_id    BINARY(16)  NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    -- 컬럼 규약상 존재할 뿐 쓰지 않는다. 위 주석 참고
    deleted_at   DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- 같은 방향을 두 번 담지 못하게 한다. 재추가는 멱등 성공이라 서비스가 먼저 거른다
    UNIQUE KEY uk_friendships_user_id_friend_id (user_id, friend_id),
    -- 탈퇴 시 양방향 삭제가 friend_id 로도 지우므로 그쪽 조회 경로를 연다
    KEY idx_friendships_friend_id (friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

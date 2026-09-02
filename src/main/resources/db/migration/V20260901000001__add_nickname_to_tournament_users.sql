-- 토너먼트 전용 표시명(#1018). 유저 프로필 닉네임(users.nickname)과 분리한다 — 토너먼트 입장 닉네임을 바꿔도
-- 프로필이 바뀌지 않게. 신규 참여부터 join 시점의 프로필 닉네임으로 채우고(스냅샷), 이후 프로필 수정에 영향받지 않는다.
-- NULL 은 레거시(마이그레이션 이전 참여) — 표시 시 users.nickname 으로 폴백한다(기존 데이터는 백필하지 않는다).
-- 길이는 users.nickname(VARCHAR(10)) 과 맞춘다. FK 는 두지 않는다(컨벤션).
ALTER TABLE tournament_users
    ADD COLUMN nickname VARCHAR(10) NULL COMMENT '토너먼트 전용 표시명 — NULL 이면 users.nickname 폴백(레거시)';

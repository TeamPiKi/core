-- 참여자별 플레이(진행) 상태를 tournament_users 로 옮기기 위한 첫 단계(#1027 Phase 1).
-- 현재 tournaments.status 는 "정의(PENDING/IN_PROGRESS)" 와 "한 사람의 진행(COMPLETED)" 을 겸직하는데,
-- 두 번째 사람이 오는 순간 담을 곳이 없어 CLONE(source_tournament_id) 행이 생겨났다. 진행 상태를 참여 행으로
-- 내리면 CLONE 이 붙들던 마지막 정보가 제자리를 찾는다. 이 컬럼이 그 그릇이다.
--
-- Phase 1 은 컬럼만 추가한다 — 읽는 코드가 아직 없어 배포해도 동작 변화가 없다(백필은 Phase 2, 읽기 전환은 Phase 3).
-- ADD COLUMN + DEFAULT 라 additive·commutative(순서 무관)하고, 기존 행은 DB 가 DEFAULT 로 채운다.
-- 길이·기본값은 tournaments.status(VARCHAR(50) NOT NULL DEFAULT 'PENDING') 와 맞춘다. FK 는 두지 않는다(컨벤션).
ALTER TABLE tournament_users
    ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'PENDING';

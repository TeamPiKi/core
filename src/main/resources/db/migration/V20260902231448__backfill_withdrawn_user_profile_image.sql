-- 기존 탈퇴 유저(tombstone)의 프로필을 탈퇴 전용 S3 아바타(defaults/user-deleted.png)로 backfill 한다.
-- 신규 탈퇴 경로의 아바타 교체(이 PR — User.withdraw 가 DefaultProfileImages.deleted() 를 받는다)와 한 세트로,
-- 배포 시 Flyway 가 각 env RDS 에 자동 적용한다. 탈퇴해도 공유 토너먼트 히스토리엔 참여자로 남으므로
-- 프사가 "탈퇴한 유저" 임을 드러내야 한다(기존 값은 외부 dicebear URL 이라 우리 자산이 아니었다).
--
-- 대상: deleted_at 이 채워진 행 전부. users 의 tombstone 은 곧 탈퇴 회원이며(게스트는 보존할 공유 참조가 없어
-- 하드삭제 경로라 행이 남지 않는다), tombstone 프사는 정의상 비식별 값이어야 하므로 조건 없이 덮는다.
-- 고정값으로 덮으므로 재실행해도 결과가 같다(멱등). 기존 기본 프사 backfill 두 개(V20260607211858 ·
-- V20260608005959)는 deleted_at IS NULL 가드가 있어 tombstone 을 건드리지 않으므로 적용 순서와 무관하다(out-of-order 안전).
--
-- URL 은 env 별 publicBaseUrl(placeholder)로 조립해 DefaultProfileImages.deleted() 와 같은 포맷을 맞춘다
-- (trailing-slash 제거 + /defaults/user-deleted.png). publicBaseUrl 미설정(로컬/테스트)이면 가드로 no-op.
UPDATE users
SET profile_image = CONCAT(TRIM(TRAILING '/' FROM '${s3publicbaseurl}'), '/defaults/user-deleted.png')
WHERE deleted_at IS NOT NULL
  AND '${s3publicbaseurl}' <> '';

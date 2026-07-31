-- ============================================================================
-- AWS 계정 이전(#808) — 이미지 URL 재작성
--
-- 실행 시점: 컷오버 창 E-26-1 (구 prod 덤프를 새 RDS 에 복원한 직후)
-- 실행 대상: 새 계정 RDS 의 prod DB
-- 실행 방법:
--   mysql -h <new-rds-endpoint> -u <user> -p <db> < 20260731_rewrite_image_urls_to_new_account.sql
--
-- 계정번호가 바뀌면 아래 두 값을 찾아 바꾼다 (전체 8곳):
--   구계정 250758375457  →  신계정 996918499382
--
-- ---------------------------------------------------------------------------
-- 왜 Flyway 가 아닌가
--
-- 이 SQL 이 컷오버 전 구 prod 에 한 번이라도 적용되면 flyway_schema_history 에
-- 기록이 남고, 그 history 가 mysqldump 로 새 RDS 에 그대로 복원돼 재실행되지 않는다.
-- 가드로 no-op 이 되어도 Flyway 는 success 로 기록하므로 "success = 재작성 완료" 가
-- 성립하지 않는다. 밖에 두면 affected rows 로 실제 재작성 건수를 즉시 확인할 수 있다.
--
-- ---------------------------------------------------------------------------
-- 왜 사용자 변수(@old_account)를 쓰지 않는가  ← 리허설에서 실측된 제약
--
-- 이 DB 는 컬럼별 collation 이 섞여 있다 (테이블 생성 시기 차이).
--
--   DB 기본 · 세션                  utf8mb4_0900_ai_ci
--   announcements.body              utf8mb4_0900_ai_ci
--   users.profile_image             utf8mb4_unicode_ci
--   item_snapshots.image_url        utf8mb4_unicode_ci
--   notifications.actor_image_url   utf8mb4_unicode_ci
--
-- 사용자 변수는 coercibility 가 컬럼과 같은 레벨(IMPLICIT)이라
-- `col LIKE CONCAT('%', @var, '%')` 가 ERROR 1267 (Illegal mix of collations) 로 깨진다.
-- 문자열 리터럴은 컬럼 collation 을 따라가므로 충돌하지 않는다. 그래서 리터럴을 쓴다.
--
-- ---------------------------------------------------------------------------
-- 왜 계정번호만 치환하는가
--
-- 버킷명이 계정번호 파생(piki-images-<계정>)이고 **키 경로는 구·신이 완전히 동일**하다
-- (2026-07-31 B-10 에서 356개 실파일 키 전수 대조, 차이는 0바이트 폴더 마커뿐).
-- 따라서 계정번호 12자리만 바꾸면 prod·dev·staging 버킷 URL 이 모두 정확히 맞는다.
-- 소셜 로그인 외부 프로필 URL(카카오·구글 등)은 계정번호를 포함하지 않아 자동 제외된다.
--
-- ---------------------------------------------------------------------------
-- 대상 컬럼 (2026-07-31 리허설 실측, 구 prod 데이터 기준)
--
--   users.profile_image             645 건
--   item_snapshots.image_url        196 건
--   notifications.actor_image_url    32 건
--   announcements.body                0 건   (마크다운 본문 내 이미지 — 현재 0 이나 향후 대비)
--                                   ─────
--                                    873 건
--
-- items.image_url 은 대상이 아니다 — 버저닝 분리(#362, V20260606201416)로 이미 drop 된
-- 컬럼이다. 이미지는 item_snapshots 만 들고 있다.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 재작성
--    WHERE 절로 대상 행만 건드려 affected rows 가 실제 치환 건수와 일치하게 한다.
--    REPLACE 는 한 컬럼 안의 모든 출현을 바꾸므로 announcements.body 처럼 URL 이
--    여러 개 박힌 마크다운도 한 번에 처리된다.
-- ---------------------------------------------------------------------------

UPDATE users
   SET profile_image = REPLACE(profile_image, '250758375457', '996918499382')
 WHERE profile_image LIKE '%250758375457%';

UPDATE item_snapshots
   SET image_url = REPLACE(image_url, '250758375457', '996918499382')
 WHERE image_url LIKE '%250758375457%';

UPDATE notifications
   SET actor_image_url = REPLACE(actor_image_url, '250758375457', '996918499382')
 WHERE actor_image_url LIKE '%250758375457%';

UPDATE announcements
   SET body = REPLACE(body, '250758375457', '996918499382')
 WHERE body LIKE '%250758375457%';

-- ---------------------------------------------------------------------------
-- 2. 검증 — remaining 이 전부 0 이어야 한다
--    하나라도 0 이 아니면 재작성이 끝나지 않은 것이므로 freeze 를 해제하지 않는다.
-- ---------------------------------------------------------------------------

SELECT 'users.profile_image'           AS target, COUNT(*) AS remaining FROM users          WHERE profile_image   LIKE '%250758375457%'
UNION ALL
SELECT 'item_snapshots.image_url',           COUNT(*) FROM item_snapshots WHERE image_url       LIKE '%250758375457%'
UNION ALL
SELECT 'notifications.actor_image_url',      COUNT(*) FROM notifications  WHERE actor_image_url LIKE '%250758375457%'
UNION ALL
SELECT 'announcements.body',                 COUNT(*) FROM announcements  WHERE body            LIKE '%250758375457%';

-- ---------------------------------------------------------------------------
-- 3. 참고 — 새 계정 URL 반영 건수 (위 "대상 컬럼" 실측치와 대조)
-- ---------------------------------------------------------------------------

SELECT 'users.profile_image'           AS target, COUNT(*) AS rewritten FROM users          WHERE profile_image   LIKE '%996918499382%'
UNION ALL
SELECT 'item_snapshots.image_url',           COUNT(*) FROM item_snapshots WHERE image_url       LIKE '%996918499382%'
UNION ALL
SELECT 'notifications.actor_image_url',      COUNT(*) FROM notifications  WHERE actor_image_url LIKE '%996918499382%'
UNION ALL
SELECT 'announcements.body',                 COUNT(*) FROM announcements  WHERE body            LIKE '%996918499382%';

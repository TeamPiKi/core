-- 부하테스트 합성 시드 (#911) — Flyway 가 스키마를 만든 뒤(첫 배포 후) 적재한다.
--
-- 볼륨: users 2,000 / items 50,000 / item_snapshots 100,000(item 당 v1·v2, 전부 READY)
--       / wishes 90,000(user 당 45)
--
-- 정합 규칙 (어기면 조회가 500):
--   * wish.snapshot_id → item_snapshots.id → items.id 체인이 전부 실재 + deleted_at IS NULL
--   * READY snapshot 은 name·price·image_url·extracted_at 4개 non-null (READY 불변식)
--   * source='SERVER' — NULL 이면 표시값 파생·가격 이력에서 제외된다
--   * PENDING/PROCESSING 을 하나도 남기지 않는다 — 남기면 디스패처가 즉시 extractor 호출을 시작한다
--   * users.id 는 BINARY(16) big-endian: UUID_TO_BIN(uuid) (swap_flag 0). swap_flag 1 금지
--   * 위시 부하 유저는 identity_type='MEMBER' (GUEST 는 위시 API 403)
--   * nickname 은 ASCII 'lt' prefix — 게스트 자동 닉네임 풀(한글 조합)과 유니크 충돌 방지
--
-- 실행: docker exec -i piki-loadtest-mysql mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < seed.sql

SET SESSION cte_max_recursion_depth = 100001;

-- 1) users — 2,000명, 가입일을 과거 90일에 분산
INSERT INTO users (id, nickname, identity_type, created_at, updated_at)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 2000)
SELECT UUID_TO_BIN(UUID()),
       CONCAT('lt', LPAD(i, 6, '0')),
       'MEMBER',
       NOW(6) - INTERVAL (i MOD 90) DAY,
       NOW(6) - INTERVAL (i MOD 90) DAY
FROM n;

-- 2) items — 50,000개. source_url 만 채운다(source_image_key 와 XOR 불변식).
--    도메인은 실재하지 않는 팀 소유 서브도메인 — 갱신(refresh) 시나리오가 등록 경계를 타도
--    실제 외부 fetch 로 새지 않는다(파싱은 어차피 stub 이 받는다).
INSERT INTO items (source_url, created_at, updated_at)
WITH RECURSIVE n AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM n WHERE i < 50000)
SELECT CONCAT('https://loadtest.piki.day/products/', i),
       NOW(6) - INTERVAL (i MOD 90) DAY,
       NOW(6) - INTERVAL (i MOD 90) DAY
FROM n;

-- 3) item_snapshots — item 당 2버전. v1 전체를 먼저, v2 전체를 나중에 넣어
--    "v2 의 id 가 항상 크다"를 보장한다(표시값 파생이 max(id) 기계 READY 를 고르므로,
--    목록·상세가 v2 가격을 보여주고 가격 이력에는 v1→v2 변동이 잡힌다).
INSERT INTO item_snapshots (item_id, name, image_url, price, currency, status, extracted_at, source, created_at, updated_at)
SELECT it.id,
       CONCAT('부하테스트 상품 ', it.id),
       CONCAT('https://example.com/loadtest/', it.id, '.png'),
       10000 + ((it.id * 37) MOD 90000),
       'KRW', 'READY',
       NOW(6) - INTERVAL 61 DAY,
       'SERVER',
       NOW(6) - INTERVAL 61 DAY,
       NOW(6) - INTERVAL 61 DAY
FROM items it
WHERE it.source_url LIKE 'https://loadtest.piki.day/%';

INSERT INTO item_snapshots (item_id, name, image_url, price, currency, status, extracted_at, source, created_at, updated_at)
SELECT it.id,
       CONCAT('부하테스트 상품 ', it.id),
       CONCAT('https://example.com/loadtest/', it.id, '.png'),
       10000 + ((it.id * 53 + 977) MOD 90000),   -- v1 과 다른 가격 → 이력 변동 생성
       'KRW', 'READY',
       NOW(6) - INTERVAL 31 DAY,
       'SERVER',
       NOW(6) - INTERVAL 31 DAY,
       NOW(6) - INTERVAL 31 DAY
FROM items it
WHERE it.source_url LIKE 'https://loadtest.piki.day/%';

-- 4) wishes — user 당 45개. 포인터는 전체 snapshot 에서 의사난수로 고른다(소수 계수로 분산).
--    일부는 v1(옛 포인터)을 가리켜 표시값 파생(최신 기계 READY 대체) 경로도 실제로 돈다.
--    (user, snapshot) 중복은 유니크가 없어 허용 — 목록에 중복 카드로 뜰 뿐 무해하다.
INSERT INTO wishes (user_id, snapshot_id, memo, created_at, updated_at)
SELECT u.id,
       s.id,
       CASE WHEN (u.rn + j.j) MOD 5 = 0 THEN '부하테스트 메모' ELSE NULL END,
       NOW(6) - INTERVAL ((u.rn * 13 + j.j * 7) MOD 60) DAY,
       NOW(6) - INTERVAL ((u.rn * 13 + j.j * 7) MOD 60) DAY
FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn
      FROM users WHERE nickname LIKE 'lt%') u
JOIN (WITH RECURSIVE jj AS (SELECT 0 AS j UNION ALL SELECT j + 1 FROM jj WHERE j < 44)
      SELECT j FROM jj) j
JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM item_snapshots) s
  ON s.rn = 1 + ((u.rn * 7919 + j.j * 104729) MOD 100000);

ANALYZE TABLE users, items, item_snapshots, wishes;

-- 검증 셀렉트 — 실행 후 눈으로 확인한다
SELECT 'users' AS t, COUNT(*) AS cnt FROM users WHERE nickname LIKE 'lt%'
UNION ALL SELECT 'items', COUNT(*) FROM items
UNION ALL SELECT 'item_snapshots(READY)', COUNT(*) FROM item_snapshots WHERE status = 'READY'
UNION ALL SELECT 'item_snapshots(비READY, 0이어야)', COUNT(*) FROM item_snapshots WHERE status <> 'READY'
UNION ALL SELECT 'wishes', COUNT(*) FROM wishes
UNION ALL SELECT '고아 wish(0이어야)', COUNT(*)
  FROM wishes w LEFT JOIN item_snapshots s ON s.id = w.snapshot_id WHERE s.id IS NULL;

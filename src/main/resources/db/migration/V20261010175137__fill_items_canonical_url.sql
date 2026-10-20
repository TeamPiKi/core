-- canonical_url 이 빈 옛 아이템을 채우고 NOT NULL 로 올린다. 채울 수 없는 행은 그 흔적까지 지운다

-- 1. 이미지 키 칸이 생기기 전의 이미지 등록 아이템. 버전이 가리키는 S3 객체 키가 정체
UPDATE items i
JOIN (
    SELECT s.item_id, MAX(s.id) AS id
    FROM item_snapshots s
    WHERE s.image_url LIKE 'https://piki-images-%.amazonaws.com/%'
    GROUP BY s.item_id
) latest ON latest.item_id = i.id
JOIN item_snapshots s ON s.id = latest.id
SET i.canonical_url = SUBSTRING_INDEX(SUBSTRING_INDEX(s.image_url, '.amazonaws.com/', -1), '?', 1),
    i.source_type   = 'IMAGE'
WHERE i.canonical_url IS NULL AND i.source_url IS NULL;

-- 2. 같은 등록 원문으로 여러 번 만들어진 아이템은 첫 아이템으로 합친다
-- 그 원문을 이미 정체로 가진 아이템이 있으면 그쪽에 합친다. 파싱 중 경합으로 생긴 빈 아이템에 원문을 채우면 정체 유니크에 걸린다
-- 정체 주인은 따로 뽑아 id 로 붙인다. 원문(대소문자 무시)과 정체(바이트) 비교를 한 조인에 이으면 옵티마이저가 조건을 옮겨 묶음마다 결과가 갈린다
CREATE TEMPORARY TABLE canonical_holders AS
SELECT b.id AS item_id, c.id AS holder
FROM items b
JOIN items c ON c.canonical_url = b.source_url
WHERE b.canonical_url IS NULL;

CREATE TEMPORARY TABLE merged AS
SELECT a.id AS loser, COALESCE(MIN(h.holder), MIN(b.id)) AS winner
FROM items a
JOIN items b ON b.source_url = a.source_url AND b.canonical_url IS NULL
LEFT JOIN canonical_holders h ON h.item_id = b.id
WHERE a.canonical_url IS NULL AND a.source_url IS NOT NULL
GROUP BY a.id
HAVING loser <> winner;

DROP TEMPORARY TABLE canonical_holders;

UPDATE wishes w JOIN merged m ON m.loser = w.item_id SET w.item_id = m.winner;
UPDATE tournament_items t JOIN merged m ON m.loser = t.item_id SET t.item_id = m.winner;
UPDATE item_snapshots s JOIN merged m ON m.loser = s.item_id SET s.item_id = m.winner;

-- 합친 뒤 한 사용자가 같은 아이템을 두 번 담은 꼴이 되면 나중 것을 지운다
UPDATE wishes w
JOIN (
    SELECT w2.user_id, w2.item_id, MIN(w2.id) AS keep
    FROM wishes w2
    JOIN merged m ON m.winner = w2.item_id
    WHERE w2.deleted_at IS NULL
    GROUP BY w2.user_id, w2.item_id
    HAVING COUNT(*) > 1
) d ON d.user_id = w.user_id AND d.item_id = w.item_id AND w.id <> d.keep
SET w.deleted_at = NOW(6)
WHERE w.deleted_at IS NULL;

DELETE i FROM items i JOIN merged m ON m.loser = i.id;
DROP TEMPORARY TABLE merged;

-- 3. 등록 원문이 정체. 정체 칸(768자)에 들어가지 않는 원문은 지금의 등록 상한도 넘어 4단계로 보낸다
UPDATE items SET canonical_url = source_url WHERE canonical_url IS NULL AND source_url IS NOT NULL AND CHAR_LENGTH(source_url) <= 768;

-- 4. 남은 행은 정체를 만들 재료가 없어 흔적째 지운다. 이미지 키 칸이 생기기 전 이미지 등록 중 결과 이미지도 남지 않은 것과 768자를 넘는 원문
CREATE TEMPORARY TABLE doomed_items AS SELECT id FROM items WHERE canonical_url IS NULL;
CREATE TEMPORARY TABLE doomed_wishes AS SELECT w.id FROM wishes w JOIN doomed_items d ON d.id = w.item_id;
CREATE TEMPORARY TABLE doomed_entries AS SELECT t.id, t.tournament_id FROM tournament_items t JOIN doomed_items d ON d.id = t.item_id;
-- 우승 출전이 지워지는 토너먼트는 결과가 남을 수 없어 통째로 지운다
CREATE TEMPORARY TABLE doomed_tournaments AS
SELECT DISTINCT h.tournament_id FROM tournament_histories h JOIN doomed_entries e ON e.id = h.selected_tournament_item_id WHERE h.current_round = 2;

DELETE n FROM notifications n JOIN doomed_wishes w ON w.id = n.wish_id;
DELETE n FROM notifications n JOIN doomed_entries e ON e.id = n.tournament_item_id;
DELETE n FROM notifications n JOIN doomed_tournaments dt ON dt.tournament_id = n.tournament_id;
DELETE e FROM wish_external_entries e JOIN doomed_wishes w ON w.id = e.wish_id;
DELETE w FROM wishes w JOIN doomed_wishes d ON d.id = w.id;

DELETE h FROM tournament_histories h JOIN doomed_tournaments dt ON dt.tournament_id = h.tournament_id;
DELETE h FROM tournament_histories h JOIN doomed_entries e ON e.id IN (h.first_tournament_item_id, h.second_tournament_item_id);
DELETE t FROM tournament_items t JOIN doomed_tournaments dt ON dt.tournament_id = t.tournament_id;
DELETE t FROM tournament_items t JOIN doomed_entries e ON e.id = t.id;
DELETE tu FROM tournament_users tu JOIN doomed_tournaments dt ON dt.tournament_id = tu.tournament_id;
DELETE tr FROM tournaments tr JOIN doomed_tournaments dt ON dt.tournament_id = tr.id;

DELETE s FROM item_snapshots s JOIN doomed_items d ON d.id = s.item_id;
DELETE i FROM items i JOIN doomed_items d ON d.id = i.id;

DROP TEMPORARY TABLE doomed_tournaments;
DROP TEMPORARY TABLE doomed_entries;
DROP TEMPORARY TABLE doomed_wishes;
DROP TEMPORARY TABLE doomed_items;

ALTER TABLE items MODIFY COLUMN canonical_url VARCHAR(768) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

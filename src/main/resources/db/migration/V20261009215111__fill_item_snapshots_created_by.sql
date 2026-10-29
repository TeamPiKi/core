-- created_by 가 빈 옛 행을 채우고 NOT NULL 로 올린다. 이전 백필은 위시가 가리키는 버전만 채워 같은 아이템의 다른 버전이 남았다
UPDATE item_snapshots s
JOIN (
    SELECT w.item_id, MIN(w.id) AS first_wish_id
    FROM wishes w
    WHERE w.item_id IS NOT NULL
    GROUP BY w.item_id
) fw ON fw.item_id = s.item_id
JOIN wishes w ON w.id = fw.first_wish_id
SET s.created_by = w.user_id
WHERE s.created_by IS NULL;

UPDATE item_snapshots s
JOIN (
    SELECT t.item_id, MIN(t.id) AS first_tournament_item_id
    FROM tournament_items t
    WHERE t.item_id IS NOT NULL
    GROUP BY t.item_id
) ft ON ft.item_id = s.item_id
JOIN tournament_items t ON t.id = ft.first_tournament_item_id
SET s.created_by = t.user_id
WHERE s.created_by IS NULL;

-- 아무 위시·출전도 가리키지 않는 아이템의 버전은 볼 수 있는 화면이 없다
DELETE FROM item_snapshots WHERE created_by IS NULL;

ALTER TABLE item_snapshots MODIFY COLUMN created_by BINARY(16) NOT NULL;

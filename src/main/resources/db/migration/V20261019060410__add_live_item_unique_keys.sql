-- 같은 사용자가 같은 상품을 두 번 담은 위시는 먼저 담은 쪽에 합친다. 파싱 성공의 합치기와 같은 규칙
CREATE TEMPORARY TABLE duplicate_wishes AS
SELECT w.id AS loser, d.keep
FROM wishes w
JOIN (
    SELECT user_id, item_id, MIN(id) AS keep
    FROM wishes
    WHERE deleted_at IS NULL AND item_id IS NOT NULL
    GROUP BY user_id, item_id
    HAVING COUNT(*) > 1
) d ON d.user_id = w.user_id AND d.item_id = w.item_id AND w.id <> d.keep
WHERE w.deleted_at IS NULL;

INSERT INTO wish_memos (wish_id, content, written_at, created_at, updated_at)
SELECT d.keep, m.content, m.written_at, NOW(6), NOW(6)
FROM wish_memos m
JOIN duplicate_wishes d ON d.loser = m.wish_id;

UPDATE wishes w JOIN duplicate_wishes d ON d.loser = w.id SET w.deleted_at = NOW(6);

DROP TEMPORARY TABLE duplicate_wishes;

-- 지운 슬롯은 live 가 NULL 이라 유니크 검사에서 빠진다. 0 으로 두면 지운 슬롯끼리 겹친다
ALTER TABLE wishes
    ADD COLUMN live TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL,
    ADD UNIQUE KEY uq_wishes_user_id_item_id_live (user_id, item_id, live);

-- 버전을 고정하던 옛 출전(snapshot_id 있음)은 검사에서 뺀다. 끝난 토너먼트의 대진 기록이라 겹쳐도 지울 수 없다
ALTER TABLE tournament_items
    ADD COLUMN live TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL AND snapshot_id IS NULL, 1, NULL)) VIRTUAL,
    ADD UNIQUE KEY uq_tournament_items_tournament_id_item_id_live (tournament_id, item_id, live);

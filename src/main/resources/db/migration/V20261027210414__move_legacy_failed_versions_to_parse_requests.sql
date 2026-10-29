-- 옛 큐는 실패·대기를 값 없는 버전 행으로 남겼다. 버전은 파싱 성공 때만 생기므로 이 행이 남으면 실패 카드가 빈 INCOMPLETE 로 보인다
-- 볼 수 있는 버전이 없는 카드는 지금 모델의 실패(옛 대기는 대기) 요청으로 옮기고, 옛 행과 버전 없는 상품은 지운다

-- 판정은 카드 주인 기준이다. 공유 상품에 남이 직접 넣은 버전만 있으면 그 값이 내 카드에 보인다
CREATE TEMPORARY TABLE stranded_wishes AS
SELECT w.id AS slot_id, w.item_id, w.deleted_at IS NULL AS live
FROM wishes w
WHERE w.item_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM item_snapshots s
    WHERE s.item_id = w.item_id
      AND s.deleted_at IS NULL
      AND (s.status IS NULL OR s.status NOT IN ('FAILED', 'PENDING', 'PROCESSING'))
      AND (s.user_input = FALSE OR s.created_by = w.user_id)
  );

CREATE TEMPORARY TABLE stranded_tournament_items AS
SELECT t.id AS slot_id, t.item_id, t.deleted_at IS NULL AND tr.deleted_at IS NULL AS live
FROM tournament_items t
JOIN tournaments tr ON tr.id = t.tournament_id
WHERE t.item_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM item_snapshots s
    WHERE s.item_id = t.item_id
      AND s.deleted_at IS NULL
      AND (s.status IS NULL OR s.status NOT IN ('FAILED', 'PENDING', 'PROCESSING'))
      AND (s.user_input = FALSE OR s.created_by = t.user_id)
  );

CREATE TEMPORARY TABLE stranded_inputs AS
SELECT i.id AS item_id,
       i.source_type AS input_type,
       IF(i.source_type = 'LINK' AND CHAR_LENGTH(i.source_url) <= 768, i.source_url, i.canonical_url) AS input_value,
       -- 지금의 정규화를 SQL 로 다시 만들 수 없어 옛 정체로 대신한다. 같은 상품만 같아질 수 있어 잘못된 409 는 없다
       i.canonical_url AS normalized_input,
       last_legacy.status AS last_status,
       COALESCE(last_legacy.created_at, i.created_at) AS requested_at,
       COALESCE(last_legacy.updated_at, i.updated_at) AS settled_at
FROM items i
LEFT JOIN (
    SELECT item_id, MAX(id) AS id
    FROM item_snapshots
    WHERE status IN ('FAILED', 'PENDING', 'PROCESSING')
    GROUP BY item_id
) latest ON latest.item_id = i.id
LEFT JOIN item_snapshots last_legacy ON last_legacy.id = latest.id
WHERE i.id IN (SELECT item_id FROM stranded_wishes)
   OR i.id IN (SELECT item_id FROM stranded_tournament_items);

-- 가장 최근 요청은 id 로 고른다. 이미 요청이 있는 카드에 옛 요청을 더하면 그 뒤의 실제 요청을 가린다
-- 지운 카드도 옮기는 건 그 카드가 가리키던 상품을 지울 수 있어서다. 지운 카드는 다시 파싱하지 않는다
INSERT INTO item_parse_outbox (slot_type, slot_id, purpose, input_type, input_value, normalized_input, status, created_at, updated_at)
SELECT 'WISH', sw.slot_id, 'REGISTER', si.input_type, si.input_value, si.normalized_input,
       IF(sw.live AND si.last_status IN ('PENDING', 'PROCESSING'), 'PENDING', 'FAILED'),
       si.requested_at, si.settled_at
FROM stranded_wishes sw
JOIN stranded_inputs si ON si.item_id = sw.item_id
WHERE NOT EXISTS (SELECT 1 FROM item_parse_outbox o WHERE o.slot_type = 'WISH' AND o.slot_id = sw.slot_id);

INSERT INTO item_parse_outbox (slot_type, slot_id, purpose, input_type, input_value, normalized_input, status, created_at, updated_at)
SELECT 'TOURNAMENT_ITEM', st.slot_id, 'REGISTER', si.input_type, si.input_value, si.normalized_input,
       IF(st.live AND si.last_status IN ('PENDING', 'PROCESSING'), 'PENDING', 'FAILED'),
       si.requested_at, si.settled_at
FROM stranded_tournament_items st
JOIN stranded_inputs si ON si.item_id = st.item_id
WHERE NOT EXISTS (SELECT 1 FROM item_parse_outbox o WHERE o.slot_type = 'TOURNAMENT_ITEM' AND o.slot_id = st.slot_id);

-- 상품 행이 없어 요청을 만들지 못한 카드까지 비우면 상품도 요청도 없는 카드가 된다
UPDATE wishes w
JOIN stranded_wishes sw ON sw.slot_id = w.id
SET w.item_id = NULL
WHERE EXISTS (SELECT 1 FROM item_parse_outbox o WHERE o.slot_type = 'WISH' AND o.slot_id = w.id);

UPDATE tournament_items t
JOIN stranded_tournament_items st ON st.slot_id = t.id
SET t.item_id = NULL
WHERE EXISTS (SELECT 1 FROM item_parse_outbox o WHERE o.slot_type = 'TOURNAMENT_ITEM' AND o.slot_id = t.id);

DROP TEMPORARY TABLE stranded_inputs;
DROP TEMPORARY TABLE stranded_tournament_items;
DROP TEMPORARY TABLE stranded_wishes;

-- 성공 버전이 있는 상품의 옛 행은 실패한 새로고침이다. 지금은 요청에만 남고 버전이 되지 않는다
DELETE FROM item_snapshots WHERE status IN ('FAILED', 'PENDING', 'PROCESSING');

-- 버전이 남지 않은 상품을 가리키던 카드는 위에서 모두 옮겼다
CREATE TEMPORARY TABLE versionless_items AS
SELECT i.id
FROM items i
WHERE NOT EXISTS (SELECT 1 FROM item_snapshots s WHERE s.item_id = i.id AND s.deleted_at IS NULL);

DELETE s FROM item_snapshots s JOIN versionless_items v ON v.id = s.item_id;
DELETE i FROM items i JOIN versionless_items v ON v.id = i.id;

DROP TEMPORARY TABLE versionless_items;

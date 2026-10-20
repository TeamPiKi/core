-- 시작 시각 칸이 생기기 전에 시작한 토너먼트의 출전 값을 고정한다. 비워 두면 새 버전이 생길 때마다 가격이 바뀌어 진행 중 대진이 어긋난다.
-- 실제 시작 시각은 남아 있지 않다. 옛 출전 값은 출전이 가리키던 버전이었으므로 그 버전들 중 가장 늦은 생성 시각으로 채운다.
-- 가리키던 버전이 없으면 그 아이템의 첫 버전 시각을 쓴다. 기준 시각까지 버전이 하나도 없는 출전이 생기면 카드를 계산할 수 없다.
UPDATE tournaments tr
JOIN (
    SELECT t.tournament_id, MAX(COALESCE(pin.created_at, earliest.created_at)) AS anchored_at
    FROM tournament_items t
    LEFT JOIN item_snapshots pin
        ON pin.id = t.snapshot_id AND pin.item_id = t.item_id AND pin.deleted_at IS NULL
    LEFT JOIN (
        SELECT item_id, MIN(created_at) AS created_at
        FROM item_snapshots
        WHERE deleted_at IS NULL
        GROUP BY item_id
    ) earliest ON earliest.item_id = t.item_id
    WHERE t.deleted_at IS NULL AND t.item_id IS NOT NULL
    GROUP BY t.tournament_id
) a ON a.tournament_id = tr.id
SET tr.started_at = a.anchored_at
WHERE tr.status <> 'PENDING' AND tr.started_at IS NULL AND a.anchored_at IS NOT NULL;

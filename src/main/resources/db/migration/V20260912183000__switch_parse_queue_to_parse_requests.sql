-- 작업 큐를 item_snapshots 에서 parse_requests 로 넘긴다(#1073 2단계). 이 배포부터 집기·회수·마감·소유권이 요청만 본다.
-- 두 문장 모두 전환 시점의 미완 작업을 정리하는 일회성 정합 맞춤이고, 이후 큐는 요청 행만으로 돈다.

-- 1) 진행 중이던 버전을 전부 종결한다. 배포로 앱이 교체되는 시점의 미완 작업이고, 어차피 마감(3분)이 곧 끊을 것들이다.
--    요청 행이 없는 버전은 새 스케줄러 눈에 안 보여 영구 정체가 되므로 여기서 끝낸다.
UPDATE item_snapshots
SET status = 'FAILED', updated_at = NOW(6)
WHERE status IN ('PENDING', 'PROCESSING');

-- 2) 1단계에서 적재만 기록해 둔 요청들의 상태를 결과 버전에서 백필한다. 안 맞추면 이미 끝난 작업의 요청이 PENDING 으로
--    남아 새 스케줄러가 다시 집는다. 위 문장이 비-터미널을 없앴으므로 모든 요청이 여기서 터미널로 닫힌다.
UPDATE parse_requests r
JOIN item_snapshots s ON s.id = r.result_snapshot_id
SET r.status = CASE WHEN s.status = 'FAILED' THEN 'FAILED' ELSE 'SUCCEEDED' END,
    r.failure_reason = CASE WHEN s.status = 'FAILED' THEN 'EXTRACTION' ELSE NULL END,
    r.attempt_count = s.attempt_count,
    r.finished_at = s.updated_at,
    r.updated_at = NOW(6)
WHERE r.status IN ('PENDING', 'PROCESSING');

-- 한 칸에 담겼던 사실 둘을 가른다. method 는 추출 계약 enum 그대로, user_input 은 사람이 넣었는지
ALTER TABLE item_snapshots ADD COLUMN user_input BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE item_snapshots SET user_input = TRUE, source = NULL WHERE source = 'MANUAL';
UPDATE item_snapshots SET source = 'STRUCTURED' WHERE source = 'SERVER';
UPDATE item_snapshots SET source = 'LLM' WHERE source = 'SERVER_LLM';

ALTER TABLE item_snapshots RENAME COLUMN source TO method;

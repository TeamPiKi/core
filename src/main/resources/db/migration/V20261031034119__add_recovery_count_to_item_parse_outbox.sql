ALTER TABLE item_parse_outbox
    ADD COLUMN recovery_count INT NOT NULL DEFAULT 0;

-- 既存MANUAL/ORDER履歴のmetadataはNULLのまま。推測key/backfillやTTLを導入しない。
ALTER TABLE inventory_movements
    ADD COLUMN idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT uk_inventory_movements_actor_idempotency_key UNIQUE (actor_user_id, idempotency_key),
    ADD CONSTRAINT ck_inventory_movement_idempotency_pair CHECK (
        (idempotency_key IS NULL AND request_fingerprint IS NULL)
        OR (idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL)),
    ADD CONSTRAINT ck_inventory_movement_order_idempotency CHECK (
        source_type <> 'ORDER' OR (idempotency_key IS NULL AND request_fingerprint IS NULL)),
    ADD CONSTRAINT ck_inventory_movement_fingerprint CHECK (
        request_fingerprint IS NULL OR BINARY request_fingerprint REGEXP '^[0-9a-f]{64}$');

-- legacyは両列NULLのまま保持。opaque keyはcase-sensitiveで比較する。
ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT uk_orders_customer_idempotency_key UNIQUE (customer_id, idempotency_key);

-- 既存stockから過去の履歴/initial snapshotを推測しない。導入後の変動だけを保存する。
CREATE TABLE inventory_movements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    delta INT NOT NULL,
    stock_before INT NOT NULL,
    stock_after INT NOT NULL,
    reason VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    source_type VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    source_id BIGINT NULL,
    actor_user_id BIGINT NOT NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_inventory_movements_product_created_id (product_id, created_at, id),
    INDEX idx_inventory_movements_source (source_id),
    CONSTRAINT fk_inventory_movement_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_inventory_movement_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT fk_inventory_movement_order FOREIGN KEY (source_id) REFERENCES orders (id),
    CONSTRAINT ck_inventory_movement_delta CHECK (delta <> 0),
    -- INT operands are explicitly widened; their sum fits signed BIGINT.
    CONSTRAINT ck_inventory_movement_stocks CHECK (stock_before >= 0 AND stock_after >= 0
        AND CAST(stock_after AS SIGNED) = CAST(stock_before AS SIGNED) + CAST(delta AS SIGNED)),
    CONSTRAINT ck_inventory_movement_reason CHECK (reason IN
        ('ORDER_CREATED','ORDER_CANCELLED','RECEIPT','DAMAGE','STOCKTAKE','CORRECTION')),
    CONSTRAINT ck_inventory_movement_source CHECK (
        (source_type = 'ORDER' AND source_id IS NOT NULL AND note IS NULL
            AND ((reason = 'ORDER_CREATED' AND delta < 0) OR (reason = 'ORDER_CANCELLED' AND delta > 0)))
        OR (source_type = 'MANUAL' AND source_id IS NULL AND reason IN ('RECEIPT','DAMAGE','STOCKTAKE','CORRECTION')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

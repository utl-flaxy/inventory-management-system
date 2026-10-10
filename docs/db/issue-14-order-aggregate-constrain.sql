-- expandと確認済みデータの補完が完了してから適用する。--forceは使用しない。
-- 不完全な履歴、孤児明細、空注文、合計不一致があればDDL前に停止する。
DROP TEMPORARY TABLE IF EXISTS issue14_order_aggregate_preflight;
CREATE TEMPORARY TABLE issue14_order_aggregate_preflight (
    ok INT NOT NULL CHECK (ok = 1)
);
INSERT INTO issue14_order_aggregate_preflight (ok)
SELECT CASE WHEN
    EXISTS (
        SELECT 1 FROM orders o LEFT JOIN users u ON u.id = o.customer_id
        WHERE u.id IS NULL OR o.ordered_at IS NULL OR o.updated_at IS NULL
            OR o.version IS NULL OR o.total_price IS NULL
    ) OR EXISTS (
        SELECT 1 FROM order_items i
        LEFT JOIN orders o ON o.id = i.order_id
        LEFT JOIN products p ON p.id = i.product_id
        WHERE o.id IS NULL OR p.id IS NULL OR i.product_name_snapshot IS NULL
            OR i.price IS NULL OR i.price < 0 OR i.quantity IS NULL OR i.quantity <= 0
    ) OR EXISTS (
        SELECT o.id FROM orders o LEFT JOIN order_items i ON i.order_id = o.id
        GROUP BY o.id
        HAVING COUNT(i.id) = 0 OR MAX(o.total_price) <> SUM(i.price * i.quantity)
    ) THEN 0 ELSE 1 END;
DROP TEMPORARY TABLE issue14_order_aggregate_preflight;

ALTER TABLE orders
    MODIFY COLUMN customer_id BIGINT NOT NULL,
    MODIFY COLUMN ordered_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0,
    MODIFY COLUMN total_price INT NOT NULL,
    ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES users(id);

ALTER TABLE order_items
    MODIFY COLUMN product_name_snapshot VARCHAR(255) NOT NULL,
    MODIFY COLUMN price INT NOT NULL,
    MODIFY COLUMN order_id BIGINT NOT NULL,
    MODIFY COLUMN product_id BIGINT NOT NULL;

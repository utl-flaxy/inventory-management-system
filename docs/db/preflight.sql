-- 既存DB専用のデータ検証。書き込み停止・backup後、対象DBを確認して実行する。
-- 永続テーブル/データを変更しない。1件でも違反があればCHECKで停止する。
-- --forceは禁止。成功してもschema照合なしにbaselineしない。
DROP TEMPORARY TABLE IF EXISTS issue15_data_preflight;
CREATE TEMPORARY TABLE issue15_data_preflight (
    check_name VARCHAR(80) NOT NULL,
    violations BIGINT NOT NULL,
    CONSTRAINT ck_issue15_no_violations CHECK (violations = 0)
);
INSERT INTO issue15_data_preflight (check_name, violations)
SELECT 'users required fields / role', COUNT(*) FROM users
WHERE username IS NULL OR password IS NULL OR role IS NULL
   OR CHAR_LENGTH(username) > 255 OR CHAR_LENGTH(password) > 255
   OR BINARY role NOT IN ('ROLE_USER', 'ROLE_ADMIN')
UNION ALL
SELECT 'duplicate username', COUNT(*) FROM
    (SELECT username FROM users GROUP BY username HAVING COUNT(*) > 1) duplicates
UNION ALL
SELECT 'products required fields / nonnegative values', COUNT(*) FROM products
WHERE name IS NULL OR CHAR_LENGTH(name) > 255 OR price IS NULL OR price < 0 OR stock IS NULL OR stock < 0 OR version IS NULL
UNION ALL
SELECT 'orders required fields / customer / status / total', COUNT(*) FROM orders o
LEFT JOIN users u ON u.id = o.customer_id
WHERE u.id IS NULL OR o.total_price IS NULL OR o.total_price < 0 OR o.status IS NULL
   OR BINARY o.status NOT IN ('PENDING', 'SHIPPED', 'COMPLETED', 'CANCELLED')
   OR o.ordered_at IS NULL OR o.updated_at IS NULL OR o.version IS NULL
UNION ALL
SELECT 'items required fields / quantity / price / references', COUNT(*) FROM order_items i
LEFT JOIN orders o ON o.id = i.order_id LEFT JOIN products p ON p.id = i.product_id
WHERE o.id IS NULL OR p.id IS NULL OR i.quantity IS NULL OR i.quantity <= 0
   OR i.price IS NULL OR i.price < 0 OR i.product_name_snapshot IS NULL OR CHAR_LENGTH(i.product_name_snapshot) > 255
UNION ALL
SELECT 'empty order / total mismatch', COUNT(*) FROM (
    SELECT o.id FROM orders o LEFT JOIN order_items i ON i.order_id = o.id
    GROUP BY o.id, o.total_price
    HAVING COUNT(i.id) = 0 OR o.total_price <> SUM(i.price * i.quantity)
) inconsistent_orders;
DROP TEMPORARY TABLE issue15_data_preflight;

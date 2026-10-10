-- 完了日時不明の既存COMPLETED注文は推測補完せず、売上集計から除外する。
ALTER TABLE orders ADD COLUMN completed_at DATETIME(6) NULL;
-- MariaDB 12.2.2で期間queryのEXPLAINを確認（docs/sales.md）。
CREATE INDEX idx_orders_status_completed_at ON orders (status, completed_at);

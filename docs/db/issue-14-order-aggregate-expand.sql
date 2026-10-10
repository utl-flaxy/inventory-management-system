-- MariaDB限定。書き込み停止・バックアップ後、新アプリ起動前に適用する。
-- 注文者・日時・商品名は推測で埋めない。手順書に従い確認済みデータで補完する。
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS customer_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS ordered_at DATETIME(6) NULL,
    ADD COLUMN IF NOT EXISTS updated_at DATETIME(6) NULL,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS product_name_snapshot VARCHAR(255) NULL;

-- 注文時単価は既存price列を再利用する。unit_price列は追加しない。

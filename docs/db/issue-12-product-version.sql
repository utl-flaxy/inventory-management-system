-- MariaDB: 既存商品のversionを0で初期化。在庫・価格・数量には触れない。
ALTER TABLE products ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

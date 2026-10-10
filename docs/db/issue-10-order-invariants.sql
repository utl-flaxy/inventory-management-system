-- Issue #10: 既存MariaDB向け。適用前にREADME.mdの手順を確認する。
-- CHECKを先に追加し、既存の不正行を暗黙補正せずに拒否する。
ALTER TABLE products
    ADD CONSTRAINT ck_products_stock_nonnegative CHECK (stock IS NOT NULL AND stock >= 0);
ALTER TABLE products MODIFY COLUMN stock INT NOT NULL;

ALTER TABLE order_items
    ADD CONSTRAINT ck_order_items_quantity_positive CHECK (quantity IS NOT NULL AND quantity > 0);
ALTER TABLE order_items MODIFY COLUMN quantity INT NOT NULL;

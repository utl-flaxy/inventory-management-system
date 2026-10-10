# 注文・在庫のDB変更

この文書と同じディレクトリのSQLはIssue #10/#12/#14時点の履歴用アーカイブ。
現行schemaの定義・新規DB作成には使用しない。将来の変更をここへ追記しない。
既存DBを扱う場合も、まず[現行Flyway移行手順](../README.md)に従う。

Issue #14の既存注文者・日時・商品名スナップショットの補完と段階的SQLは
[Order集約の移行手順](../../orders/order-aggregate.md#既存dbの移行) を参照する。
新アプリ起動前にexpand→確認済みデータ補完→constrainの順で実施する。
既存price列を注文時単価として再利用し、unit_priceへの列名変更は行わない。

## Issue #10 のDB制約

新規スキーマにはEntityの `@Check` と `@Column(nullable = false)` から制約を生成する。
既存テーブルでは `ddl-auto=update` だけにCHECK追加を任せられないため、
`issue-10-order-invariants.sql` をアプリ更新前に適用する。Flyway導入は #15 で行う。
このSQLは自動実行されない。本PRで本番DBへの接続・適用は行わない。

1. DBをバックアップし、書き込みを停止する。
2. 次のSELECTで不正行を確認する。存在する場合は適用を止め、注文・在庫・売上を照合して個別に修復する。
   このSQLは既存データを自動削除・補正しない。

```sql
SELECT id, stock FROM products WHERE stock IS NULL OR stock < 0;
SELECT id, quantity FROM order_items WHERE quantity IS NULL OR quantity <= 0;
SHOW CREATE TABLE products;
SHOW CREATE TABLE order_items;
```

3. 対象DBを選択したMariaDBクライアントで `SOURCE docs/db/legacy/issue-10-order-invariants.sql;` を実行する。
   エラー時は中止する（`--force` は使わない）。DDLは暗黙コミットされるため、途中失敗時には
   `SHOW CREATE TABLE` で適用済みの制約を確認してから、未適用の文だけを実行する。
   新規スキーマなど同名CHECKが既にある場合も、該当のADD CONSTRAINT文は実行しない。
4. `SHOW CREATE TABLE` で `stock` / `quantity` のNOT NULLと上記の名前付きCHECKを確認し、アプリを更新する。

通常の回帰テストはJava 17で `./mvnw test`（Windows: `mvnw.cmd test`）。
`OrderDataIntegrityTest` はH2上で生SQLのINSERT/UPDATE拒否と注文の副作用を検証する。
MariaDBでも同じテストを実行できる。必ず破棄可能な専用テストDBを指定する。
テストは `create-drop` を使用するため、既存DB・本番DBには実行しない。

```text
./mvnw test -Dtest=OrderDataIntegrityTest -Dspring.test.database.replace=NONE -Dspring.datasource.url=jdbc:mariadb://127.0.0.1:13310/issue10_test -Dspring.datasource.username=root -Dspring.datasource.password=issue10-local-only
```

既存スキーマ用SQLの回帰テスト `OrderConstraintMigrationTest` は、通常はH2のMariaDBモードで実行する。
MariaDBで実行する場合は、別の空の専用テストDB `issue10_migration` を用意し、次のコマンドを使う。
テストは `products` / `order_items` を作成・削除するので、既存DB・本番DBは指定しない。

```text
./mvnw test -Dtest=OrderConstraintMigrationTest -Dissue10.migration.url=jdbc:mariadb://127.0.0.1:13310/issue10_migration -Dissue10.migration.username=root -Dissue10.migration.password=issue10-local-only
```

数量に固定の業務上限は設けない。既存のInteger金額を維持し、`price * quantity` が
`Integer.MAX_VALUE` を超える注文を在庫変更前に400で拒否する。型の表現範囲を超えたJSONも400になる。

CHECKの動作は [MariaDBの制約仕様](https://mariadb.com/docs/server/reference/sql-statements/data-definition/constraint)、
DDLの動作は [ALTER TABLE仕様](https://mariadb.com/docs/server/reference/sql-statements/data-definition/alter/alter-table) を参照。

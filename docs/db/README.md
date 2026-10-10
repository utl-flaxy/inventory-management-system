# FlywayによるDB管理（Issue #15）

DB schemaのsource of truthは `src/main/resources/db/migration`。
通常起動はSpring BootがFlyway migrateを実行した後、HibernateがEntityとの一致をvalidateする。
Hibernateはテーブルを作成・変更しない。Entity annotationはmapping/validation補助として使う。

## 構成

Spring Boot 3.5.12のdependency managementを利用し、`org.flywaydb:flyway-core` と
MariaDB対応の `org.flywaydb:flyway-mysql` を追加した。個別version固定は行わない（現在11.7.2）。
MariaDB JDBC driverは既存のものを使う。
[Bootの初期化仕様](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)と
[FlywayのMySQL/MariaDB module](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)に従う。

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=false
spring.flyway.clean-disabled=true
```

`baseline-on-migrate` は通常起動でも移行profileでも有効にしない。未確認の非空DBは起動を拒否する。
本番で `flyway clean` を実行しない。CLIのclean-disabledも無効化しない。

Boot管理のFlyway 11.7.2はMariaDB 12.2に上流での未検証警告を出す（同版の上流検証上限は11.2）。
本リポジトリではMariaDB 12.2.2実DBでmigration・validate・制約・再起動・業務回帰を必須検証する。
警告を隠したりMariaDBを下げたりせず、Boot管理とJava 17を維持する。上流による12.2認証を意味しない。

## Migration / Entity対応

| migration | 内容 |
| --- | --- |
| V1__baseline_schema.sql | 空DB用の全4テーブル、NOT NULL、CHECK、FK、UNIQUE、INDEX、version、日時、snapshot |
| V2__widen_money_columns_to_bigint.sql | products.price / orders.total_price / order_items.priceをINTからsigned BIGINTへ拡張。値・制約名は維持 |

| Entity / table | 必須列 | CHECK / UNIQUE / FK |
| --- | --- | --- |
| User / users | id, username, password, role | username UNIQUE、roleはROLE_USER/ROLE_ADMIN |
| Product / products | id, name, price, stock, version | stock>=0、price>=0、version DEFAULT 0 |
| Order / orders | id, customer_id, total_price, status, ordered_at, updated_at, version | total_price>=0、4状態のCHECK、customer→users、version DEFAULT 0 |
| OrderItem / order_items | id, quantity, price, product_name_snapshot, order_id, product_id | quantity>0、price>=0、order→orders、product→products |

全idはBIGINT AUTO_INCREMENT PK、全テーブルはInnoDB/utf8mb4。
注文の日時はDATETIME(6)。円整数の金額はJava Long/long・DB BIGINT、数量・在庫はInteger/INT。
明細の注文時単価は既存 `price` 列を維持し、`unit_price` へrenameしない。
DBは0円を許可し、商品APIの既存「1円以上」Validationは変更しない。
statusはVARCHAR(20)+CHECK。EntityもVARCHARを明示し、HibernateのMariaDB native ENUM生成に依存しない。
role/status列はutf8mb4_binで大小文字を区別し、`pending`/`role_user`等も拒否する。
状態またはroleを追加する場合は、CHECKを変更する新migrationとアプリ変更を一緒にレビューする。

| index | 対象 |
| --- | --- |
| uk_users_username（UNIQUE） | users(username) |
| idx_orders_customer | orders(customer_id) |
| idx_order_items_order | order_items(order_id) |
| idx_order_items_product | order_items(product_id) |

所有者一覧はcustomer index、id+customer条件の詳細/lockはid PKを使用できる。
実MariaDBのindex metadataとEXPLAINで確認し、冗長な(customer_id,id) indexは追加しない。
FK名はfk_orders_customer、fk_order_items_order、fk_order_items_product。削除cascadeは追加しない。

## 金額型のV1→V2移行（Issue #25）

V1はchecksum管理済みのため一切変更せず、V2の3本のMODIFY COLUMN BIGINT NOT NULLで拡張する。
既存のINT値は丸め・符号変更・NULL化をせず保持する。元の金額列にDEFAULTはなく、それも維持する。
非負CHECK、FK、index、他列のversion DEFAULT 0はMariaDB 12.2.2で移行前後を比較する。
legacy SQLのINT定義は歴史的fixtureであり、現行schema sourceではない。

商品APIは1円以上〜Long.MAX_VALUEを許可し、DBは従来どおり0円も許可する。
価格snapshot・明細/注文合計はlongのMath exact演算を使用し、multiply/add overflowを在庫更新前に400で拒否する。
SUM(totalPrice)のRepository/DTOはLong（空なら0L）。CANCELLEDを含む計上ルールは変更しない。
Money Value Object/BigDecimal、小数、税・割引、通貨コード、多通貨は未導入。

既存のFlyway管理済みV1 DBは、書き込み停止・backup・preflight・検証DBでの適用確認後に、
新artifactの通常migrateでV2を適用する。再baselineやV1再実行はしない。
3テーブルのDDLは暗黙commitされ、途中失敗が全体rollbackされるとは限らない。
移行に伴うlock時間・容量と各列/historyを確認し、失敗時は書き込みを再開せず、適用状況に応じて前進修復またはbackup復元を行う。
拡張後にInteger上限を超えるデータがあれば旧アプリ/INT列へ戻せない。自動でINTへ縮小するrollbackは用意しない。
この作業では本番DBに接続・適用しない。

## 新規の空DB

1. MariaDBに専用の空DBと必要権限を持つ接続ユーザーを作成する。
2. 対象を確認した `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` /
   `SPRING_DATASOURCE_PASSWORD` と `JWT_SECRET` を承認済みの秘密情報から設定する。
   接続情報をGitやshell履歴へ書かない。既存データがあるDBを空DBとして扱わない。
3. Java 17で `./mvnw spring-boot:run`（Windows: `mvnw.cmd spring-boot:run`）。
   jar/Composeによる通常起動でも同じmigrate→validateが動く。
4. `flyway_schema_history` のversion=1および2/type=SQL/success=1/checksumと、4テーブル・金額BIGINTを確認する。
   migrationがなければHibernate validateで起動に失敗する。
5. 再起動ではpending migrationがなければV1/V2を再実行せず、既存データを保持する。

## Flyway未管理の既存DB

既存DBへV1をそのまま実行しない。手動baselineはDDLを適用する操作ではなく、
「このDBは指定version相当である」と記録する操作である。
[Flyway baselineの仕様](https://documentation.red-gate.com/flyway/reference/commands/baseline)上、
version=1のbaseline後はV1までが実行対象から除外される。
以下をすべて完了するまでbaselineしない。本PRでは本番への接続・適用を行わない。

1. **停止・backup**: アプリと全書き込みを停止し、schema/data/権限と復元可能なbackupを取得する。
   DB名・接続先・MariaDB version・SQL mode・timezone（日時の補完時はUTC）を確認する。
2. **schema確認**: 全4テーブルのSHOW CREATE TABLE、列型/長さ/照合順序、AUTO_INCREMENT、NOT NULL、
   DEFAULT、CHECK式、FK、UNIQUE、INDEXをV1と比較する。schema_historyの有無も確認する。
   Flyway管理済みDBに再baselineしない。別の履歴がある場合は作業を止めて確認する。
3. **data preflight**: `docs/db/preflight.sql` を対象DBで実行し、SQLエラーがあれば停止する。
   このscriptはTEMPORARY TABLEだけを使い、違反件数>0をCHECKで拒否する。
   負在庫・非正数量・負金額・NULL・不正role/status・重複username・孤児FK・空注文・合計不一致・
   未補完customer/日時/snapshot・文字列長超過を検査する。`--force`やエラー継続は使わない。
   必要列自体がなければSQLエラーになるため、後述の旧DB手順で列を追加してから再実行する。
4. **確認済みの補完とschema整合**: 差分に応じたDDL/補完SQLを作成し、PR reviewと復元手順を確認する。
   customer・注文日時・更新日時・注文時商品名は信頼できる履歴から復元する。
   現在のユーザー/時刻/商品名を自動代入しない。復元不能なら停止して業務判断を求める。
   価格snapshot・数量・在庫・注文合計をschema移行の都合で書き換えない。
   古いENUMのstatusは、検証済みの値を保ってV1と同じVARCHAR(20)/照合順序/CHECKへ変更する。
   nullableな商品名/価格、足りない金額/role/status CHECK、FK/indexもV1に照合して整える。
   DDLはMariaDBで暗黙commitするため、適用順と途中失敗時の再開方法を個別にレビューする。
5. **再照合**: preflightを再実行して成功を確認し、SHOW CREATE TABLEとinformation_schemaを再照合する。
   EntityのvalidateだけではCHECK/FK/indexや全データの一致は保証できない。
   別の空の検証DBをV1で作成し、schema差分を比較する方法も使う。
6. **手動baseline**: 同じFlyway 11.7.2のCLIを用意し、承認済みの接続情報を
   `FLYWAY_URL` / `FLYWAY_USER` / `FLYWAY_PASSWORD` 等の秘密情報管理から渡す。
   `FLYWAY_LOCATIONS=filesystem:src/main/resources/db/migration` として、対象を再確認する。

```text
flyway -baselineVersion=1 -baselineDescription="verified existing schema" baseline
flyway validate
flyway info
```

7. **起動と検証**: 通常設定の新アプリを起動し、baseline済みV1相当schemaへV2が適用され、
   金額BIGINTでのHibernate validate成功、pendingなし、
   件数・在庫・合計・customer・日時・snapshot・制約を確認する。成功後に書き込みを再開する。

手動baselineの履歴はtype=BASELINEで、SQL migrationのchecksumは持たない。
V1のCHECK/FK/indexの正しさをFlyway baseline/validateが自動認定するわけではない。
既存DBがschema不一致のままならbaselineしてはいけない。

## 暫定SQLの扱い

Issue #10/#12/#14の4本は `docs/db/legacy` に内容を保ったまま移動した。
過去の限定的なupgrade手順・回帰fixtureとして保存し、現行schemaの定義には使わない。
新規DBは正式migrationのV1→V2を使用し、将来のschema変更をlegacy SQLへ追記しない。
旧DBの段階的な履歴補完については[Issue #14の既存DB手順](../orders/order-aggregate.md#既存dbの移行)と
[legacy文書](legacy/README.md)を参照し、その後に上のpreflight/照合/baselineを完了する。
legacy SQLだけでは新しいV1の全制約を満たさないため、適用済みという理由だけでbaselineしない。

## Migration運用・rollback

- mainに入ったVersioned migrationは編集・削除しない。修正は新しいVxを追加する。
- `V3__add_meaningful_constraint.sql` のように目的が分かる名前を付ける。schema変更はPR review必須。
- 適用前にbackupとdata preflightを行い、既存データとアプリ互換性・ロック時間を確認する。
- checksum mismatchは改変・誤ったartifact/DB接続を調査する。`repair`で雑に履歴を合わせない。
- 本番の `flyway clean` は禁止。clean-disabledを解除しない。
- MariaDB DDLはTransactionで完全rollbackできない。失敗時は書き込みを停止し、適用済みDDLと
  historyを確認する。review済みの新migrationによる前進修復、または検証済みbackupの復元を選ぶ。
- 旧アプリへ戻すだけではschema/dataは戻らない。V1を逆順DROPするrollbackは行わない。

## 検証

Java 17とDockerで `./mvnw clean verify`（Windows: `mvnw.cmd clean verify`）。
CIも `./mvnw verify` でSurefireとFailsafeの全テストを実行する。Docker未起動時はskipしない。
Issue #15では既存344件を維持し、schema制約41件・空DB/起動5件・既存DB preflight 28件を追加した
全418件がJava 17で成功している（failures/errors/skipped=0）。

- SchemaConstraintIT: MariaDB 12.2.2でFlyway migrate→JPA validate→raw INSERT/UPDATEの
  NOT NULL/CHECK/FK/UNIQUE拒否、許可値、version DEFAULT、index metadata/EXPLAIN。
- EmptyDatabaseMigrationIT: 完全な空DB、実ApplicationContext、history/version/description/checksum、
  再起動、不在migration/適用後schema driftでのvalidate失敗、未確認非空DBの自動baseline拒否、確認済み手動baseline。
- LegacyDatabasePreflightIT: 不完全な旧DBに負在庫・非正数量・孤児・未補完履歴・合計不一致等を作り、
  データとschema_historyを変更せずpreflightが停止することを確認する。
- AuthorizationMatrixITとInventoryConcurrencyMariaDbITもV1→V2+validateで実DB回帰する。
- MoneyMigrationITは既存20億円の値・他の全データ・V1 checksum/historyを維持し、BIGINT/制約/DEFAULT/FK/index、
  0/Integer上限超/Long最大値のraw INSERT/UPDATEと負数・NULL・BIGINT範囲外の拒否、再実行なしを検証する。
- MoneyApiITは実Security/API/DBで高額商品・注文・snapshot・CSV・OpenAPI int64・overflow時DB不変を検証する。
- H2の既存JPA fixture 3クラスだけは明示的にFlyway無効/create-drop。
  H2の成功をMariaDB migrationの検証結果として扱わない。Controller/unitの設計は維持する。

## Out of scope

CD（#17）、売上仕様、OAuth2、金額Value Object、在庫履歴、Order/API/認可の再設計、
並行制御方式の変更、本番DBの更新は行わない。

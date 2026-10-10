# 完了時の売上計上（Issue #27）

売上は **COMPLETEDかつcompletedAtが判明している注文のtotalPrice** とする。
PENDING/SHIPPED/CANCELLEDは0円・0件。発送だけでは計上しない。
10/1注文・10/5発送・10/8完了の注文は10/8の売上になる。
totalOrdersは同じ売上条件の注文件数。0円の完了注文も1件に数える。空集合は0L/0L。
金額は円整数Long/DB signed BIGINTのまま。明細数と全注文数はこのAPIでは返さない。

## APIと日時

ADMIN専用の `GET /api/v1/admin/orders/sales` を維持する。USERは403、未認証は401。

| query | 対象 |
| --- | --- |
| 省略 | 全期間 |
| fromのみ | completedAt >= from（下限inclusive） |
| toのみ | completedAt < to（上限exclusive） |
| fromとto | from <= completedAt < to |

`from` / `to` はISO-8601 Instant（`2026-10-01T00:00:00Z`など）。オフセット付きも同じUTC時点へ変換する。
URLでは `+` を `%2B` にencodeする。timezoneを持たないLocalDateTimeは受け付けない。
不正な日時形式は400/INVALID_REQUEST、from >= toはRepository問い合わせ前に400/INVALID_ORDER_REQUEST。
既存のMapエラー形式を維持する。期間を連続して集計しても上限は重複しない。

例:

```http
GET /api/v1/admin/orders/sales?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z
```

```json
{"totalSales":7000000000,"totalOrders":2}
```

レスポンスの既存2フィールドを維持する。from/toはリクエストで指定する境界であり、レスポンスに重複追加しない。
OrderResponseにはnullableなstring/date-timeのcompletedAtを追加する。
現行OpenAPI 3.1では `type: [string, null]` / `format: date-time` でnull許容を表す。
PENDING/SHIPPED/CANCELLEDはnull、通常のCOMPLETEDはISO timestamp。legacyの完了日時不明注文はnull。
注文CSVの列・内容形式は変更しない。

## 完了日時と状態遷移

許可する遷移はPENDING→SHIPPED、PENDING→CANCELLED、SHIPPED→COMPLETEDのみ。
`completeOrder` は既存Order PESSIMISTIC_WRITEロック・READ_COMMITTED Transactionを維持し、
`Order.complete(Instant)`が遷移を検証してcompletedAt/statusを確定し、saveする。
Serviceは注入されたClock（本番はClock.systemUTC）を使い、DATETIME(6)に合わせmicrosecond未満を切り捨てる。
updatedAtは変更日時であり、完了日時の代替には使わない。orderedAt/updatedAtの時間基盤は改変しない。
新規/更新OrderはCOMPLETED iff completedAt非NULLをJPA callbackでも検証する。
読み取りcallbackではlegacyを拒否・補完しない。終端状態へ再completeは409で、日時を書き換えない。
cancelの在庫復元処理は変更しない。completeは在庫を変更しない。

## DB集計

OrderRepository.aggregateSalesはSalesResponseへの単純なconstructor projectionで、SUMとCOUNTを1本のJPQLで返す。
Entity全件loadやJavaでのfilterは行わない。

```sql
SELECT COALESCE(SUM(total_price), 0), COUNT(id)
FROM orders
WHERE status = 'COMPLETED'
  AND completed_at IS NOT NULL
  AND (:from IS NULL OR completed_at >= :from)
  AND (:to IS NULL OR completed_at < :to);
```

日時不明のlegacy COMPLETEDは**全期間でも期間指定でも除外**する。
そのため、移行直後の売上は未確認の過去注文を含まない。旧APIの全注文合計・全件数とは意図的に意味が変わる。

## 既存DBへのV3適用

1. アプリと書き込みを止め、schema/data/historyのbackupと復元手順を確認する。
2. 接続先を確認し、V1/V2のsuccess・checksumと現行schemaを照合する。V1/V2は編集・再baselineしない。
3. V3前に `SELECT COUNT(*) FROM orders WHERE status='COMPLETED';` を記録し、既存の完了日時の外部記録の有無を確認する。
4. 検証DBで適用したartifactを通常migrate→Hibernate validateで起動する。
   V3はnullable DATETIME(6)列と索引を追加するだけで、データを推測補完しない。
5. V3履歴success、列precision=6/nullable、旧値とV1/V2履歴の不変を照合する。次のpreflightで未確認注文を列挙する。

```sql
SELECT id, status, ordered_at, updated_at
FROM orders
WHERE status = 'COMPLETED' AND completed_at IS NULL;
```

0件でなければ運用者確認なしに補完しない。外部記録等で完了時刻が確認できた注文だけ、UTC日時へ正規化して
ID・根拠・実施日時を記録し、検証後に条件付きで手動補完する。大量更新をmigrationへ混ぜない。

```sql
UPDATE orders SET completed_at = ?
WHERE id = ? AND status = 'COMPLETED' AND completed_at IS NULL;
```

パラメータは確認済みのUTC完了日時とID。更新件数が1であること、再実行で0になること、期間売上の変化を確認する。
`completed_at = updated_at` / `ordered_at` の一括更新は禁止。DB NOT NULL/CHECKを追加してlegacy行を締め出さない。
MariaDB DDLは暗黙commitされる。途中失敗時は書き込みを再開せずDDL/historyを確認し、前進修復またはbackup復元を判断する。
このPRで本番DBへの適用・補完は実施しない。

## 索引とEXPLAIN

MariaDB 12.2.2の使い捨てDBに2万行（25% COMPLETED、完了日を365日に分散）を作り、上の集計条件を実行した。
10月の該当行は423件。索引前はtype=ALL/推定20,181行、
`(status, completed_at)`追加後はtype=range/key=idx_orders_status_completed_at/推定423行/Using index conditionとなった。
この期間検索の実測結果を根拠にV3へ複合索引を追加する。
全期間は索引後もALLが選ばれた（推定19,681行）。SUMのためtotal_priceの参照が必要であり、索引利用を強制しない。
推定行数は統計・データ分布に依存し、本番で同じ値になることは保証しない。余分なcovering indexは追加しない。

再現用fixture（使い捨て検証DB限定）:

```sql
CREATE TABLE orders (
  id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL, total_price BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL, completed_at DATETIME(6) NULL,
  INDEX idx_orders_customer(customer_id)
);
INSERT INTO orders
SELECT seq,1,100,IF(MOD(seq,4)=0,'COMPLETED','PENDING'),
       IF(MOD(seq,4)=0,TIMESTAMPADD(DAY,MOD(seq,365),'2026-01-01'),NULL)
FROM seq_1_to_20000;
ANALYZE TABLE orders;
EXPLAIN SELECT COALESCE(SUM(total_price),0),COUNT(id) FROM orders
WHERE status='COMPLETED' AND completed_at IS NOT NULL
  AND ('2026-10-01' IS NULL OR completed_at>='2026-10-01')
  AND ('2026-11-01' IS NULL OR completed_at<'2026-11-01');
CREATE INDEX idx_orders_status_completed_at ON orders(status,completed_at);
ANALYZE TABLE orders;
-- 同じEXPLAINを再実行して比較する。
```

## 回帰テスト

SalesQueryITは全4状態、空集合、0円完了、複数明細、Longの70億円、期間の両境界/片側省略、legacy NULL除外、
UTC+microsecondのMariaDB round tripを検証する。SalesApiITは実JWT/Security/DBと固定Clockで、API期間/error/認可/レスポンス/OpenAPIを検証する。
CompletionMigrationITはV1/V2→V3の既存値/checksum/history、legacy NULL、索引列順、Hibernate validate、再適用なし、条件付き手動補完を検証する。
InventoryConcurrencyMariaDbITは同じSHIPPED注文への20同時complete（通常/互換status API経路）を3回検証し、
成功1件・他は409相当の例外・完了日時不変・在庫不変・売上1注文分のみを確認する。
`mvn test`はDocker不要、`mvn verify`はMariaDB 12.2.2 Testcontainers込みの全件をJava 17で実行する。

返金/返品/部分返金、税・割引、多通貨、売上台帳、日次集計テーブル、Money Value Object、ProblemDetail移行は対象外。

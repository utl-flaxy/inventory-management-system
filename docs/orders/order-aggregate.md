# Issue #14: Order集約と複数明細

Orderは注文者・作成/更新日時・状態・version・明細・合計を持つ集約ルート。
`Order.create(customer, items)` が明細0件とcustomer未指定を拒否し、内部の `addItem` で
両側の関連と合計を確定する。永続化後の明細追加・差し替え・合計setterは公開しない。
明細はOrderのcascadeで保存し、OrderItemRepositoryへ個別saveしない。
`REFRESH` cascadeは引き続き除外し、Orderのrefreshが暗黙の商品ロックを取得することを防ぐ。

## 注文者と日時

CurrentUser helperがSecurityContextの認証済みusernameをUserRepositoryで解決する。
customerは必須で、リクエストから指定できない。未認証/存在しない認証ユーザーは401。
所有者による参照・cancel制限やADMINの操作制限は[注文APIの認可（Issue #5）](../security/order-authorization.md)を参照。
orderedAt/updatedAtはInstant（UTC）。生成時に設定し、更新時はJPAのPreUpdateでupdatedAtを更新する。
OrderにもVersionを持たせ、商品側のVersionと既存の悲観ロックを維持する。

## APIと互換性

`POST /api/v1/me/orders` は必須・1〜100明細の `items` 配列と、Issue #29から必須 `Idempotency-Key` headerを受け取る。
keyはASCII `[A-Za-z0-9._:-]{1,128}`、case-sensitive、customer単位。同key/同明細は元のOrder、別明細は409。
API作成では `Order.create(customer, items, key, fingerprint)` を使い、metadataを生成時に確定する。
legacy用factoryと両metadata列NULLの既存注文は維持する。[冪等性仕様](idempotency.md)を参照。

```json
{"items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
```

各明細のproductId/quantityはnullを許さず正数。null明細も400。
同一商品IDが複数行ある場合は400で全体を拒否する（暗黙の数量統合は行わない）。
固定の数量上限は追加せず、Longの明細金額・注文合計の表現範囲を超えた場合に400で拒否する。
旧形式 `{"productId":1,"quantity":2}` はitems未指定として400になるため、クライアント更新が必要。
明細の商品IDはレスポンスでも昇順。

### 作成の明細数上限（AUD-08 / Issue #112）

上限100は通常の複数商品注文を許容しつつ、1要求で扱う商品種類・作成明細・在庫移動数を最大100に抑えるAPI制約。
監査の提案100と既存の1〜複数商品ユースケースを基に選定し、実測のlatency/SLAから決めた値ではない。
`OrderRequest.MAX_ITEMS` をDTOのSize/OpenAPI maxItemsとServiceの共通上限とする。設定変更だけで無制限には戻せない。
件数は入力配列で数え、重複を統合して回避しない。101以上はDTOで400 VALIDATION_ERROR / errors.items、
直接Service呼出しでは400 INVALID_ORDER_REQUEST、detail「注文明細は100件以内で入力してください」。
0件の既存拒否と、上限内の重複・不正数量・在庫/金額検証は維持する。

Serviceでは既存key形式検証の後、明細の走査・TreeMap作成・fingerprint生成・CurrentUser解決・
customer/商品ロック・保存より前にList.sizeで拒否する。HTTPのJWT認証に必要な既存user lookupは維持し、
全HTTP処理でDBアクセスがゼロになるとは主張しない。Transaction属性やロック順序は変更しない。
上限超過は既存keyのretryでも検証前には検索しない。過去の100超の注文はGET/CSV/cancel等で扱え、
既存のOrder/Response/DB schema・migrationへ上限を追加したり、保存済み明細を切り捨てたりしない。

この制限は注文処理のmap/指紋/Entity/更新の増加を抑える。JSON受信・デシリアライズ・Bean Validationの走査や
HTTP本文のbyte数、頻度、複数Transactionの総負荷は制限しない。edgeの本文/流量制御とhost/DBの容量設計は別途必要。
100商品でも競合待ちやDB障害で長時間化し得るため、ロック時間・CPU/メモリ上限の保証ではない。

Issue #5でURLをself/adminへ分離した。旧 `/orders/**` は廃止し、クライアント更新が必要。
成功HTTP status（200）と状態変更の業務ルールは維持する。
作成/一覧/詳細/cancel/ship/complete/statusの返却はOrderResponseに統一する。
`items` はproductId/productName/unitPrice/quantity/lineTotalを持ち、customerはid/usernameだけを返す。
EntityのorderItemsやネストしたProduct、Userのpassword/roleは返さない。
orderedAt/updatedAt/completedAt/versionを含める。ServiceがTransaction内で必要な関連を初期化するためOSIVは不要。
不正数量/金額/重複/存在しない商品は400、不正状態遷移は409、存在しない注文は404。
売上集計はIssue #27でCOMPLETEDかつcompletedAt非NULLに限定し、[完了日時で期間を絞る](../sales.md)。

## スナップショットと合計

OrderItem.createがロック下の商品名と単価をproductNameSnapshot/unitPriceへコピーする。
unitPriceは既存DBのprice列を再利用する。明細はsetterを持たず、保存後に編集できない。
詳細・一覧・CSVは保存した名前と単価を読み、現在の商品名/単価変更に影響されない。
Issue #25で価格・注文時単価・明細/注文合計は円整数のLong/longへ移行した。
明細金額はMath.multiplyExact(long,long)、注文合計はMath.addExact(long,long)で算出する。
30億円×2個=60億円を扱い、Long overflowは全在庫の変更前に400で拒否する。
合計は生成時に確定し、商品や状態の更新では変化しない。

## Transactionとロック

全明細を検証・canonical fingerprintを生成した後、User(customer)をPESSIMISTIC_WRITEで取得する。
同customer/keyのOrderを通常queryで読み、replay/conflictでは商品へのアクセス・保存を行わない。
新規の場合だけ商品IDを昇順に並べ、全商品をPESSIMISTIC_WRITEで取得・refreshする。
全商品の在庫と全明細の合計を検証してから在庫を減らす。
在庫減算、Order/全OrderItemの保存は1つのREAD_COMMITTED Transactionで行う。
1商品でも不存在・在庫不足・金額overflowなら保存を行わず、途中のDB失敗も全体rollbackする。
キャンセルは既存通りOrderロック→Product ID昇順ロック→復元後在庫を全件検証→更新。
PENDING→SHIPPED/CANCELLED、SHIPPED→COMPLETEDだけを許可し、二重復元を禁止する。

## 既存DBの移行

新規DBはFlyway V1→V2→V3→V4から生成し、金額3列はBIGINT。Hibernateはvalidateのみ行う。
V4はnullable key/fingerprintとcustomer/key UNIQUEを追加する。legacy行は両列NULLのままship/complete/cancelできる。
既存DBは[Flyway移行手順](../db/README.md)のbackup・preflight・schema照合・手動baselineを完了してから起動する。
以下はIssue #14の古いDBを段階的に補完するための履歴手順で、SQLはlegacyアーカイブにある。
本Issueの作業では本番DBへの接続・適用を行わない。

1. バックアップを取得し、アプリを停止して全ての書き込みを止める。DBセッションのtimezoneをUTCにする。
2. `SHOW CREATE TABLE orders/order_items/users/products` と履歴を照合する。
   既存注文者・注文日時は旧スキーマに存在しないため、ログ等の信頼できる情報から補完する必要がある。
   注文時の商品名も履歴から復元する。既存priceは注文時単価なので、そのまま維持する。
   復元できない情報がある場合は移行を止め、業務上の取扱いを決める。
   現在ユーザー・現在時刻・現在商品名を自動代入して履歴を捏造しない。
3. 対象DBを選んだMariaDBクライアントで
   `SOURCE docs/db/legacy/issue-14-order-aggregate-expand.sql;` を実行する。
   この段階はNULL許容の追加列とversion=0のみ。既存在庫・金額・明細数量は変更しない。再実行可能。
4. 確認済みの注文ごとにcustomer_id/ordered_at/updated_atと各明細のproduct_name_snapshotを補完する。
   更新履歴が判明しないupdated_atは、確認済みordered_atと同じ値を使う方針を業務側で確認する。
   この補完SQLは環境ごとに作成・レビューし、今回の共通SQLでは自動実行しない。
5. 次の照合を行い、異常があれば中止・個別修復する。

```sql
SELECT o.id FROM orders o LEFT JOIN users u ON u.id = o.customer_id
WHERE u.id IS NULL OR o.ordered_at IS NULL OR o.updated_at IS NULL;
SELECT i.id FROM order_items i
LEFT JOIN orders o ON o.id = i.order_id LEFT JOIN products p ON p.id = i.product_id
WHERE o.id IS NULL OR p.id IS NULL OR i.product_name_snapshot IS NULL
   OR i.price IS NULL OR i.price < 0 OR i.quantity IS NULL OR i.quantity <= 0;
SELECT o.id, o.total_price, SUM(i.price * i.quantity) AS line_total
FROM orders o LEFT JOIN order_items i ON i.order_id = o.id
GROUP BY o.id, o.total_price
HAVING COUNT(i.id) = 0 OR o.total_price IS NULL OR o.total_price <> SUM(i.price * i.quantity);
```

6. `SOURCE docs/db/legacy/issue-14-order-aggregate-constrain.sql;` を実行する。
   preflight CHECKで不足データ/空注文/合計不一致をDDL前に拒否し、NOT NULLとcustomer FKを追加する。
   `--force` は使用しない。DDLは暗黙commitするため、失敗時はSHOW CREATE TABLEで適用箇所を確認し、
   未適用のDDLだけを実行する。この段階は1回適用用。同名customer FKがある新規DBには適用しない。
   quantity/stock CHECKと既存order_id/product_id FKを維持する（#10の適用を前提とする）。
7. 必須列・FK・既存CHECK・versionと注文/明細合計を確認し、新アプリと更新済みクライアントを起動する。
   この旧手順だけでV1の全制約は満たさない。Flyway移行手順のpreflight・schema照合・手動baselineも完了する。
   Hibernateによる履歴補完は行わないため、移行未完了のDBで新アプリを起動しない。

## 検証

Java 17とDockerを使って `./mvnw clean verify`（Windows: `mvnw.cmd clean verify`）を実行する。
JWT・入力Validation・DB制約・全16状態遷移・cancel rollback・商品version競合の回帰を維持する。
MariaDB 12.2.2 Testcontainersで従来の100接続同時注文に加え、逆順の複数商品注文/取消、
複数商品失敗時の不変、履歴snapshot、段階的SQL移行を検証する。Docker未起動時にskipしない。

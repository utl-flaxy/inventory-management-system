# 注文一覧のページング（Issue #65）

## APIと移行

`GET /api/v1/admin/orders`はROLE_ADMINだけ、`GET /api/v1/me/orders`はROLE_USER/ROLE_ADMINの本人だけ。
未認証は401、USERのadmin一覧は403。本人一覧は認証済みprincipalからcustomer IDを取得し、
外部のcustomerId/username query parameterで対象を変更できません。

**破壊的変更:** 以前のJSON配列を既存`PageResponse<OrderResponse>`へ変更します。
クライアントは配列の参照を`response.content`へ移し、page/sizeとmetadataを利用してください。

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,"first":true,"last":true}
```

OrderResponseの全field/型とcustomer/itemsのネストを維持します。商品名・単価は注文時snapshot、
lineTotalは保存単価×数量、totalPriceは注文の保存合計（long）。Productの現在値を読みません。
明細はitem ID昇順です。completedAtのnull、日時・status enum・versionの契約も維持します。

| parameter | contract |
|---|---|
| page | 0-based、default 0、非負int |
| size | default 20、正int、100超は100 |
| sort | default id,desc。id / orderedAt / status / totalPriceのfield,ascまたはfield,desc |

複数sortは`sort=status,asc&sort=totalPrice,desc`のように繰り返します。
idが不在なら最後にid DESCを追加し、明示されたidは追加しません。
同じfieldの重複、不正direction/構文、未知field、空/負数/非整数/範囲外page/size、
複数page/size、JPA int offsetを超えるpage×正規化sizeは400 INVALID_REQUESTの既存ProblemDetailです。
巨大なsizeはintとして有効な範囲で100へ制限します。未知field/入力値をエラーdetailやlogに出しません。
空データや最終ページを超えた要求は200、content[]、正確な注文数/totalPagesを返します。
Product/Inventory APIの共通Pageable resolverや上限は変更しません。

## 2段階取得とメモリ

1. Order rootだけをSELECTし、ORDER BYとLIMIT/OFFSETで最大100個のIDを取得します。
   countはOrder rootのCOUNT(id)。本人一覧は両queryでWHERE customer_id=:authenticatedId。
2. ID IN (:pageIds)の注文とcustomer、LEFT JOIN order_itemsを1 queryでscalar DTO取得します。
   本人一覧はこのqueryにもcustomer条件を加えます。ページのID順にDTOを組み立て直します。

代表SQL（実際のalias/placeholderはHibernate生成）:

```sql
SELECT id FROM orders
WHERE customer_id = ?
ORDER BY ordered_at ASC, id DESC LIMIT ? OFFSET ?;
SELECT COUNT(id) FROM orders WHERE customer_id = ?;
SELECT o.id, o.status, o.ordered_at, o.updated_at, o.completed_at, o.total_price, o.version,
       c.id, c.username, i.id, i.product_id, i.product_name_snapshot, i.price, i.quantity
FROM orders o JOIN users c ON c.id = o.customer_id
LEFT JOIN order_items i ON i.order_id = o.id
WHERE o.id IN (...) AND c.id = ?
ORDER BY o.id DESC, i.id ASC;
```

adminはcustomerのWHERE条件を外します。customer JOINによって本人条件がc.idへ最適化されても、
orderのFKと結合条件で同じownershipが保証されます。
関連queryにはPageable/LIMITを渡しません。collection fetch joinのメモリページングも行いません。
Product本体、User.password、注文idempotency key/fingerprintをprojectionへ含めません。
注文/明細のJPA managed entityを全件ロードせず、OSIVに依存しません。

保持単位は最大100 ID、その注文に属する全明細row、および最大100個の完成OrderResponse。
1注文の明細件数に新たな業務上限を設けないため、byte数の固定上限ではありません。
総注文件数分のListを保持する設計には戻しません。
空ページは関連queryなし。Spring Dataは部分ページからtotalを確定できる場合、countを省略します。

通常の満杯ページはID page + count + details = **3 order queries**で、size 1/20/100/101で一定。
JWT user lookupを含むHTTP request全体はadmin **4**、self **5** SELECT
（selfはCurrentUserの既存user lookupが追加）。注文/明細Entity loadとcollection fetchは0です。

## Transactionと同時更新

ID page、必要なcount、関連取得、DTO化は同じread-only REPEATABLE_READ transaction内で完了します。
MariaDB/InnoDBの非locking consistent readが、最初のSELECT以降の同一snapshotを保ちます。
取得途中で他transactionが注文を追加・状態更新してcommitしても、このrequestのcount/内容には混ぜません。
更新lockや書き込みは追加しません。関連取得の失敗も黙殺せず、既存safe500処理へ伝えます。

別HTTP requestのページ間は独立snapshotです。途中の追加/状態変更がsort順を変えれば、
offset pagingではページが移動し、跨ぐrequest間の重複/欠落を完全には防げません。
更新のないデータでは一意id tie-breakerで安定したページ境界を保証します。
深いOFFSETや非索引sortのDBコストは残ります。cursor APIや索引の追加は今回行いません。

## 索引と回帰検証

Flyway V1のPRIMARY orders(id)、idx_orders_customer(customer_id)、idx_order_items_order(order_id)
を実MariaDB EXPLAINで確認します。InnoDBのsecondary indexにはPKも含まれます。
default id DESCのadmin rootはPRIMARY、selfはidx_orders_customer、明細のIN取得はidx_order_items_orderを利用。
status/orderedAt/totalPriceの複合sortはDB filesortになる場合があり、万能な複合indexは追加しません。
V1〜V6とDB schemaは変更しません。

テストではOSIVをOFF、fail_on_pagination_over_collection_fetchをtrueにして、
query数・SQL WHERE/IN/LIMIT・Hibernate statisticsを検証します。
126注文×2明細、100件の上限、複数ページ、保存値不変、並行commit後のsnapshot、
認可・ProblemDetail・OpenAPI・既存CSV/売上/注文コマンドを回帰確認します。
prod実consoleの全loggerも監査し、response内username/商品名、未知sort、JWT/keyをlogに出しません。

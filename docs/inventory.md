# 在庫調整と変更履歴（Issue #39）

## 在庫を変更できる経路

| 操作 | 在庫 | 履歴 |
|---|---|---|
| ADMIN Product POST | initial stockを指定（従来通り必須） | 作らない |
| 注文作成 | quantity分を減算 | 商品ごとにORDER_CREATEDを1件 |
| PENDINGのcancel | quantity分を復元 | 商品ごとにORDER_CANCELLEDを1件 |
| ADMIN手動調整 | signed deltaを加算 | 商品ごとにMANUALを1件 |
| Product PUT / ship / complete | 変えない | 作らない |

Productのpublic stock setterは廃止し、decreaseStock / increaseStock / adjustStockで
正の数量・非0差分・非負の結果を検証する。Integerの加算はMath.addExactでoverflowを拒否する。
通常のProduct PUTはname / price / versionのみ。stock（nullを含む）や未知fieldがあるJSONは
400 INVALID_REQUESTで拒否する。DTO専用の拒否であり、Jackson全体のunknown field設定は変更しない。

## ADMIN手動調整

POST /api/v1/admin/products/{id}/inventory-adjustments

~~~json
{
  "delta": 10,
  "reason": "RECEIPT",
  "note": "仕入れ入荷"
}
~~~

deltaは必須のsigned int32、0は禁止。reasonはRECEIPT / DAMAGE / STOCKTAKE / CORRECTIONのみ。
各reasonで正負どちらの差分も許可する（例えば入荷入力の訂正にも使える）。
ORDER_CREATED / ORDER_CANCELLED / 未知enumは400 INVALID_REQUEST。
noteは任意、最大500文字。空文字・blankのみはNULLへ正規化し、非blankはそのまま保存する。
noteはログに出力しない。

ADMINは200でInventoryMovementResponseを受け取る。USERは商品存在に関係なく403、
未認証は401。SecurityFilterChainのADMIN境界でbody解析・lookupより先に拒否する。
ADMINで商品未存在は404 PRODUCT_NOT_FOUND。
形式は正しくても結果が負在庫またはint32上限超なら409 INVENTORY_ADJUSTMENT_CONFLICT。
0/null差分・reason欠落・note超長は400 VALIDATION_ERROR。
Serviceの直接呼出も入力とADMIN権限を防御する。

~~~json
{
  "id": 123,
  "productId": 5,
  "delta": 10,
  "stockBefore": 3,
  "stockAfter": 13,
  "reason": "RECEIPT",
  "sourceType": "MANUAL",
  "sourceId": null,
  "actor": {"id": 1, "username": "admin"},
  "note": "仕入れ入荷",
  "createdAt": "2026-10-10T01:02:03.123456Z"
}
~~~

Entityは返さない。actorは既存のuser summary DTOを再利用し、password/hashを返さない。
Clock beanのInstantをmicrosecondへ切り捨ててDATETIME(6)へ保存する。

## ADMIN履歴取得

GET /api/v1/admin/products/{id}/inventory-movements?page=0&size=20

Spring Pageを返す。page / sizeを利用し、sortはサーバー固定のcreatedAt DESC, id DESC。
clientのsort指定は上書きする。同じmicrosecondの記録もidで安定する。
存在する商品で履歴なしは200 empty page、未存在は404。USER403 / 未認証401は調整と同じ。
product_idで絞り、actorをEntityGraphで取得してTransaction内でDTOへ変換する。
商品・注文のEntityをresponseへ含めず、履歴1件ごとのlazy fetchを避ける。

## Immutable履歴・reason/source/actor

InventoryMovementはsetterなし・Hibernate @Immutable・updatable=false。
Repositoryは新規saveと商品別Page queryだけを公開する。履歴のupdate/delete APIはない。

| reason | sourceType | sourceId | delta | actor / note |
|---|---|---|---|---|
| ORDER_CREATED | ORDER | 保存済みOrder ID（必須） | 負 | customer / NULL |
| ORDER_CANCELLED | ORDER | Order ID（必須） | 正 | 実際にcancelしたselfまたはADMIN / NULL |
| RECEIPT / DAMAGE / STOCKTAKE / CORRECTION | MANUAL | NULL | 非0 | ADMIN / 任意note |

product・actor・時刻は必須。before / afterは非負で、after == before + delta。
reason/source不一致・Order ID欠落・逆符号・注文noteはdomainでも拒否する。
Immutableはアプリの契約。DB管理者によるraw SQLのUPDATE/DELETEを防ぐtriggerや権限分割は今回導入しない。

## Transactionと並行制御

手動調整はREAD_COMMITTEDの1Transactionで、CurrentUser → Product PESSIMISTIC_WRITE →
refresh → 現在stockの検証/変更 → Product保存 → Movement保存 → commit。
注文作成は既存のcustomer行ロック → 全ProductのID昇順ロックを維持する。
Order ID取得後に各Productのmovementを追加し、Product / Order / OrderItem / Movementを同時commitする。
cancelは既存OrderロックとProduct ID昇順ロックを維持し、全商品の復元可能性を先に検証する。
InventoryServiceの注文記録はMANDATORYで既存Transactionに参加し、単独commitを許可しない。
REQUIRES_NEWは使わない。履歴保存失敗時は在庫・注文・明細・status・履歴の全体をrollbackする。

同customer/key/bodyのreplayは既存Orderを返し、追加movementは0件。
異なるbodyのkey再利用は409で副作用なし。二重cancelは409で在庫も履歴も増えない。
ship / completeは履歴を書かない。
手動20同時調整、manual/order、manual/cancelも同じProductロックで直列化し、lost updateを防ぐ。
stock変更時は既存@Versionが進む。古いversionのProduct PUTは409 CONCURRENT_UPDATE。

## V5 migration・legacy境界

V5__create_inventory_movements.sqlだけを追加する。V1〜V4は変更しない。
inventory_movementsはid / product_id / delta / stock_before / stock_after / reason /
source_type / source_id / actor_user_id / note / created_atを持つ新table。
NOT NULL、非0delta、非負stock、前後関係、allowed reason/sourceのCHECKを設ける。
CHECKの加算はCAST AS SIGNEDでBIGINTへ広げ、INT overflowに依存しない。
reason / source_typeはutf8mb4_binで大小文字を区別する。

product→products、actor→users、ORDER source_id→ordersのFKはRESTRICTでcascade deleteしない。
MANUAL source_idはNULL。履歴query用(product_id, created_at, id)とsource_id索引を追加する。
MariaDB12.2.2のEXPLAINで履歴queryが複合索引を選び、filesortしないことを検証する。

**V5以前の在庫変更履歴は存在しない。** 現在stockのinitial snapshotや推測過去履歴をbackfillしない。
V4の既存stock・注文・Flyway historyを維持し、V5適用後の変動から記録する。
既存専用DB userの対象DB限定CREATE / ALTER / INDEX / REFERENCES等でV1〜V5が実行できる。
追加のglobal privilegeは不要。[DB運用と移行](db/README.md)を参照。

## 削除制限と対象外

履歴があるProductはFKで削除できない。注文参照Productと同じ既存の安全な500 SYSTEM_ERRORを維持し、
SQL・constraint名をresponseへ出さない。履歴だけをcascade削除する迂回は設けない。
注文/履歴参照をまとめたPRODUCT_DELETE_CONFLICT等への409統一とsoft deleteは別Issue。

手動調整POSTの冪等性は未導入。通信retryで同じPOSTを再送すると再度調整されるため、
クライアントは成功済み操作を不用意に再送しない。将来のIdempotency-Key対応は別Issue。
warehouse / lot / reservation / 返品 / outbox / Redis / RDS移行 / OAuth2再設計は対象外。
production DB・EC2操作・Deploy有効化は行わない。

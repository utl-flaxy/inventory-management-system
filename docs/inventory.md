# 在庫調整と変更履歴（Issue #39 / #43）

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

~~~http
POST /api/v1/admin/products/{id}/inventory-adjustments
Idempotency-Key: receipt-20261010-001
~~~

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
未認証は401。SecurityFilterChainのADMIN境界でheader/body解析・lookupより先に拒否する。
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
冪等性key・fingerprintもresponseやOpenAPIの成功schemaへ含めない。
Clock beanのInstantをmicrosecondへ切り捨ててDATETIME(6)へ保存する。

### Idempotency-Keyと再送

Issue #43から手動調整POSTはkey必須。注文作成と同じASCII `[A-Za-z0-9._:-]{1,128}` を使い、
大小文字を区別する。trim/lowercaseは行わず、空白・制御文字・非ASCII・129文字以上は拒否する。
共通IdempotencyKey validatorで400 INVALID_IDEMPOTENCY_KEYを返す。
注文と同じく、Security firewallが先に拒否する改行等のcontrolは400 INVALID_REQUESTとなる。

scopeはactor_user_id + key。商品IDはscopeへ含めず、fingerprintへ含める。
同じADMIN・key・内容を再送すると、最初のInventoryMovement ID・before/after・createdAtを200で返す。
現在stockが別の操作で変わっていても再計算せず、在庫・version・履歴件数を増やさない。
同じADMIN・keyを別商品または別内容に使うと409 IDEMPOTENCY_KEY_CONFLICT。
別ADMINは同じkeyを別操作に使える。新しい操作には新key、通信retryには元のkeyを送る。

fingerprintはproductId / signed delta / reason.name() / 正規化noteを型付きのcanonical文字列にし、
UTF-8のSHA-256をlowercase hex 64文字で保存する。JSON property順には依存しない。
noteはnullまたはString.isBlank()ならnull、それ以外は前後空白もそのまま。
null markerとUTF-8 byte長付きnoteを区別し、改行や区切り文字があっても曖昧にならない。

Validation・認可・商品未存在・underflow/overflow・INSERT/flush失敗では成功履歴が残らず、keyを消費しない。
入力を修正して同じkeyで再試行できる。成功したkeyは履歴が残る限り有効で、TTL/cleanupは設けない。
既存の注文冪等性scope・fingerprint・API契約は維持する。履歴GETにはkey不要。

## ADMIN履歴取得

GET /api/v1/admin/products/{id}/inventory-movements?page=0&size=20

HTTPはProductと同じ固定 `PageResponse<InventoryMovementResponse>` を返す（Issue #52）。
top-levelはcontent / page / size / totalElements / totalPages / first / lastの7項目だけ。旧PageImpl内部fieldは非公開。
ServiceのSpring Page返却は維持する。pageは0-based、size=500は500、size=5000は上限2000。sortはサーバー固定のcreatedAt DESC, id DESC。
clientのsort指定は上書きする。同じmicrosecondの記録もidで安定する。
存在する商品で履歴なしは200 empty page、未存在は404。USER403 / 未認証401は調整と同じ。
product_idで絞り、actorをEntityGraphで取得してTransaction内でDTOへ変換する。
商品・注文のEntityをresponseへ含めず、履歴1件ごとのlazy fetchを避ける。

## Immutable履歴・reason/source/actor

InventoryMovementはsetterなし・Hibernate @Immutable・updatable=false。
Repositoryは新規save・商品別Page・参照exists・actor/key replay queryを公開する。履歴のupdate/delete APIはない。

| reason | sourceType | sourceId | delta | actor / note |
|---|---|---|---|---|
| ORDER_CREATED | ORDER | 保存済みOrder ID（必須） | 負 | customer / NULL |
| ORDER_CANCELLED | ORDER | Order ID（必須） | 正 | 実際にcancelしたselfまたはADMIN / NULL |
| RECEIPT / DAMAGE / STOCKTAKE / CORRECTION | MANUAL | NULL | 非0 | ADMIN / 任意note |

product・actor・時刻は必須。before / afterは非負で、after == before + delta。
reason/source不一致・Order ID欠落・逆符号・注文noteはdomainでも拒否する。
Immutableはアプリの契約。DB管理者によるraw SQLのUPDATE/DELETEを防ぐtriggerや権限分割は今回導入しない。

## Transactionと並行制御

手動調整はREAD_COMMITTEDの1Transactionで、ADMIN確認 → 入力/key検証・fingerprint生成 →
User(actor) PESSIMISTIC_WRITE → actor/key照会 → Product PESSIMISTIC_WRITE →
refresh → 現在stockの検証/変更 → Product保存 → Movement保存 → flush → commit。
replay/conflictはactor/key照会で確定し、Product lookup/lockへ進まない。
注文作成は既存のcustomer行ロック → 全ProductのID昇順ロックを維持する。
Order ID取得後に各Productのmovementを追加し、Product / Order / OrderItem / Movementを同時commitする。
cancelはOrder → User(actor) → Product ID昇順のロック後、全商品の復元可能性を先に検証する。
Movement INSERTのactor FKが暗黙のUserロックを取るため、cancelもProductより先にactorをロックする。
これにより手動調整のUser → Productとの循環待ちを防ぐ。Product削除はProduct行だけをロックし、Userを取得しない。
InventoryServiceの注文記録はMANDATORYで既存Transactionに参加し、単独commitを許可しない。
REQUIRES_NEWは使わない。履歴保存失敗時は在庫・注文・明細・status・履歴の全体をrollbackする。

同customer/key/bodyのreplayは既存Orderを返し、追加movementは0件。
異なるbodyのkey再利用は409で副作用なし。二重cancelは409で在庫も履歴も増えない。
ship / completeは履歴を書かない。
手動20同時調整、manual/order、manual/cancelも同じProductロックで直列化し、lost updateを防ぐ。
stock変更時は既存@Versionが進む。古いversionのProduct PUTは409 CONCURRENT_UPDATE。
同actor/keyの20同時再送はactor行ロックで直列化し、すべて同じmovementを返す。
DB UNIQUEも最終防御に残す。save/flushのcauseにMariaDB error 1062かつ正確な
uk_inventory_movements_actor_idempotency_keyがある場合だけ409へ限定変換する。
失敗Transactionで再照会せずrollbackする。対象外のSQL障害は固定の500 SYSTEM_ERROR。

## V5 migration・legacy境界

Issue #39でV5__create_inventory_movements.sqlを追加した。V1〜V4は変更しない。
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
既存専用DB userの対象DB限定CREATE / ALTER / INDEX / REFERENCES等でV1〜V6が実行できる。
追加のglobal privilegeは不要。[DB運用と移行](db/README.md)を参照。

## V6 migration・冪等性制約

V6__add_inventory_adjustment_idempotency.sqlを追加し、V1〜V5のSQL/checksumは変更しない。
inventory_movementsへnullable idempotency_key VARCHAR(128) / request_fingerprint CHAR(64)を追加する。
両列はascii_bin。UNIQUE uk_inventory_movements_actor_idempotency_key(actor_user_id,idempotency_key)と、
key/hashが両方NULLまたは両方非NULL、ORDER metadataは両方NULL、hashはlowercase hex 64文字のCHECKを設ける。
既存MANUAL/ORDERは両列NULLのまま、stock・履歴内容をbackfillしない。
新規MANUAL factoryは有効なkey/hashを必須にし、ORDER factoryは両列NULLを維持する。
MariaDB12.2.2でV5→V6の旧データ保持・制約・Hibernate validate・2回再起動pending0を検証する。

## 削除制限と対象外

履歴があるProductは監査情報保全のため削除できない。Issue #41で注文参照と在庫履歴参照を
409 PRODUCT_DELETE_CONFLICTへ統一する。外部responseにSQL・constraint名を出さず、固定業務messageを返す。
削除TransactionはProduct行ロック→参照チェック→delete→flushの順とし、FK RESTRICTも最終防御に残す。
409時にProduct・Order・OrderItem・InventoryMovementとstockを変更しない。履歴のcascade削除は行わない。
soft delete・archive・Product復元は対象外。

TTL/cleanup・汎用冪等性framework・Redis/分散lock・履歴GETの冪等化は対象外。
warehouse / lot / reservation / 返品 / outbox / Redis / RDS移行 / OAuth2再設計は対象外。
production DB・EC2操作・Deploy有効化は行わない。

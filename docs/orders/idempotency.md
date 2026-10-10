# 注文作成のIdempotency-Key（Issue #29）

注文APIでは `POST /api/v1/me/orders` だけに `Idempotency-Key` headerを必須とする。
初回もreplayもHTTP 200。GET、cancel、ADMINのship/complete/status等には追加header不要。
クライアントは業務注文を始めるときにkeyを生成し、通信retryでは同じkeyと明細を送る。
新しい注文には新しいkeyを使う。失敗してOrderがcommitされなかったkeyは再利用できる。

```http
POST /api/v1/me/orders
Authorization: Bearer <JWT>
Idempotency-Key: 01J9VQW2M7MX7KQ7S2H4F7ABCD
Content-Type: application/json

{"items":[{"productId":1,"quantity":2},{"productId":2,"quantity":3}]}
```

## Keyとscope

keyはcase-sensitiveなopaque identifierで、ASCII `[A-Za-z0-9._:-]{1,128}`。
trim・小文字化を行わない。未指定、空、空白、前後空白、129文字以上、改行、control文字、
日本語、`/`や`,`等は400 `INVALID_IDEMPOTENCY_KEY`（Security firewallが先に拒否する改行/controlは400 `INVALID_REQUEST`）。匿名アクセスは従来どおり401。
USER/ADMINともself APIを利用でき、customerは現在のDBユーザーから決定する。
一意性は `(customer_id, idempotency_key)`。別customerの同じkeyは独立した注文であり、
他customerの注文を検索・返却しない。`KeyA` と `keya` は別key。

## Canonical fingerprint

DTOおよびServiceでproductId/quantityの正数・明細非空・商品ID重複なしを先に検証する。
重複明細は400のままで、数量を統合しない。検証済み明細をproductId昇順に並べ、
`productId:quantity` をLFで連結する（末尾LFなし）。例のcanonical文字列は `1:2\n2:3`。
UTF-8固定、Java標準MessageDigest SHA-256、lowercaseの64文字hexで保存する。
JSONの空白・property順・明細順に依存しない。
商品価格、stock、customerId、日時は含めず、customerはquery/UNIQUEのscope側で扱う。

## Replayとconflict

同customer/keyでfingerprintが一致すれば既存Orderの同じID・現在状態と保存済みsnapshotを返す。
SHIP/COMPLETE/CANCEL後も注文を再生成せず、その現在状態を返す。
商品Repositoryへのアクセス、商品lock、在庫再検証、再減算、注文/明細保存は行わない。
商品価格・名前が変わったり在庫が0になっても元の単価・合計を返す。
DTOに必要なcustomer/itemsはTransaction内で初期化し、OSIVに依存しない。

fingerprint不一致は409 `IDEMPOTENCY_KEY_CONFLICT`。商品へのアクセスや更新は行わない。
Issue #33でエラーはapplication/problem+jsonのSpring ProblemDetailへ移行し、status/codeを維持する。
Validationは400 VALIDATION_ERRORのerrorsへnested pathを保持する。[APIエラー契約](../api-errors.md)を参照。
key/fingerprintはOrderResponse・OpenAPI schemaへ出さず、
keyをapplication logへ追加出力しない。OpenAPIにはrequired headerとscope/retry/conflict/exampleを記載する。

## Transactionとロック順序

注文作成はREAD_COMMITTEDの1つのTransactionで以下を行う。

1. key/明細検証とfingerprint生成、CurrentUserの解決。
2. User(customer)行を明示的にPESSIMISTIC_WRITEで取得する。
3. 同customer/keyのOrderを通常queryで検索し、replay/conflictならここで終了する。
4. 新規ならProductをID昇順にPESSIMISTIC_WRITE取得・refreshする。
5. 全在庫・金額を検証し、key/fingerprint必須のOrder factoryで生成する。
6. 在庫減算とOrder/明細cascade保存を行い、commitする。途中失敗は全体rollbackする。

| 操作 | 排他ロック順序 |
|---|---|
| create | User(customer) → Product IDs昇順。既存Orderの排他lockなし |
| cancel | Order → User(actor) → Product IDs昇順（Issue #43からactor FKの待ち順を統一） |
| ship / complete | Order |
| Product update | Product |

同customerのcreateはkeyが違っても直列化される。異なるcustomerは商品lockで在庫を保護する。
createから既存Orderの排他lockを取らないため、cancelのOrder → Userと循環待ちを作らない。
Issue #43では手動調整のUser → Productに合わせ、cancelもProduct前にactorをロックする。
Movement INSERTのactor FKがProduct後にUserを待つ逆転を防ぐ。[在庫仕様](../inventory.md)を参照。
ship/completeはUserをロックしない。FKのcustomer列はupdatable=falseを維持する。
商品は複数でも常に同じ順序。SERIALIZABLE、Redis、TTL、共通middlewareは導入しない。

## DB・V4移行・legacy互換

V1/V2/V3は変更しない。V4はordersへnullable `idempotency_key VARCHAR(128)`、
`request_fingerprint CHAR(64)` と `uk_orders_customer_idempotency_key` UNIQUE(customer_id,idempotency_key)を追加する。
両列は `CHARACTER SET ascii COLLATE ascii_bin`。keyのASCII契約とcase-sensitive比較をDBでも維持する。
JPA列はupdatable=false・公開setterなし。key付きfactoryは有効keyと64文字lowercase hexを必須とする。
legacy用factoryおよび両列NULLの既存行は読み取り・ship/complete/cancelが可能。
JPA callbackは両列NULLを許し、片側だけの値や不正値は拒否する。

主な直列化はcustomer lock、DB UNIQUEは別writer/将来の変更への防御。
MariaDB 1062かつ対象UNIQUE名を持つ保存失敗だけ409へ変換し、一般の制約違反は誤ってreplayにしない。
rollback-onlyのTransactionで再照会/replayせず、全体をrollbackする。クライアントが同じ内容で再送すれば通常lookupで判断する。

適用前に書き込み停止、backup、V1/V2/V3の履歴/checksumとschema・件数を照合し、検証DBでV4を試す。
通常起動のFlyway migrate → Hibernate validateで適用し、列型・長さ・照合順序・UNIQUEとlegacy NULLを確認する。
既存行のkey/fingerprintを推測で補完しない。MariaDB DDLは暗黙commitのため途中失敗時は書き込みを再開せず、
SHOW CREATE TABLE/historyを確認し、review済み前進修復またはbackup復元を行う。新クライアントへheader必須変更を配布する。
本作業で本番DBへ接続・適用しない。詳細は[DB運用](../db/README.md)。

## 回帰テスト

Java 17で `./mvnw clean test` はDocker不要。key/domain/fingerprint・Serviceのreplay/conflictで商品未アクセスを検証する。
`./mvnw clean verify` は全既存テストとMariaDB 12.2.2の以下を実行する（Docker必須、skipしない）。

- 初回stock10→7と10回retry、逆順明細、mismatch409、価格変更後/在庫0のsnapshot、現在状態。
- customer分離、case-sensitive key、header400、匿名401、ADMINコマンドheader不要、DTO/schema非露出。
- barrierで20 HTTPリクエストを同時開始して3回実行。全20件200、同ID、1注文/1明細セット、在庫更新1回。
- body A/B各10件を同時開始。勝者10件200/敗者10件409、1注文、勝者の数量だけ在庫減算。
- 同customer別key・別customer同keyの同時注文、失敗後同key retry、対象UNIQUE違反時のrollback。
- V3→V4の既存全4状態・値・V1/V2/V3履歴/checksum保持、実列/index比較、重複拒否、JPA validateと2回再起動pending0。

外部決済、Outbox、注文Event Sourcing、TTL/cleanupは対象外。ProblemDetail移行はIssue #33で実装済み。

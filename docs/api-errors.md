# APIエラー契約（Issue #33 / #35 / #39 / #41 / #43）

## B3 ADMIN強制失効の追加契約

`POST /api/v1/admin/users/{uid}/token-revocations` の成功は204・bodyなし。
現ADMIN本人の再認証失敗は401 INVALID_CREDENTIALS、不正/旧世代JWTと古いproofは401 UNAUTHENTICATED、
USER/現在権限不足は403 ACCESS_DENIED、再認証済みADMINの対象不在は404 USER_NOT_FOUND。
入力不正は400 VALIDATION_ERROR＋errors.currentPassword、不正UIDは400 INVALID_REQUEST。
認証管理用429＋正のRetry-After、接続元不明503、DB一時障害503、未知/設定障害500の既存契約は同じ。
503へRetry-Afterは付けない。固定detail・ProblemDetail・requestIdを使い、credential/hash/JWT/拒否値は返さない。
[具体的境界とソース/IT](auth.md#b3-admin再認証付きの指定uid全端末失効)を参照する。

主要REST APIのエラーはSpringの `org.springframework.http.ProblemDetail` をruntime responseに使う。
Content-Typeは `application/problem+json`。ControllerAdvice、Security filter、method security、firewallで共通契約となる。
成功レスポンスのJSON/CSV・HTTP statusは維持する。Issue #35でProduct resource未存在を404へ統一する。

**API契約の破壊的変更**: 旧code/message Mapのmessageはdetailへ、旧Validationのfield Mapはerrorsへ移る。
クライアントはcodeで分類し、detailを表示し、Validationのerrorsでfieldを特定する。

## Schema / URI方針

| field | 意味 |
|---|---|
| type | problem category。安定した絶対URI |
| title | codeに対応する固定の短い英語説明 |
| status | 実HTTP statusと同じ整数 |
| detail | 安全な業務説明、入力形式の固定説明 |
| instance | request URI pathのみ。context pathを含み、query stringを含めない |
| code | 既存業務code、ValidationはVALIDATION_ERROR |
| errors | Validationのみ。nested field path → safe messageのMap |

typeは `https://inventory-management-system.example/problems/{slug}`。
slugはcodeのLocale.ROOT lowercase・underscoreをhyphenへ置換する。公開サイトの運用は不要。
titleはApiProblemFactoryでcode別に明示し、timestamp・trace・exception・stackTraceを返さない。
JSON propertyの順序は契約にしない。

## code / status一覧

| status | code | title |
|---|---|---|
| 400 | VALIDATION_ERROR | Validation Error |
| 400 | INVALID_REQUEST | Invalid Request |
| 400 | INVALID_IDEMPOTENCY_KEY | Invalid Idempotency Key |
| 400 | INVALID_PRODUCT_UPDATE | Invalid Product Update |
| 400 | INVALID_INVENTORY_ADJUSTMENT | Invalid Inventory Adjustment |
| 400 | INVALID_ORDER_REQUEST | Invalid Order Request |
| 400 | OUT_OF_STOCK | Out of Stock |
| 401 | INVALID_CREDENTIALS | Invalid Credentials |
| 401 | UNAUTHENTICATED | Unauthenticated |
| 403 | ACCESS_DENIED | Access Denied |
| 404 | ORDER_NOT_FOUND | Order Not Found |
| 404 | PRODUCT_NOT_FOUND | Product Not Found |
| 409 | USERNAME_ALREADY_EXISTS | Username Already Exists |
| 409 | IDEMPOTENCY_KEY_CONFLICT | Idempotency Key Conflict |
| 409 | CONCURRENT_UPDATE | Concurrent Update |
| 409 | INVENTORY_ADJUSTMENT_CONFLICT | Inventory Adjustment Conflict |
| 409 | PRODUCT_DELETE_CONFLICT | Product Delete Conflict |
| 409 | ILLEGAL_ORDER_STATE_TRANSITION | Illegal Order State Transition |
| 409 | ORDER_INVENTORY_RESTORATION_FAILED | Order Inventory Restoration Failed |
| 415 | UNSUPPORTED_MEDIA_TYPE | Unsupported Media Type |
| 429 | LOGIN_RATE_LIMITED | Login Rate Limited |
| 429 | REGISTRATION_RATE_LIMITED | Registration Rate Limited |
| 429 | AUTH_MUTATION_RATE_LIMITED | Authentication Mutation Rate Limited |
| 503 | LOGIN_SOURCE_UNAVAILABLE | Login Source Unavailable |
| 503 | REGISTRATION_SOURCE_UNAVAILABLE | Registration Source Unavailable |
| 503 | AUTH_MUTATION_SOURCE_UNAVAILABLE | Authentication Mutation Source Unavailable |
| 503 | SERVICE_UNAVAILABLE | Service Unavailable |
| 500 | SYSTEM_ERROR | System Error |

登録・ログイン・logout-all・password変更の429には正の秒数の `Retry-After` を付ける。接続元不明503には付けず、運用設定の確認を求める。
各APIの制限・接続元検証・受付順序は[Auth仕様](auth.md)を参照する。

### DB障害の分類（AUD-10 / Issue #114）

B1のlogout-all、B2のpassword変更とログイン発行確認は、確認済みのUser行ロックtimeout/deadlockだけを503 SERVICE_UNAVAILABLEへ変換する。
未知/設定エラーは500、旧JWT/世代不一致は401 UNAUTHENTICATED。loginの照合後に世代/hash等が変わった場合は401 INVALID_CREDENTIALS。
logout-allの成功はcommit後204・bodyなし。同JWTでの再送は401であり、bodyに他人のuidを指定しても操作対象にはならない。
password変更もcommit後204・body/新tokenなし。currentPassword不一致は401 INVALID_CREDENTIALS、
世代/hashの競合は401 UNAUTHENTICATED、入力は400 VALIDATION_ERROR＋errors.currentPassword/newPassword（拒否値なし）。
監査故障等のrollbackではhash/世代/監査は一括不変。通信断等でcommit結果が不明なケースまで未更新と保証しない。
この認証管理の503と、既存の商品/注文の409 CONCURRENT_UPDATEを混同しない。詳細は[認証契約](auth.md#jwtライフサイクルと失効制約)。

prodの待機上限と停止契約は[DB lifecycle（AUD-11 / Issue #116）](db-lifecycle.md)を参照。
pool取得・query timeoutの503、行ロック待ちの409と、接続断後の未分類なcommit/rollback失敗500を区別する。
接続断の全段階を一律503にはしない。どの5xxでも書込み結果を確認し、対応APIの同key/同内容retry契約に従う。

有効なJWTでもユーザーの照会にはDBが必要。DB接続不能・切断（JDBC SQLState class 08）、
`SQLTransientConnectionException`、`SQLRecoverableException`、接続取得/クエリのtimeout
（`SQLTimeoutException` / Spring `QueryTimeoutException`）、Spring `TransientDataAccessResourceException`
は、MVCとJWTフィルタで共通の **503 SERVICE_UNAVAILABLE** とする。
detailは「一時的にサービスを利用できません。時間をおいて再試行してください」で固定し、6フィールド形式を維持する。
復旧時刻は不明なためRetry-Afterは付けない。503はJWTやパスワードの不正を意味せず、有効JWTを破棄する理由にしない。

一律にDataAccessExceptionやCannotCreateTransactionExceptionを503にしない。原因の型/SQLStateが上記に合致しない障害、
SQLState class 28（DB認証設定）/42（SQL・schema）の原因がある障害、プログラム例外は **500 SYSTEM_ERROR**。
これらは運用者による調査・修正が必要。既存の業務例外、排他競合409とMVCの4xxを優先する。
SQLStateや例外message/stack trace、接続URL、ユーザー名、認証情報は応答・application eventに含めない。
診断は`system.database_unavailable`（WARN）または`system.unhandled_error`（ERROR）の型情報とrequestIdを使う。

503は処理未実行や自動復旧の保証ではない。書込みは結果を確認し、冪等性対応APIでは同じkey/内容の再送契約に従う。
JWT不正・期限/issuer不正・削除ユーザー・未認証は401、認可不足403、受付制限429、接続元不明の専用503は従来どおり。

malformed JSON/missing/null body、parameter type mismatch、firewall拒否は400 INVALID_REQUEST。
Idempotency-Key missingだけINVALID_IDEMPOTENCY_KEY。他required headerのmissingはINVALID_REQUEST。
その他Spring MVC標準4xxは元のHTTP statusを維持しINVALID_REQUESTと固定detailで返す。

## 注文作成の明細数（AUD-08 / Issue #112）

`POST /api/v1/me/orders` のitemsは1〜100件。0件は既存400 VALIDATION_ERROR / errors.itemsを維持し、
101件以上も400 VALIDATION_ERROR、errors.items「注文明細は100件以内で入力してください」を返す。
明細の内容が正しくても上限を超えれば拒否し、重複IDを合算して件数を減らさない。
Serviceを直接呼ぶ経路は同じ上限を、既存400 INVALID_ORDER_REQUESTと同じ固定detailで拒否する（errorsなし）。
既存の6フィールドProblemDetail、Validation時だけのerrors、JWTの401/403、keyの400、内容競合の409は維持する。
指紋計算・業務Repository照会/商品ロック/更新より前の検証で、失敗した要求は冪等keyを予約しない。
上限超過は既存keyでも400が先になり、過去の100超の注文の参照/CSV/状態変更は影響を受けない。
HTTP認証のuser lookupやJSON受信のコストまでなくなる制限ではない。詳細は[注文仕様](orders/order-aggregate.md#作成の明細数上限aud-08--issue-112)。

Issue #2: GET `/products` と `/products/search` のsortはid/name/price/stock/versionだけを許可する。
未知の項目はRepositoryアクセス前に400 INVALID_REQUESTで拒否し、detailは固定の
`リクエストの形式が不正です`。入力sort・内部クラス名は返さない。
page/sizeはSpring Data標準resolverを使用し、page不正は0、size不正は20となる。
Product一覧/検索だけはService normalizerでsizeを最大100へ制限する。

## Validation

```json
{
  "type": "https://inventory-management-system.example/problems/validation-error",
  "title": "Validation Error",
  "status": 400,
  "detail": "入力内容にエラーがあります",
  "instance": "/api/v1/me/orders",
  "code": "VALIDATION_ERROR",
  "errors": {"items[0].quantity": "数量は1以上を入力してください"}
}
```

nested pathを潰さない。同じfieldに複数違反があるときはconstraint code、messageの昇順で先頭1件を採用する。
NotBlankはPattern/Sizeより先になる。field順も安定させるがJSON property順に依存したclient実装は不要。
errorsはfieldとannotationの安全なmessageのみ。rejectedValue、password/username実値、request bodyを返さない。

### 商品名の入力境界（AUD-05 / Issue #106）

ADMINの `POST /products` と `PUT /products/{id}` のnameは、既存のNotBlankに加えて**最大255 Unicodeコードポイント**を要求する。
ASCII、日本語のBMP文字、補助平面の絵文字はそれぞれ1として数える。255絵文字も受け付ける（JavaのUTF-16では510単位）。
結合文字やZWJで連結した絵文字は見た目の1文字ではなく、構成コードポイントごとに数える。trim・Unicode正規化はしない。

Java Bean Validation標準の `@Size` はUTF-16単位で数えるため、ここでは `@CodePointSize(max=255)` を使う。
これはFlyway V1のutf8mb4 `products.name VARCHAR(255)` と、OpenAPIの両request schemaの `maxLength: 255` に合わせた制約。
注文時の商品名snapshotも既存のVARCHAR(255)のままで、DB migrationは変更しない。
数え方の根拠は [MariaDB VARCHAR](https://mariadb.com/docs/server/reference/data-types/string-data-types/varchar)、
[Java17 String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)、
[JSON Schema maxLength](https://json-schema.org/draft/2020-12/json-schema-validation#name-maxlength) を参照する。

255コードポイントは200で受け付け、256以上・未対サロゲートはService/DBへ渡す前に400 `VALIDATION_ERROR` と `errors.name` を返す。
null・空文字・空白のみの既存拒否も維持する。nameの入力値はerrorsへ含めない。
400の際は商品・version・stock・注文・在庫履歴を変更しない。PUTのversion必須・競合409・stock更新禁止、401/403等の契約は維持する。

```json
{
  "type": "https://inventory-management-system.example/problems/validation-error",
  "title": "Validation Error",
  "status": 400,
  "detail": "入力内容にエラーがあります",
  "instance": "/products",
  "code": "VALIDATION_ERROR",
  "errors": {"name": "商品名は255コードポイント以内の正しいUnicodeで入力してください"}
}
```

## Auth / Security

```json
{
  "type": "https://inventory-management-system.example/problems/invalid-credentials",
  "title": "Invalid Credentials",
  "status": 401,
  "detail": "ユーザー名またはパスワードが正しくありません",
  "instance": "/auth/login",
  "code": "INVALID_CREDENTIALS"
}
```

user不存在/password違いで全fieldが同一。Auth Validationは400 VALIDATION_ERROR、duplicateは409を維持する。
protected endpointのanonymous、invalid/expired JWT、DBにuserがないJWTは401 UNAUTHENTICATED、detailは「認証が必要です」。
role不足は403 ACCESS_DENIED、detailは「この操作を実行する権限がありません」。
filterとmethod securityはinstance以外の全fieldが同一。instanceは各request pathとなる。
SecurityはSpring管理ObjectMapperと共通factoryを使い、status/content-type/UTF-8を明示する。
JWT parse/署名/issuer/DB role優先、CSRF/session/matcher/roles/permitAllは変更しない。
firewallのDispatcherServlet前の拒否も共通writerで400にする。

## 商品検索keywordの入力境界（Issue #78）

`GET /products/search?keyword=...` の `keyword` は**必須・1〜100 Unicodeコードポイント・空白だけは不可**。
Java `Character.isWhitespace` / `Character.isSpaceChar` と U+0085 (NEXT LINE) を空白として扱い、Unicode White_Space 全25種のみの入力を400にする。未対のUTF-16サロゲートも400でRepository呼出前に拒否する。
U+200B / U+FEFF は空白の定義に含めず、置換文字U+FFFDも有効なUnicodeとして保持する。HTTPデコーダが不正なバイトを置換した場合、元のバイト列はServiceで識別できない。生のHTTP request-targetの構文拒否はWebサーバーの責務であり、MockMvcの未対サロゲート検証と区別する。
Tomcatのquery parameterデコードが不正UTF-8/不正percent escapeを拒否した場合も、500へ変換せず400 `INVALID_REQUEST` の固定ProblemDetailに統一する。Tomcatのparameter size制限による413等の4xxは維持する。生HTTP・実Tomcat・MariaDBで認証済400/未認証401とProductRepository未呼出を検証し、パーサーのmessage/入力値は公開しない。
`null`/空文字/空白のみ（NBSP・全角スペース等）/101コードポイント以上の文字列は、Repositoryを呼ぶ前に拒否する。
不足query parameterはSpring標準の400。URL長制限等によりServletの前段で拒否される場合もあり得る。
成功時は従来と同じ部分一致（caseの扱いはDB collation依存）で、前後の空白・特殊文字をtrim/正規化/小文字化しない。
すでに空文字で全件候補を検索していたclientは**400への契約変更**となるため、全件取得には `GET /products` を使う。

~~~json
{
  "type": "https://inventory-management-system.example/problems/invalid-request",
  "title": "Invalid Request",
  "status": 400,
  "detail": "リクエストの形式が不正です",
  "instance": "/products/search",
  "code": "INVALID_REQUEST"
}
~~~

既存Product一覧・検索のpage/size/sort、注文・Inventory APIの契約は変更しない。
この入力制約は巨大/空白検索を抑制するだけで、先頭ワイルドカードによるテーブル走査や負荷への根本的な対策ではない。性能は実データ量で別途測定する。

## Product resource未存在（Issue #35）

```json
{
  "type": "https://inventory-management-system.example/problems/product-not-found",
  "title": "Product Not Found",
  "status": 404,
  "detail": "商品が存在しません",
  "instance": "/products/999",
  "code": "PRODUCT_NOT_FOUND"
}
```

GET /products/{id}、ADMINのPUT/DELETE /products/{id}は未存在なら同じ404契約となる。
同じpathなら全fieldが一致する。従来のGET/PUTの500・DELETEの未存在成功からの仕様変更。
認証済みUSERのPUT/DELETEは商品の存在に関係なく403 ACCESS_DENIED、anonymousは3操作とも401。
成功時のGET/PUT/DELETEは200を維持する。空の一覧・検索は200のempty pageであり404にしない。

PUTはrequest/version Validation → 商品lookup → version一致確認 → 更新の順で処理する。
有効な非負versionを指定しても商品自体がなければ404、既存商品のversion不一致は409 CONCURRENT_UPDATE。
versionのnull/負値はHTTPのBean Validationで400 VALIDATION_ERROR（errors.version）。
Service直接呼び出しのrequest/version防御は従来の400 INVALID_PRODUCT_UPDATEを維持する。
name/priceのValidationも400 VALIDATION_ERRORのまま。Issue #39からPUTへのstock注入は400 INVALID_REQUEST。

数値に変換できないidは400 INVALID_REQUEST。0/-1には新しいpath制約を追加せずlookup結果で404とする。
注文作成でのproductId未存在は従来の400 INVALID_ORDER_REQUESTを維持し、注文/在庫/冪等keyを変更しない。
DELETEは同じTransactionでProduct行ロックと存在確認後、参照チェックを経てentityを削除・flushする（Issue #41）。
外部キー制約等のRepository失敗をPRODUCT_NOT_FOUNDへ変換しない。

## Concurrency / 500

optimistic/pessimistic lock、timeout、Spring ConcurrencyFailureExceptionは既存通り409。
```json
{
  "type": "https://inventory-management-system.example/problems/concurrent-update",
  "title": "Concurrent Update",
  "status": 409,
  "detail": "商品または注文が他の処理で更新されています。最新の情報で再試行してください",
  "instance": "/products/1",
  "code": "CONCURRENT_UPDATE"
}
```

全APIの予期しない500はSYSTEM_ERROR、固定detail「予期しないエラーが発生しました」。
exception message/class/stack、SQL/constraint/DB URL/file path、password/hash/token/JWT secretを露出しない。
新しいSystem.out.printlnやcredential loggingは追加しない。

ProductNotFoundExceptionだけを404 PRODUCT_NOT_FOUNDへ変換する。
Product APIでも予期しないRuntimeException・対象外の制約違反は安全な500 SYSTEM_ERRORを維持する。
DELETEの2種類のProduct参照FK違反だけは、下記の409 PRODUCT_DELETE_CONFLICTへ限定変換する。

## OpenAPI / 検証 / 対象外

ApiProblem schemaは標準5fieldとcode、Validation用errorsを表示し、共通customizerで主要endpointの4xx/5xxを
application/problem+jsonへ揃える。成功schema/contentは変更しない。
Auth register/login、order create、sales、product updateとProduct GET/PUT/DELETEの404を実/v3/api-docsで確認する。

Docker不要のProblemDetailContractTestと、MariaDB12.2.2/実SecurityのProblemDetailContractITを追加する。
既存AuthApiIT、AuthorizationMatrixIT、Idempotency等を削除せず、Map期待値を正式な契約へ更新する。
Accept: application/jsonでも実responseはapplication/problem+jsonを優先する。

Issue #33/#35のエラー統一ではFlyway V1〜V4・schema/型・JWT/Security policy・CI/CD設定を変更していない。
i18n、trace ID/structured logging（Issue #3）、例外階層の全面再設計、GraphQL、OAuth2/OIDC、特殊method全対応は対象外。

参照: [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)、
[Spring Error Responses](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-rest-exceptions.html)。

## 在庫調整・履歴（Issue #39）

Product PUTはmetadataのみ。stockまたは未知field付きJSONは400 INVALID_REQUEST。
Product POSTのinitial stock Validationは維持する。
ADMIN手動調整でdelta null/0・reason欠落・note 501文字は400 VALIDATION_ERROR。
注文reasonや未知enumは400 INVALID_REQUEST。Service直接呼出の不正入力は400 INVALID_INVENTORY_ADJUSTMENT。
現在stockにdeltaを加算すると負数/overflowになる場合は409 INVENTORY_ADJUSTMENT_CONFLICT。

~~~json
{
  "type": "https://inventory-management-system.example/problems/inventory-adjustment-conflict",
  "title": "Inventory Adjustment Conflict",
  "status": 409,
  "detail": "現在の在庫にこの調整を適用できません",
  "instance": "/api/v1/admin/products/1/inventory-adjustments",
  "code": "INVENTORY_ADJUSTMENT_CONFLICT"
}
~~~

調整・履歴ともADMINの商品未存在は404 PRODUCT_NOT_FOUND、USER403・未認証401をlookup前に返す。
履歴保存失敗は安全な500 SYSTEM_ERRORとなり、business transaction全体をrollbackする。
履歴ありProductのDELETEはIssue #41から409 PRODUCT_DELETE_CONFLICTとなり、SQL/constraint名を公開しない。
local OpenAPIにrequest/responseとProblemDetail 400/401/403/404/409を掲載し、prod docs404は維持する。
詳細は[在庫仕様](inventory.md)を参照。

## 商品削除競合（Issue #41）

注文履歴または在庫履歴があるProductのADMIN DELETEは、従来の500 SYSTEM_ERRORから409へ変更する。
両方から参照される場合も同じ固定detailを返し、どの参照があるかは公開しない。

~~~json
{
  "type": "https://inventory-management-system.example/problems/product-delete-conflict",
  "title": "Product Delete Conflict",
  "status": 409,
  "detail": "注文履歴または在庫履歴から参照されているため商品を削除できません",
  "instance": "/products/123",
  "code": "PRODUCT_DELETE_CONFLICT"
}
~~~

USERは403、anonymousは401をProduct lookup前に返す。ADMINの商品未存在は参照チェック前に404、
参照のない商品の削除成功は200を維持する。削除以外のAPI契約・Idempotency-Keyは変更しない。
DELETEはREAD_COMMITTEDのTransaction内でProduct PESSIMISTIC_WRITE→OrderItem/InventoryMovementのexists check
→delete→flushを行う。注文作成・手動調整・cancelと同じProduct lockで直列化し、stock/historyを保全する。

FK RESTRICTは最終防御として維持する。delete/flushのDataIntegrityViolationExceptionは、cause内の
SQLExceptionがMariaDB error 1451かつdriver messageの正確なconstraint名が
`fk_order_items_product`または`fk_inventory_movement_product`の場合だけ専用例外へ変換する。
未知constraint・別FK・他error code・connection/syntax failureは安全な500 SYSTEM_ERRORとなる。
responseにSQL・SQLException・constraint名・内部table名・password/hashを含めない。
409/失敗時にTransactionをrollbackし、Product/Order/OrderItem/InventoryMovementとstockを維持する。

DELETE OpenAPIの409はapplication/problem+json・ApiProblemを使用する。
Issue #41ではFlyway V1〜V5不変、migration追加なし、cascade削除なし、新しいDB権限なし。
soft delete・archive・商品状態追加・Product復元・履歴削除は対象外。

## 手動調整の冪等性（Issue #43）

ADMIN POST /api/v1/admin/products/{id}/inventory-adjustmentsはIdempotency-Key必須。
missing/空/空白/非ASCII/129文字以上は400 INVALID_IDEMPOTENCY_KEY。
制御文字も拒否し、注文と同様にfirewallが先に拒否する改行等は400 INVALID_REQUEST。
USER403・未認証401をheader/body解析・商品lookup前に返す。
同actor/keyの別商品・delta・reason・正規化noteは409 IDEMPOTENCY_KEY_CONFLICT。
detailは固定の「同じIdempotency-Keyに対する在庫調整内容が競合しています」。
同内容replayは200で最初のmovementを返し、現在stockを再計算しない。

対象UNIQUEのMariaDB 1062だけ同じ409へ変換する。失敗Transactionの再照会はしない。
その他のSQL/INSERT/flush障害は500 SYSTEM_ERRORで全体rollbackし、失敗keyを消費しない。
ProblemDetailは既存type/title/status/detail/instance/code契約とapplication/problem+jsonを維持する。
SQL・constraint・内部table・key/hash・password等を公開しない。
local OpenAPIに必須headerと400/401/403/404/409 ApiProblemを掲載する。
成功DTO/schemaと履歴GETに冪等性metadataを追加しない。prod docs404は維持する。
詳細は[在庫仕様](inventory.md)を参照。

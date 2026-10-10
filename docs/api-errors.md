# APIエラー契約（Issue #33 / #35）

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
| 409 | ILLEGAL_ORDER_STATE_TRANSITION | Illegal Order State Transition |
| 409 | ORDER_INVENTORY_RESTORATION_FAILED | Order Inventory Restoration Failed |
| 415 | UNSUPPORTED_MEDIA_TYPE | Unsupported Media Type |
| 500 | SYSTEM_ERROR | System Error |

malformed JSON/missing/null body、parameter type mismatch、firewall拒否は400 INVALID_REQUEST。
Idempotency-Key missingだけINVALID_IDEMPOTENCY_KEY。他required headerのmissingはINVALID_REQUEST。
その他Spring MVC標準4xxは元のHTTP statusを維持しINVALID_REQUESTと固定detailで返す。

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
name/price/stockのValidationも400 VALIDATION_ERRORのまま。

数値に変換できないidは400 INVALID_REQUEST。0/-1には新しいpath制約を追加せずlookup結果で404とする。
注文作成でのproductId未存在は従来の400 INVALID_ORDER_REQUESTを維持し、注文/在庫/冪等keyを変更しない。
DELETEは同じTransactionで存在確認後にentityを削除する。新しいlockは追加せず、既存の競合409処理を使う。
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
Product APIでも予期しないRuntimeException・制約違反は安全な500 SYSTEM_ERRORを維持する。

## OpenAPI / 検証 / 対象外

ApiProblem schemaは標準5fieldとcode、Validation用errorsを表示し、共通customizerで主要endpointの4xx/5xxを
application/problem+jsonへ揃える。成功schema/contentは変更しない。
Auth register/login、order create、sales、product updateとProduct GET/PUT/DELETEの404を実/v3/api-docsで確認する。

Docker不要のProblemDetailContractTestと、MariaDB12.2.2/実SecurityのProblemDetailContractITを追加する。
既存AuthApiIT、AuthorizationMatrixIT、Idempotency等を削除せず、Map期待値を正式な契約へ更新する。
Accept: application/jsonでも実responseはapplication/problem+jsonを優先する。

Flyway V1〜V4・schema/型・注文Transaction・JWT/Security policy・CI/CD設定は変更しない。
i18n、trace ID/structured logging（Issue #3）、例外階層の全面再設計、GraphQL、OAuth2/OIDC、特殊method全対応は対象外。

参照: [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)、
[Spring Error Responses](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-rest-exceptions.html)。

# 注文APIの認可と所有者境界（Issue #5）

注文APIをselfとadminに分離する。認証済みであるだけで全注文を閲覧・操作できた旧 `/orders/**` は廃止する。
クライアントは以下のURLへ更新する。成功時の200とOrderResponseの形式は維持する。

## APIと権限

| API | USER | ADMIN |
| --- | --- | --- |
| POST /api/v1/me/orders | 自分の注文を作成 | 自分の注文を作成 |
| GET /api/v1/me/orders | 自分の注文のみ | 自分の注文のみ |
| GET /api/v1/me/orders/{id} | 自分の注文のみ | 自分の注文のみ |
| POST /api/v1/me/orders/{id}/cancel | 自分のPENDINGのみ | 自分のPENDINGのみ |
| GET /api/v1/admin/orders | 403 | 全注文 |
| GET /api/v1/admin/orders/{id} | 403 | 任意の注文 |
| POST /api/v1/admin/orders/{id}/ship | 403 | PENDINGのみ |
| POST /api/v1/admin/orders/{id}/complete | 403 | SHIPPEDのみ |
| POST /api/v1/admin/orders/{id}/cancel | 403 | PENDINGのみ |
| PATCH /api/v1/admin/orders/{id}/cancel | 403 | POST cancelと同じ処理 |
| PATCH /api/v1/admin/orders/{id}/status | 403 | 既存の業務コマンドへ振り分け |
| GET /api/v1/admin/orders/sales | 403 | 全注文の既存集計 |
| GET /api/v1/admin/orders/export | 403 | 全注文のCSV |

ADMINもself APIでは所有者境界に従う。任意の注文への操作はadmin APIを使用する。
注文作成のcustomerは引き続きCurrentUserで決め、body/queryのcustomerId・username・roleを使用しない。
レスポンスのcustomerはid/usernameのみで、password hashやroleを返さない。

## SecurityとHTTP status

SecurityConfigのrequestMatchersがadminをROLE_ADMIN、selfをROLE_USER/ROLE_ADMINに限定する。
有効化済みのMethod Securityも使用し、AdminOrderController/SelfOrderControllerのクラスに
同じ条件の `@PreAuthorize` を付ける。管理ServiceメソッドのHTTP入口はAdminOrderControllerに限定する。
JwtAuthenticationFilterは署名・issuer・有効期限を検証し、usernameから実UserDetailsServiceで
現在のDB roleを読み込む。JWTのrole claim、body、queryから権限を決めない。

| 状況 | HTTP status |
| --- | --- |
| 未認証で注文APIへアクセス | 401 |
| USERがadmin APIへアクセス | 403 |
| self APIで他人の注文 / 存在しない注文 | 同じ本文の404 ORDER_NOT_FOUND |
| ADMINが存在しない注文を指定 | 404 ORDER_NOT_FOUND |
| 不正状態遷移 / 二重cancel | 409 |
| 入力Validation違反 | 400 |
| 廃止した /orders および /orders/** | 未認証401、認証済み403 |

SecurityのAccessDeniedHandlerとGlobalExceptionHandlerのAccessDeniedException処理は403を返す。
エラー形式全体の変更は行わない。既存のJWT検証・401処理を維持する。

## Repositoryと所有者付きロック

CurrentUserが解決したcustomer IDを使い、DB queryに所有者条件を含める。

- 一覧: `findAllByCustomerId(customerId)`。全件取得後のJava filterは行わない。
- 詳細: `findByIdAndCustomerId(id, customerId)`。他人と不存在は同じ空結果・404。
- cancel: `findByIdAndCustomerIdForUpdate(id, customerId)`。
  `where id = :id and customer.id = :customerId` とPESSIMISTIC_WRITEを同じqueryに適用する。
  通常findで所有者を調べてから別途lockする経路は作らない。

self/admin cancelは、ロック済みOrderに対する同じ復元処理を使う。
READ_COMMITTEDでOrderロック・状態検証・Product ID昇順ロック・復元後在庫の事前検証・更新を行う。
Orderのrefresh、Math.addExact、全体rollback、状態遷移ルールは従来通り。
customerは保存後に変更できないため、ロック待ち後のrefreshでも所有者は変わらない。

customer_idのNOT NULL/FKとidの主キーはIssue #14で導入済み。
実MariaDBではFKに必要なcustomer_id先頭のindexがあることも検証する。
今回のqueryに新たなDDLは不要。将来のPageableはcustomer条件を維持してRepositoryに追加できる。

## テスト

Java 17とDockerを使用し、`./mvnw clean verify`（Windows: `mvnw.cmd clean verify`）を実行する。
Issue #4でUnitはSurefire、`*IT` はFailsafeへ分離した。CIとPR前の品質ゲートは `verify`。
Issue #5では既存247件を維持し、認可84件・所有者Service 7件・MariaDB回帰6件を加えた全344件が成功している。

- AuthorizationMatrixIT: 実SecurityFilterChain・JWT filter・UserDetailsService・MariaDB 12.2.2。
  filtersを無効にせず、Security/Repository/Serviceのmockも使用しない。
  anonymous/USER A/USER B/ADMINとself/admin全主要API、拒否時のDB不変、最小限のレスポンスを検証する。
  他人/不存在の同一404、role偽装・DB role変更、直接Controller呼出しのMethod Security、旧入口の拒否も確認する。
- 同じ統合テストで実DBの一覧・詳細・所有者付きlock queryと既存indexを検証する。
- 実JWTでowner cancelとADMIN shipを同時実行し、200/409、所有者、最終状態・在庫・versionを3回検証する。
- OrderOwnershipServiceTest: scoped queryだけを使い、他人/不存在でProduct更新を行わないことを確認する。
- InventoryConcurrencyMariaDbIT（旧InventoryConcurrencyMariaDbTest）: 独立DB接続でowner cancel対ADMIN shipを5回検証し、他人のcancelも拒否する。
  従来の20/100同時注文、20同時cancel、逆順商品ロック、Product version競合を維持する。
- Issue #9〜#14のJWT、Validation、不変条件、全16状態遷移、集約、snapshot、DB制約、rollbackを維持する。

## Out of scope

Flyway（#15）、CD（#17）、売上集計仕様、OAuth2、JWT方式の再設計、Order集約の再設計、
並行制御方式の変更、在庫履歴APIは変更しない。本番DBへの接続・適用も行わない。

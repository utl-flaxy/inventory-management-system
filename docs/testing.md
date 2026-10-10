# テスト品質ゲート

## Java 17・実行コマンド

高速なローカル回帰（Docker不要）:

```bash
./mvnw test
```

完全検証（PR前必須・GitHub CIと同じ品質ゲート）:

```bash
./mvnw verify
```

Windowsでは `mvnw.cmd test` / `mvnw.cmd verify`。Java 17をJAVA_HOMEで選択する。
古いレポートを消して件数を比較する場合は `clean test` / `clean verify` を使う。
`test` の成功だけではmerge可能とは判断しない。

## 命名と実行phase

| 分類 | 命名 | plugin / phase | 対象 |
|---|---|---|---|
| Unit / lightweight | `Test*`, `*Test`, `*Tests`, `*TestCase` | Surefire / test | domain、Mockito、JWT、Controller slice、H2 JPA補助 |
| Integration | `*IT` | Failsafe / integration-test + verify | MariaDB、Flyway、実Security + JWT + DB、実lock/Transaction |

Surefire/FailsafeのversionはSpring Boot parent管理（現行3.5.5）を使う。
標準[Failsafeのライフサイクル](https://maven.apache.org/surefire/maven-failsafe-plugin/usage.html)を使用し、
ITの失敗はverify goalで非zero終了にする。独自runnerやfailure-ignoreは使わない。
CIはJava 17で `./mvnw verify` を実行し、timeoutは10分を維持する。

Unit tests: Surefire、Integration tests: Failsafe。両方の件数・failure/error/skippedはMavenログに表示される。
レポートは `target/surefire-reports` / `target/failsafe-reports`（標準配置）。
`verify` はcompile → Unit → package → Integration → verifyを実行する。

## 必須MariaDB統合テスト

起動済みDockerとMariaDB 12.2.2イメージの取得が必要。CIはDockerが使えるrunnerを前提とする。
TestcontainersをDocker未起動時にskipしない。接続不能やpull失敗はbuild失敗となる。
本番DB・EC2・本番秘密値は使わず、各classの使い捨てMariaDBを使う。

| class | 回帰対象 |
|---|---|
| AuthorizationMatrixIT | 実SecurityFilterChain、JWT、DB role、所有者/ADMIN境界、DB不変、並行操作 |
| InventoryConcurrencyMariaDbIT | 旧InventoryConcurrencyMariaDbTest。実lock、同時注文/cancel、version、rollback |
| SchemaConstraintIT | Flyway + validate、MariaDB NOT NULL/CHECK/FK/UNIQUE/index |
| EmptyDatabaseMigrationIT | 空DB migration、実起動・再起動、schema drift、baseline拒否 |
| LegacyDatabasePreflightIT | 既存DBの不正データ/不足履歴での停止とDB不変 |
| AuthApiIT | Authの400/409/401契約、UTF-8/文字数境界、10件同時登録、BCrypt、非露出、OpenAPI、DB role優先 |
| ProblemDetailContractIT | 実Security/DB、主要4xx、JWT/DB user、filter/method 403一致、firewall、在庫不変、OpenAPI Problem schema |
| ProductNotFoundIT | 実MariaDB/SecurityのGET/PUT/DELETE未存在404、認可優先、DB不変、stale/Validation、注文冪等性、OpenAPI |
| ProductDeletionIT | Product参照409、限定FK fallback・rollback、認可優先、delete/order/manual/cancel並行整合、OpenAPI |
| SalesQueryIT | COMPLETEDのSUM/COUNT、全4状態、期間境界、legacy NULL、UTCマッピング |
| MoneyApiIT | 高額Product/Order API、snapshot/CSV、OpenAPI int64、Long overflow時DB不変 |
| MoneyMigrationIT | 既存値/V1履歴保持、V1→V2、BIGINT境界と制約、再実行なし |
| SalesApiIT | 固定Clock、実JWT/ADMIN、日時400、期間境界、完了日時レスポンス/OpenAPI |
| CompletionMigrationIT | V1/V2→V3、既存値/履歴保持、legacy NULL、validate、条件付き手動補完 |
| IdempotentOrderIT | header必須、実JWT、replay/conflict、snapshot、20同時送信×3、mixed body、scope、legacy、OpenAPI |
| IdempotencyMigrationIT | V3→V4、既存全4状態/履歴保持、ascii_bin列、customer/key UNIQUE、validate、再起動pending0 |
| ProductionProfilesIT | 実prod/local/default起動、専用user最小権限/Flyway、Swagger/H2/Actuator/health/header/JWT、production jarのH2除外 |

Issue #37のProductionConfigurationTestは実prod設定を読み、DataSource/Flyway生成前の必須設定不足、
blank、root、credential付きURL、弱いJWT鍵の停止と非露出を検証する。
ConfigurationContractTestはsafe defaults/local差分・Boot管理Actuator・H2 test scopeを固定する。
ProductionProfilesITはComposeと同じdb-init scriptで専用user権限を絞った実MariaDB12.2.2で
V1〜V5 migrationとHibernate validate、3profileの実HTTP、health details非公開を検証する。
既存OpenAPIテストだけlocalを明示し、production要件をtest profileで無効化しない。
Deployment testsはActuator target、labelに基づくlegacy/modern rollback、DB設定不足、prod必須、
root/不正identifier拒否、secret非出力と既存SHA/image/lock/app-only安全性を検証する。

Issue #27はmain基準519件を削除せず、売上の期待値を正式なCOMPLETED計上ルールへ更新する。H2補助テストはSurefireに残す。
Issue #27のJava 17検証はSurefire 288件 + Failsafe 277件 = 565件（46件追加）、failures/errors/skipped=0。
Docker接続先を無効にしたclean testでも288件成功し、Docker不要のUnit品質ゲートを維持する。
Issue #29は565件を削除せず、既存createテストへ一意keyを付ける。Docker不要のUnitを33件、実MariaDBのITを37件追加する。
Java 17でDocker接続先を無効にした `mvnw.cmd clean test` は321件成功。
`mvnw.cmd clean verify` はSurefire321件 + Failsafe314件 = 635件成功、failures/errors/skipped=0。
OrderRequestFingerprintTestは既知UTF-8/SHA-256ベクトル、明細順、ID/数量差、64文字lowercase hexを検証する。
OrderIdempotencyServiceTestはkey拒否・User lock順序・scoped lookup・replay/conflict時Product未アクセス/未保存・対象外制約を検証する。
IdempotentOrderITは実SecurityFilterChainとJWTを通す20同時HTTP送信×3、10+10異なるbody、別key/別customerをbarrierで同期する。
全Futureを回収し、Executor終了・timeoutを明示する。新APIはtest都合でheader optionalにしない。
H2の成功でMariaDB CHECK・lock・Flywayの正しさを代用しない。
DeploymentWorkflowTest / DeploymentScriptTestはYAML/Bashの静的・mock制御テストであり、
本番Docker配備を行わないためSurefireに残す。Deployの運用上の無効化状態は変更しない。

ITだけを開発中に選択する場合:

```bash
./mvnw verify -Dit.test=AuthApiIT,SalesQueryIT
```

このコマンドもUnitを実行するが、PR前には絞り込みなしのverifyを必ず実行する。
各ITのDBはclass単位で分離し、Authは毎回ユーザーを削除、SalesはTransaction rollbackで破棄する。
実行順への依存やstatic共有レコードは作らず、既存の並行実行timeoutも維持する。

## Authの入力・エラー契約（Issue #31）

登録はROLE_USERを強制し、clientのADMIN指定を無視する。DBのpasswordは実BCrypt hash。
User.passwordのJsonIgnoreを維持し、RegisterResponseのid/username/roleだけを返してpassword/hash非露出を保証する。
loginは署名・issuer・有効期限を持つJWTだけを返す。JWT payloadにpassword/hash/署名鍵は含めない。
認可にはJWTのrole claimを使わず、毎回usernameから取得した現在のDB roleを使う。

Issue #31で旧500/blank受理のcharacterization期待値を正式な4xx契約に更新する。既存635件は削除しない。
Java 17でDocker接続先を無効にした `mvnw.cmd clean test` はSurefire377件成功。
`mvnw.cmd clean verify` はSurefire377件 + Failsafe332件 = 709件成功（74件追加）、failures/errors/skipped=0。

- 両APIのusernameは3〜50文字のASCII英数字と._-、passwordはNotBlank/8〜72文字/UTF-8 72 bytes以下。trimなし。
- invalid inputは400でService/Repository/JWT未呼出。malformed JSON、missing/null bodyも400、未対応Content-Typeは415。
- 重複usernameは409 USERNAME_ALREADY_EXISTS。MariaDB1062とuk_users_usernameだけを識別し、他制約を奪わない。
- user不存在/wrong passwordは同じ401 INVALID_CREDENTIALS本文。予期しないAuth内部例外も固定messageに伏せる。
- AuthApiITは同username10件、異なるpassword10件、異なるusername10件の同時POSTをbarrierで開始し、1成功/9競合または全成功を確認する。

AuthControllerTest/AuthServiceTestはSurefire、実JWT/Security/DB/並行登録はAuthApiITのFailsafeで検証する。
usernameは現行utf8mb4_unicode_ciのcase-insensitive照合を維持し、username/password列255も維持する。
UTF-8の72/73 bytes境界を実BCryptと入力Validationで検証する。[Auth仕様・互換性](auth.md)を参照。

## Salesの現行仕様

Issue #27から、COMPLETEDかつcompletedAt非NULLのみをSUM/COUNTの単一DB queryで集計する。
PENDING/SHIPPED/CANCELLEDと完了日時不明のlegacy行は、全期間/期間集計ともに除外する。
空なら0L/0L、0円の完了注文も件数に含む。totalOrdersは売上対象注文件数であり、全注文数・明細数ではない。
固定Instantで下限inclusive/上限exclusive・片側省略・orderedAtを使わないこと・UTC DATETIME(6)のround tripを検証する。
OrderCompletionTest/SalesServiceTestはdomain invariantと不正期間時のRepository未呼出をDockerなしで検証する。
InventoryConcurrencyMariaDbITは20操作の同時completeを3回実行し、成功1件/他409相当・日時不変・1注文分の売上を確認する。
時刻の検証にはClockと固定Instantを使い、Thread.sleepを使わない。詳細は[売上仕様](sales.md)。

## 金額のLong/BIGINT回帰（Issue #25）

金額は円の整数値をLong/longで扱い、DBはV2でBIGINTへ移行する。V1は不変。
商品APIは1円以上でJSON number/field名を維持し、OpenAPIはint64。数量・在庫はint32のまま。
Money Value Object、BigDecimal、小数/多通貨は未導入。
Math.multiplyExact/addExactのlong演算で、注文のoverflowを在庫変更前に400で拒否する。
UnitのOrderAggregateTest/OrderServiceTestもLong境界に対応し、Dockerなしで実行する。
Integrationでは30億円×2個=60億円、複数明細180億円、価格変更後のsnapshot、CSVの整数出力、
0L/70億円のJPQL SUM(Long)・totalSales、V1→V2の20億円保持・制約・再起動を検証する。
Long.MAX_VALUEの商品も許可し、quantity=1は成功、quantity=2や合計のLong overflowは400となる。
API clientもint64を保持できる型で扱う。COMPLETEDの30億+40億=70億、PENDINGの50億/CANCELLEDの60億除外も検証する。

## 別Issueへ分離した内容

注文冪等性はIssue #29で[必須headerとreplay契約](orders/idempotency.md)を実装し、実IdempotentOrderITで検証する。
Issue #33でGlobalExceptionHandlerとSecurityの主要エラーをSpring ProblemDetailへ統一する。
ProblemDetailContractTestは全主要code/type/title/status、Validationの決定性/nested path、
JSON/body/header/media/type mismatch、synthetic 500の内部情報非露出をDockerなしで検証する。
ProblemDetailContractITは実Security + MariaDB12.2.2を必須実行する。
既存709件のMap前提はdetail/errorsへ更新し、注文所有者と不存在404はinstance以外の全field一致を維持する。
Authの不存在/password違いはinstanceも含めて同一ProblemDetailを維持する。
[APIエラー契約](api-errors.md)はエラーbodyの破壊的変更とstatus/code維持を明記する。
Issue #33のJava 17検証は、Docker接続先を無効にした `mvnw.cmd clean test` で408件成功。
`mvnw.cmd clean verify` はSurefire408件 + Failsafe350件 = 758件成功（既存709件 + 契約49件追加）、
failures/errors/skipped=0。新規ProblemDetailContractTest31件、ProblemDetailContractIT18件を含む。
coverage/scanner gate、branch protection変更、Deploy有効化、JWT_SECRET設定、EC2操作も対象外。

## Product resource未存在（Issue #35）

ProductServiceTestはlookup未存在の専用例外とRepository未保存/未flush/未削除、
存在する商品の取得/削除、Repository例外を404へ誤変換しないことをDockerなしで検証する。
ProductConcurrencyServiceTestのrequest/version防御・stale409も維持する。
ProductControllerTestはGET/PUT/DELETEの404全field/content-type、Service例外の安全な500、
INVALID_PRODUCT_UPDATEの400と成功DELETEの200を検証する。
ProblemDetailContractTestには専用例外とfactoryのtitle/type/detail/instanceを追加する。
ProblemDetailContractITの旧Product未存在500の期待値は、テストを残して正式な404契約へ更新する。

ProductNotFoundITは実MariaDB12.2.2・JWT・Securityを通し、3操作の404 body完全一致、
USERの既存/未存在PUT/DELETE403、ADMIN404、anonymous401、通常CRUD200を確認する。
各失敗前後のproducts/orders/order_items/inventory_movements全行を比較して副作用を検出する。
missing + version123は404、staleは409、HTTP version null/負値・name/price不正は400 Validation、stock付きPUTは400 INVALID_REQUEST、
malformed idは400、0/-1はlookup404、空の一覧/検索は200を固定する。
注文の商品未存在400と失敗時key再利用、参照商品削除の安全な409（Issue #41）、3操作のOpenAPI404も検証する。
Flyway V1〜V4、JWT/Security policy、CI/CDは変更しない。

Java 17でDocker接続先を無効にした `mvnw.cmd clean test` はSurefire427件成功。
絞り込みなしの `mvnw.cmd clean verify` はSurefire427件 + Failsafe376件 = 803件成功
（既存758件 + Unit19件 + IT26件追加）、failures/errors/skipped=0。
初回全verifyでは既存在庫並行テストの100worker接続取得がtimeoutしたが、
同じコード・設定の単独40件再実行と全verify再実行は成功。timeout/pool設定は変更していない。

## Issue #37の検証結果

Java 17でDocker接続先を無効化した`mvnw.cmd clean test`はSurefire470件成功。
絞り込みなし`mvnw.cmd clean verify`はSurefire470件 + Failsafe380件 = 850件成功、
failures/errors/skipped=0。main803件（427+376）を維持し、Unit43件 + IT4件を追加した。
803件はtestとintegration-testの合計で、Docker不要testだけの件数ではない。
MariaDB12.2.2の専用user/init権限とV1〜V4も成功。配備回帰は28 Workflow + 35 Script = 63件。
初期化script導入時のfocused verifyはGRANTのescape表示を旧assertが考慮せず3件失敗したが、
対象DBのescape済みgrantだけを許可するassertへ直し、focused/full verifyとも成功した。
実secret・既存本番DB・EC2は使用せず、Deploy workflowは無効のまま維持する。

## 在庫調整・履歴（Issue #39）

- InventoryMovementTest: Productのsigned調整、非0/非負/overflow、movement前後関係、reason/source、
  actor・note・microsecond時刻のdomain防御（Docker不要）。
- InventoryServiceTest: ADMIN/入力防御、既存Productロック/refresh、保存、fixed sort（Docker不要）。
- ProductControllerTest: stock null/0/負数/数値/string/objectの注入を400で拒否。
  既存Product PUT fixtureはmetadataだけへ更新し、stock不変を期待する。
- InventoryApiIT: 実JWT/ADMIN境界、手動reason/差分/note、underflow/overflow、副作用なし、
  Product POST/PUT/@Version、Page/同時刻の安定順序、ORDER_CREATED/ORDER_CANCELLED、
  actual actor、replay/conflict/double cancel、ship/complete履歴なしを検証する。
  履歴INSERT後flush→故障を注入し、manual/order/cancelの全行rollbackを検証する。
  20 concurrent manual、manual/order、manual/cancelの実lockと連続before/after chainを検証する。
  Hibernate dirty checkingで履歴が書き換わらないこと、削除FKと安全な409（Issue #41）、local OpenAPIも確認する。
- InventoryMovementMigrationIT: V4 legacyからV5、checksum/history/stock保持、raw SQL制約、
  binary enums、INT境界、FK RESTRICT、複合index/EXPLAIN、再起動/Hibernate validate。
- IdempotentOrderIT: 既存20 concurrent same-keyの3反復で1 movement/Productを追加検証し、
  sequential replay/mismatchもinventory_movementsを含む全行snapshotで確認する。
- ProductionProfilesIT: 対象DBだけに権限を持つ専用userでV1〜V5が成功し、
  prod/default/localのSQL/Swagger/H2/Actuator契約を継続検証する。

main基準の850件は削除しない。契約変更に必要な既存fixture・在庫期待値・Flyway最新versionを更新する。
Java 17でDocker接続先を無効化した `mvnw.cmd clean test` は520件、
failures/errors/skipped=0で成功した（mainのSurefire470件 + 50件追加）。
`mvnw.cmd clean verify` はSurefire520件 + Failsafe468件 = **988件成功**、
failures/errors/skipped=0。mainの850件 + 新規138件（Unit50 / IT88）を含む。
新規InventoryMovementTest34件、InventoryServiceTest8件、InventoryApiIT53件、
InventoryMovementMigrationIT35件に、既存Controller6件・ProblemDetail2件の追加を含む。
V5追加でDB全体のFK数は3→6となるため、SchemaConstraintITの期待値も正式schemaへ更新する。

## 商品削除競合（Issue #41）

main基準988件は削除しない。ProductNotFoundITとInventoryApiITの参照商品DELETEだけ、
旧500の期待値を正式な409 PRODUCT_DELETE_CONFLICTへ更新する。

- ProductServiceTest: Product lock→参照チェック→delete→flushの順、参照ありで未delete/未flush、
  missing時のチェック省略、cause内の1451と正確な2制約だけの変換を検証する。
  別FK・constraint名のprefix/suffix・error code違い・構造のないmessage・SQL causeなしを誤変換しない。
- ProductControllerTest / ProblemDetailContractTest: 新409の全field、content-typeと固定safe detail。
- ProductDeletionIT: 実MariaDB12.2.2・JWTでOrderItemだけ、Movementだけ、両方の409と全行snapshot不変。
  事前チェックをspyでfalseにして実flush FK失敗を409へ変換し、raw SQLでも1451と正確な制約名を確認する。
  未知constraint・別FK・他code・connection/syntax障害は安全な500でrollbackする。
  5種の存在/参照状態でUSER403・anonymous401がlookup前に確定すること、ADMIN missing404・未参照200、
  OpenAPI409 ApiProblemを検証する。delete/orderとdelete/manualを各5反復、delete/cancelを3反復し、
  整合するstatusと最終stock/history/order rowsだけを許可する。参照切れや500を許可しない。
- ProductionProfilesIT: prod/default/localの専用DB userで未参照200・missing404・履歴参照409を確認する。
  既存SELECT/DELETE権限で動作し、GRANTを増やさない。

Flyway V1〜V5・FK RESTRICT・履歴immutability・Idempotency-Key・JWT/Security・production設定を維持する。

Java 17 (Temurin 17.0.18)でDOCKER_HOSTを接続不能なローカルportへ向けた
`mvnw.cmd clean test` は **535件成功**（Docker不要）、failures/errors/skipped=0。
`mvnw.cmd clean verify` はSurefire535件 + Failsafe506件 = **1041件成功**、failures/errors/skipped=0。
main988件 + Unit15件 + ProductDeletionIT38件であり、既存テストを削除していない。
重点verify（Product Service/Controller/ProblemDetail、ProductDeletion/ProductNotFound/Inventory API/production profile）も
Unit80件 + IT121件で全件成功した。

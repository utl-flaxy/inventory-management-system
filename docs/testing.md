# テスト品質ゲート

## B3受入試験（Issue #140、2026-10-19）

着手mainは `e332b70209abf1e5de7a91c056e46d8992fc4acb`、[main CI](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39264090536)はcompleted/success・5ジョブ成功。
B2基準はJava 1380＋937＝2317件。B3 PR HEADの実測件数・CIはPR本文の検証欄で別に記録する。
[実HTTP/実MariaDB IT](../src/test/java/com/example/demo/security/AdminTokenRevocationIT.java)はADMIN自身の再認証、自己/他人失効、
独立並行増分/相互ADMIN/同時自己失効の接続・昇順ロック、actor hash/世代/role/削除、target再作成、logout/password競合、
commit/rollback・監査故障・overflow・401/403/404/429/503/500・JWT6否定系・秘密非露出・業務不変/pool残留0を確認する。
[V9 IT](../src/test/java/com/example/demo/repository/AdminAuditMigrationIT.java)は既存行/履歴保持と予期しないDDL状態での停止、
[境界unit](../src/test/java/com/example/demo/security/PasswordMutationBoundaryTest.java)は操作枠の独立/共有permit/偽装proxyを確認する。
Dockerでは元9段階とB1/B2を残してB3を追加する。全テストはskip/pool/timeout緩和なしで実施する。
Windows DatabaseTimeoutIT初回DB観測CLI3失敗、Caddy検証失敗・MariaDB証明書時刻の過去観測は根因未確定のまま保持し、今回の成功で消さない。
Trivy OS49、Flyway互換性WARN、異版rollback未検証、本番容量/SLA/復旧未保証も残余として扱う。

## 品質ゲートの参照方針（snapshot / live）

現在の結果は[main push CIのlive一覧](https://github.com/utl-flaxy/inventory-management-system/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)を開き、
先頭runのhead SHAと[main HEAD](https://github.com/utl-flaxy/inventory-management-system/commits/main/)の一致、必須5ジョブのstatus/conclusionを確認する。
未完了・失敗を過去の成功で置き換えず、PR CIはPR HEADの別の証拠として扱う。

以下は**2026-10-18 UTC確認の監査snapshot**。基準main **`0614d7d306fed5a3ec43ba7a7502b8bf7e65f3ed`**（PR #131 merge後）、
[main push CI 39136004541](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39136004541)は同SHAでcompleted/success、必須5ジョブSUCCESS。
main更新だけを理由にsnapshotを追従更新せず、現在の成否はliveで確認する。
**Java17 / Boot4.1.1、Surefire1,314 + Failsafe862 = 2,176、Failures/Errors/Skipped=0**。
Python29、Terraform mock27、Secret例外0/未解決0、SpotBugs SECURITY違反0、OSV169 components/0 advisory。
Trivyは別対象で**OS49件（MEDIUM27/LOW22）、library0**。audit-onlyの正常実行は脆弱性ゼロを意味しない。

main保護はstrictな必須5 checks、enforce_admins/linear_history有効、force push/delete禁止をread-only APIで確認。
必須review数は0で、独立レビューは人手の運用。Deployはdisabled_manually、実AWS適用・本番TLSは未実施。
詳細な対象・保証限界・監査史は[現行証跡](release-status.md)、再現可能な[隔離Dockerデモ](demo.md)を参照。
後続PRは自分のHEADでfull verifyと全5ジョブを実行し、基準mainの結果の使い回しだけで合格としない。
旧基準[main186aad8 / CI39123950859](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39123950859)、
[mainf154801 / CI39127003622](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39127003622)、
[main5f0fae4 / CI39109302086](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39109302086)も同2,176件で、当時のsnapshotとして区別する。
[設計とテストの対応表](design/transactions-and-locking.md#6-検証できていることとテストへの入口)から各競合・rollbackテストへ進める。
Windows DatabaseTimeoutITの初回観測CLI3失敗は根因未確定であり、[最終監査の失敗記録](audit/portfolio-v1-final-acceptance-2026-10-18.md#ローカル補助検証)を保持する。
単独再実行やLinux CI成功をWindows full verify成功へ読み替えず、pool/timeout緩和やskipで解消扱いにしない。

## 過去の品質ゲートsnapshot（2026-10-17）

基準main: `cf566817c46a94249bc732d7f0d183805b7afa73`。実POMは **Spring Boot 4.1.1 / Java 17**。
mainで確認済みのJava回帰基準は **Surefire 1,101 + Failsafe 760 = 1,861件**（Failures/Errors/Skipped=0）。
PR #98・#99はmerge済み。[このmainの実CI](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39004205548)でJava件数と全5ジョブSUCCESSを確認した。
以前の1,836件（1,080 + 756）は `78886413894b1211f77cee3ec3b6d7e5045f6534` / PR #96時点の記録として区別する。

CIは `./mvnw verify` に加えてTerraform fmt/init/validateとmock 27件、secret-scan、Java SECURITY静的解析、OSV/SBOM監査、使い捨てDocker検証とTrivy監査を実行する。
このmainのCI時点でMaven依存OSV検出は169 components / 0 advisoryだが、**TrivyのOS image findingは別の残余**であり、OSV0を全脆弱性の解消と解釈しない。
mainのbranch protection設定・本番Deploy・実AWS適用はこれらのCI成功とは別の運用確認事項。
実ジョブ定義は [ci.yml](../.github/workflows/ci.yml)、秘密/静的解析の検証は [CI品質ゲート](ci-quality-gates.md)、残余は [Epic #8](https://github.com/utl-flaxy/inventory-management-system/issues/8) を参照。

**読み方:** 以下にはIssue別に追加した当時の実装・検証記録が時系列で残る。
古い節の「現行」「baseline」「CI timeout」「Spring Boot 3.5」「Flyway 11」等は**そのIssueを実施した当時の情報**であり、冒頭2026-10-18のmainの構成・合格基準を上書きしない。検証時点の一次資料として保持する。

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

## Flyway / MariaDB互換性（Issue #69：当時の検証記録）

この節のcore/mysql 11.7.2・JDBC 3.5.7・warning95件はIssue #69の当時の選定・ベースラインであり、現mainのBoot4管理Flyway12系のversion記述ではない。現mainのFlyway実効version・既存DBリスクは [DB運用手順](db/README.md) を参照。

Issue #74のlogin回帰は[Auth設計とテスト](auth.md#制限と回帰検証)を参照。
既存1673件を維持し、追加のClock/BCrypt/proxy/429テストをfull verifyへ含める。
production log監査はresponseやsource literalではなく、実stdout/stderrを全logger対象に検証する。

[調査と対応判断](db/flyway-mariadb-compatibility.md)にeffective POM、公式情報の取得日/URL、
警告原文、候補Flyway 12.11.0の実起動失敗と現行維持の理由を記録する。
候補の18件失敗は調査用の一時version指定であり、採用版のテスト結果とは分ける。
通常のJava 17 `mvnw verify` はoverrideなしのcore/mysql 11.7.2 / JDBC 3.5.7で実行する。
既存Surefire 948 + Failsafe 725 = 1673件を削除/skipせず、F/E/S=0を要求する。
V1〜V6 fresh migration、Hibernate validate、再起動時のhistory/checksum、実backup/restore、
認証・注文・在庫・売上・CSV・ページング・readiness/livenessも既存ITで検証する。
baseline CI run 38131627192のFlyway warning 95件と、採用版の実logでの出現回数を比較する。
警告を消して件数を合わせない。実件数/CIはPRへ記録し、上流認証とは区別する。
Terraform mock27件とJava/Terraform両CIも維持する。

## request correlationとsafe logging（Issue #3）

最初にlogging回帰を実行し、その後Auth/JWT・注文・在庫の関連回帰、最後に`./mvnw verify`を実行します。
Windowsでは`mvnw.cmd`を使用し、Java 17とDockerを用意してください。

```powershell
mvnw.cmd '-Dtest=RequestCorrelationFilterTest,StructuredLoggingTest,BusinessEventLoggingTest,SafeExceptionLoggingTest,ConfigurationContractTest,JwtAuthenticationTest' test
mvnw.cmd '-Dtest=AuthControllerTest,JwtAuthenticationTest,OrderControllerTest,OrderStateControllerTest' '-Dit.test=AuthApiIT,InventoryApiIT,InventoryAdjustmentIdempotencyIT' verify
mvnw.cmd verify
```

Filterテストはserver UUID/header/MDC一致、client ID無視、request間のcleanup、downstream例外、
completion一度だけ、method/path/status/durationMs、header/query/body非参照を確認します。
Security sliceでもJWT rejectのWARNが同じrequestIdを持ち、401とparse一度を維持することを検証します。
prodのconfigを読み込んだlogging専用contextではBootの実console encoderでJSONを生成し、
`@timestamp/message/level/event/requestId`とtop-level MDCをparseして確認します。production DB/secretは不要です。

| event | level | 許可するevent固有field（requestIdはMDC） |
|---|---|---|
| `http.request.completed` | INFO | method, path, status, durationMs |
| `auth.register.succeeded`, `auth.login.succeeded` | INFO | userId, role |
| `auth.login.failed` | WARN | なし（credential失敗の詳細を区別しない） |
| `security.jwt.rejected` | WARN | exceptionType |
| `order.create.completed` | INFO | orderId, status, itemCount |
| `order.cancel.completed`, `order.ship.completed`, `order.complete.completed` | INFO | orderId, status |
| `inventory.adjustment.completed` | INFO | movementId, productId, delta |
| `system.unhandled_error` | ERROR | exceptionType, rootCauseType, topFrame |

application loggerをDEBUGでcaptureして、token/password/hash/username/key/fingerprint/query/note/
SQL/constraint messageとThrowable非出力を確認します。legacy statusもcommand eventは一度だけです。
Service/commit失敗時のfalse success防止、既知4xx/409のERROR非出力、cycleを持つcause chainも検証します。
logging追加前baselineはSurefire 654 / Failsafe 597 / total 1251で、既存テストを削除・skipしません。

`ProductionLoggingIT`は実prod起動・MariaDBのduplicate-keyを用いて、driver/frameworkも含む
実console出力からusername/key/constraint/SQL messageが漏れないことを確認します。
prodの`ErrorPacket`/`SqlExceptionHelper`だけをOFFにし、既存409・rollback・safe5xx eventを維持します。

## 注文一覧ページング（Issue #65）

開始時baselineはSurefire 857 / Failsafe 652 / total 1509。既存ケースを削除・skipしません。
JSON配列からPageResponseへの明示的な契約変更に伴う期待値・stubだけを同期します。
OrderPageableNormalizerTest（45件）、OrderPagingServiceTest（9件）、
OrderPagingControllerTest（36件）、OrderPagingIT（72件）を追加し、
ProductionLoggingITに実prodページング/不正sortの全logger秘密値監査を1件追加します。
期待件数はSurefire 947 / Failsafe 725 / total 1672（163件追加）。

実MariaDBでOSIV=false、fail_on_pagination_over_collection_fetch=true、
Hibernate statistics/StatementInspectorを使用します。
size 1/20/100/101でorder SELECTは3、JWTを含む全体SELECTはadmin4/self5で一定です。
rootのLIMITと関連のINを確認し、注文/明細Entity load・collection fetch・DMLは0。
全sortの方向/複合sort/ID tie-break、明細を含む注文単位の境界、本人content/count、
read-only REPEATABLE READ、別connectionのcommitを混ぜないsnapshot、実索引EXPLAINも検証します。

```powershell
mvnw.cmd test '-Dtest=OrderPageableNormalizerTest,OrderPagingServiceTest,OrderPagingControllerTest,OrderOwnershipServiceTest,OrderControllerTest,OrderAggregatePersistenceTest,JwtAuthenticationTest,BusinessEventLoggingTest'
mvnw.cmd test-compile failsafe:integration-test failsafe:verify '-Dit.test=OrderPagingIT,AuthorizationMatrixIT,AuthApiIT,ProductionLoggingIT,OrderCsvExportIT,MoneyApiIT,SalesApiIT'
mvnw.cmd verify
```

Java 17 / Spring Boot 3.5.12、既存Terraform mock 27件を維持します。
SQL・メモリ保持単位・request間の保証範囲は[ページング仕様](orders/pagination.md)を参照してください。

## 安全な注文CSV（Issue #63）

開始時baselineはSurefire 814 / Failsafe 624 / total 1438。既存テストの削除・skipはしません。
`OrderCsvWriterTest`はJackson CSV parser（test scope、Boot管理version）で6列をroundtripし、
日本語・空文字・カンマ・quote・LF/CRLFとformula/leading control対策を確認します。
`OrderCsvServiceTest`は50,000行を500件単位で生成し、全量保持しないsinkへ送り、query前のflush・
cursor・固定limit・切断時の後続query停止・Servlet streamをcloseしないことを検証します。
`OrderCsvControllerTest`はUTF-8/filename、commit前のProblemDetail、commit後の中断を確認します。

`OrderCsvExportIT`は実prod / JWT / MariaDB / Flyway V1〜V6で認可、parser roundtrip、保存値不変、
注文時価格、安定順、1103行のバッチ境界、3 SELECT・Entity load/update 0件を検証します。
read-only接続・REPEATABLE READ・同一threadのSecurity context、並行追加/状態変更のsnapshot分離、
切断/途中DB失敗時のconnection解放、実HTTP転送の異常終了、実prod logの秘密値非出力も検証します。
既存`MoneyApiIT`・`SalesApiIT`・注文/認可/冪等性の回帰は維持します。
詳しい契約・SQL・メモリ上限・長時間transactionの制限は[CSV仕様](order-csv.md)を参照してください。

```powershell
mvnw.cmd '-Dtest=OrderCsvWriterTest,OrderCsvServiceTest,OrderCsvControllerTest,OrderAggregatePersistenceTest,OrderControllerTest,OrderStateControllerTest' '-Dit.test=OrderCsvExportIT,MoneyApiIT,SalesApiIT' verify
mvnw.cmd verify
```

## 命名と実行phase

| 分類 | 命名 | plugin / phase | 対象 |
|---|---|---|---|
| Unit / lightweight | `Test*`, `*Test`, `*Tests`, `*TestCase` | Surefire / test | domain、Mockito、JWT、Controller slice、H2 JPA補助 |
| Integration | `*IT` | Failsafe / integration-test + verify | MariaDB、Flyway、実Security + JWT + DB、実lock/Transaction |

Surefire/Failsafeのversionは実効POMで確認する。2026-10-17のmainのSpring Boot parentは4.1.1（旧Issue作業時は3.5系）。
標準[Failsafeのライフサイクル](https://maven.apache.org/surefire/maven-failsafe-plugin/usage.html)を使用し、
ITの失敗はverify goalで非zero終了にする。独自runnerやfailure-ignoreは使わない。
CIはJava 17で `./mvnw verify` を実行する。現mainの `test` job timeoutは20分（[ci.yml](../.github/workflows/ci.yml)）。

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
| ProductPagingIT | Product一覧/検索の実MariaDBで同値sortの安定ページ取得、id DESC、keyword保持、認可境界、上限100、型付きOpenAPI |
| InventoryAdjustmentIdempotencyIT | 手動再送・20同時×3・mixed body/別商品/別actor、失敗key再利用、rollback、cancelロック順、非露出/OpenAPI |
| InventoryAdjustmentMigrationIT | V5→V6、旧履歴/checksum保持、ascii_bin/UNIQUE/pair/ORDER/hash CHECK、validate・2回再起動 |
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
V1〜V6 migrationとHibernate validate、3profileの実HTTP、health details非公開を検証する。
既存OpenAPIテストだけlocalを明示し、production要件をtest profileで無効化しない。
Deployment testsはreadiness target、labelに基づくreadiness/Actuator/legacy rollback、未知label拒否、DB設定不足、prod必須、
root/不正identifier拒否、secret非出力と既存SHA/image/lock/app-only安全性を検証する。

Issue #55のProbeHealthITは実prod・MariaDB12.2・main portの匿名`/livez`/`/readyz`と最小bodyを確認する。
専用DBをpauseし、15秒のbounded retryでreadiness503とliveness200を確認してfinallyでunpauseする。
REFUSING_TRAFFIC・BROKENの独立性、復旧、非公開Actuator、実imageの公式healthcheck commandも検証する。
ConfigurationContractTestはprodだけのprobe/group設定、DeploymentWorkflowTestはCompose health/restart/起動順を固定する。
prod総合healthはstatusと標準groups metadata、probeはstatus-only、default/localは従来bodyを検証する。
先行実行: `./mvnw -Dtest=ConfigurationContractTest,DeploymentWorkflowTest,DeploymentScriptTest,StructuredLoggingTest,JwtAuthenticationTest -Dit.test=ProbeHealthIT,ProductionProfilesIT,ProductionLoggingIT verify`。
PR前には絞り込みなしのJava17 `./mvnw verify`も実行する。Windowsは`mvnw.cmd`を使う。

Issue #57のBackupRestoreScriptTestはmock Dockerで実backup/restore scriptの失敗cleanup、checksum-first、
manifest/identifier、非空DB・exact confirmation・production app-running guard・import失敗・秘密値非出力を検証する。
BackupRestoreITは実MariaDB12.2の別container間でV1〜V6＋全主要dataをbackup/restoreし、history/checksum/row/metadataを比較する。
dedicated app userによるFlyway/Hibernate validate、pending0、改ざん/非空/誤確認時の不変、disposable replaceも検証する。
POSIXでは0700/0600を実検証し、WindowsではPOSIX assertionだけ分岐する（JUnit skipなし）。
先行実行: `./mvnw -Dtest=BackupRestoreScriptTest -Dit.test=BackupRestoreIT verify`。
本番DB・AWS/EC2を使用せず、secret/dump bodyをログへ出さない。[runbook](db/backup-restore.md)参照。

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

## Productページング契約（Issue #2）

Issue #2のProductPagingServiceTestは両queryのsort許可・id補完・明示id方向・query前の拒否・keyword保持・直接呼び出し上限を確認する。
ProductPagingControllerTestは実Serviceとmock Repositoryでdefault/explicit/max/複数sort/未知sort/標準数値fallback・固定7項目レスポンスを確認する。
ProductPagingITはFailsafeで実MariaDB + JWT/Security + Flywayを使用し、同価格の商品をsize=1で繰り返し取得して重複・欠落がないことを確認する。
上限100はProductのService normalizerだけで適用する。ProductPagingITでは在庫履歴のsize=500が500、size=5000が従来の上限2000となることも確認する。
既存mainのテストは削除・skipしない。

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
  Issue #52では固定PageResponseの7項目、PageImpl内部field非公開、履歴DTO/actorのfieldと機密field非公開を確認する。
  同時刻のid DESCとclient sort無視の既存テストを強化し、page 0/1のmetadata、空ページ200、
  size=500→500 / 5000→2000、型付きOpenAPIを確認する。Service/Repository/認可/lock/Transactionは変更しない。
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

## 手動調整の冪等性（Issue #43）

main基準1041件を削除しない。既存manual POSTへ一意keyを付け、最新migration期待値をV6へ更新する。
Orderのkey/fingerprint/scope/UNIQUE契約は変更せず、既存IdempotentOrderITを継続する。

- IdempotencyKeyTest: 共通validatorとOrder delegatorのASCII/空白/制御文字/長さ/case契約（15件）。
- InventoryAdjustmentFingerprintTest: 既知UTF-8/SHA-256、null/blank note、非blank保持、意味値4fieldの差（8件）。
- InventoryAdjustmentIdempotencyTest: MANUAL key/hash防御、legacy/ORDER NULL、immutable・DTO metadata非露出（10件）。
- InventoryServiceTest: 15件追加。入力/key検証前後の副作用、User→lookup→Product lock、歴史response再送、
  4field mismatch時Product未アクセス、対象1062/正確な制約だけ409、他障害の再throw・再照会なし。
- InventoryAdjustmentIdempotencyIT: 59件。実JWT・MariaDB12.2.2で10回再送、note/JSON順序、6種mismatch、
  key境界、USER403/未認証401優先、underflow/overflow/missing/Validation/INSERT失敗後のkey再利用。
  実UNIQUE衝突と対象外SQLのrollback、20同時same body×3、20同時mixed body×3、別商品競合×3、
  別actor/別keyの独立成功、Product削除409、履歴/ProblemDetail/OpenAPI非露出を確認する。
  cancelがactor行ロックで待っている間に同actorの手動Transactionを完了させるテストを3反復し、
  Order→User→Productの順がUser→Productと循環待ちを作らず、連続stock/historyを保つことを確認する。
- InventoryAdjustmentMigrationIT: 13件。V5 legacy MANUAL/ORDERからV6だけを適用し、旧全行とV1〜V5履歴/SQL hash保持、
  列・UNIQUE・pair/hex/ORDER CHECK・binary照合を実SQLで検証。2回再起動でvalidate・pending0・history不変。
- ProductionProfilesIT: 既存専用userの対象DB限定権限でV6も成功。追加GRANTなし、prod docs/H2/Actuator契約維持。

Java 17 (Temurin 17.0.18)でDOCKER_HOSTを接続不能なローカルportへ向けた
`mvnw.cmd clean test` は **583件成功**（Docker不要）、failures/errors/skipped=0。
実MariaDB12.2.2の `mvnw.cmd clean verify` はSurefire583件 + Failsafe578件 = **1161件成功**、
failures/errors/skipped=0。既存1041件 + 新規Unit48件/IT72件 = 120件追加で、既存テストの削除なし。
CIのJava 17 verifyにも同じテストを含める。
Testcontainers未起動時にskipする設定・timeout緩和・MariaDB downgradeは行わない。

## OSIV・DTO・Transaction境界（Issue #73）

`ConfigurationContractTest`は共通設定とprofile上書き後のOSIV=falseを3件で確認する。
`ProductionProfilesIT`のOSIV command-line overrideを外し、既存3 profileの実Web contextでも
設定値とOpenEntityManagerInViewInterceptor / Filterの不在を検証する。
`ServiceTransactionBoundaryTest`は14件でProduct参照のread-only、HTTP用Order DTO入口と既存
業務入口の属性一致、注文ページのREPEATABLE_READ、CSVのIOException rollbackを固定する。
Controller既存テストのstubbingをDTO入口へ同期し、self詳細/cancelのDTO契約を2件追加する。

`OsivApiIT`はdefault/local/prodの実Tomcat + MariaDB12.2.2でAuth、Product CRUD/検索、
admin/self注文の複数明細・snapshot・ページ・詳細・作成/replay・ship/complete/cancel/status、
Sales、Inventory調整/replay/conflict/履歴、CSVを検証する（3 profile、OSIV設定overrideなし）。
test-only StatementInspectorとResponseBodyAdvice/Servlet Filterが、実JSON書込み直前に
Transaction/EntityManagerが無いこと、converter開始後のSQLが0、request終了時のresource解放を検証する。
元のEntityに実LAZY customer/itemsがあることと、Serviceから返したDTOをcontext外でJSON化できることも確認する。
HTTP応答のfield集合を固定し、password/role/内部Product/Entity情報の混入を検出する。

測定対象のbusiness queryはProduct一覧/検索2本（content + count）、詳細1本、
注文ページ3本（ID + count + DTO rows）、注文詳細1本のroot query、
Sales1本、Inventory履歴2本、少量CSV1本。JWT/CurrentUserのuser queryや注文詳細の
関連ロードは別途Transaction内で行い、このroot query数へ混ぜない。
OrderPagingIT/ProductPagingIT/OrderCsvExportITの既存query-count・複数batch・bounded-memory回帰も全verifyで実行する。
StatementInspectorはSQL時のTransaction active/read-only/isolationも確認し、注文・在庫・商品metadata更新を
誤ってread-onlyにしたりREAD_COMMITTEDを上書きした場合は失敗する。
更新後versionとupdatedAtは実DBと照合する（DATETIME(6)のprecisionを考慮）。
Issue #74のLoginSecurityITとAuth/JWT/認可・prod loggingの既存回帰を削除/skipせず維持する。

## Terraform production host（Issue #59）

既存1392件は変更・削除・skipせず、TerraformContractTestを21件追加する。
12件のsource/CI/lockfile契約と9件のmock bootstrapで、world-open ingressなし、
明示ネットワーク、official AMI、IMDSv2、暗号化EBS、入力validation、secretなし・app/DB自動起動なしを確認する。
package/download/checksum/version/service/clone失敗は非0終了し、未検証binaryをinstallしない。
WindowsはGit Bashで同じscenarioを実行する。実hostのpackage installやAWS操作は行わない。

Terraform 1.15.6のfmt/init（backendなし・lockfile readonly）/validateに加え、
host.tftest.hclはAWS mock providerとplan-onlyで14件を実行する。
安全なhost・変更可能なnetwork/size、SSH /0（同値CIDRも含む）・不正CIDR・IPv6、
VPC外subnet・host bits・過大subnet・ARM type・不正region・公開鍵path不備を検証する。
AWS credentials・実data source・real plan・applyは使用しない。
Terraform手順と検証境界は [Terraform README](../terraform/README.md) を参照する。

## HTTPS / Caddy（Issue #61）

既存1413件を削除・skipせず、ConfigurationContractTestにprod-only forwarded propertyを1件、
CaddyContractTestに15件（source/service/Compose/deploy境界4件、DNS/enable gate 11件）、
TerraformContractTestにCaddy失敗scenario 5件、CaddyProxyITに実binary/HTTP/TLS/log audit 4件を追加する。
ProductionProfilesITの既存prod case内でもforwarded HTTPS・health body・request IDを検証する。
Surefire 814 / Failsafe 624 / total 1438を期待する。Failsafeはpublic CAへ到達できないnetwork=none containerと
local CA、certificate/hostname検証を使う。fixture responseをproxyし401/409/HSTS/request ID/headersと
secret canaryが実Caddy logへ出ないことを確認する。業務処理の契約は既存MariaDB ITでも回帰確認する。
Terraform mock planは14件を維持してdomain拒否13件を追加し27件。
EIP/association/output、TCP80/443・SSH、8080/2019非公開、render済みuserdataの16KiB上限も検証する。
詳しくは [HTTPS手順](tls.md) を参照する。

## 商品名の長さ・Unicode境界（AUD-05 / Issue #106）

ProductControllerTestに28件、ProductNameValidationITに18件を追加する。
既存1954件を保持し、Surefire1197 / Failsafe803 / total2000（F/E/S=0）を確認する。

- ASCII、日本語、絵文字のみ、ASCII＋絵文字、結合文字列の各255/256コードポイントをPOST/PUTで検証する。
- 255絵文字はUTF-16で510単位だが受け付ける。結合文字は正規化せず保持し、見た目の文字数では数えない。
- 未対high/low surrogate、逆順のsurrogate、high surrogate＋ASCIIは400 VALIDATION_ERROR / errors.name。
- Controllerでは不正時のService未呼出しを確認。ITは実Security/JWT・MariaDB12.2.2・Flyway V1〜V6を使い、255コードポイントの保存値とDBのCHAR_LENGTH=255を照合する。
- 各400の直後にproducts/users/orders/order_items/inventory_movements全行のsnapshotを比較し、name/price/stock/versionを含む不変を確認する。
- null/blankの既存拒否、PUTのversion増分とstock不変、実OpenAPI両schemaのminLength1/maxLength255と400 ApiProblem参照も確認する。
- ITのHTTPダンプは無効にし、失敗時もAuthorizationのJWTをテストログへ出さない。

変更前のDTOで追加Controllerテスト18件が失敗し、実MariaDBでも256コードポイント5ケースのPOST/PUT（計10応答）が500になることを確認してから修正する。
修正後は対象テストに加えて、Java17でfull verifyを実行する。対象テストを選ぶこととfull verifyの証跡を区別し、skipによる成功扱いはしない。

```powershell
.\mvnw.cmd '-Dtest=ProductControllerTest' '-Dit.test=ProductNameValidationIT' verify
.\mvnw.cmd verify
```

## 異なる20顧客の在庫枯渇競合（AUD-06 / Issue #108）

InventoryConcurrencyMariaDbITの `twentyDistinctCustomersCompeteForTenUnitsWithoutOverselling` を3回反復する。
既存の同一customer 100 workerと、IdempotentOrderITの2顧客同時注文は保持する。
新規3件を加え、Surefire1197 / Failsafe806 / total2003（F/E/S=0）を確認する。

- 実DB ROLE_USERを20人、商品1個の在庫10・単価100・数量1、顧客ごとの固有keyを用意する。
- 既存ヘルパーのREQUIRES_NEW / READ_COMMITTED・接続保持・同時開始latchを再利用し、各workerを自分の顧客として認証する。
- 20の異なるCONNECTION_ID、Spring transaction active、MariaDBのin_transaction=1を確認。JDBCで全員の初期在庫/versionを読んでから第2latchを開く。JPA管理状態への先読みはせず、商品取得は実Serviceのロック経路に任せる。
- 成功10、OutOfStockException10のみを許可。workerの原因class/SQLState/codeを診断し、例外message・認証情報・JWT・keyをログへ出さない。sleep・pool/timeoutの緩和は使わない。
- 在庫0、商品version10、注文10、明細10、販売数量10、合計金額1000、成功顧客ID10種類、商品名/単価snapshot・status/version・日時を照合する。
- ORDER_CREATED履歴10件のstock_before=10〜1 / stock_after=9〜0、source order・actor customerの対応を確認。
- 成功顧客の同key retryで同一注文を返し、products/orders/order_items/inventory_movements/users全行が変化しないことを確認する。

直接Serviceを実DBで競合させるテストであり、新たなHTTP/JWT経路の試験ではない。既存HTTPの冪等性・認可回帰はfull verifyで維持する。

```powershell
.\mvnw.cmd test-compile failsafe:integration-test failsafe:verify '-Dit.test=InventoryConcurrencyMariaDbIT#twentyDistinctCustomersCompeteForTenUnitsWithoutOverselling'
.\mvnw.cmd verify
```

ロック除去検証はGit archiveから作る破棄可能な隔離コピーだけで行う。
ProductRepository.findByIdForUpdateの@Lockと、OrderService.createOrderの商品refreshをそのコピー内でのみ除去し、同じ新規テストのFAILを確認する。
本番ソース・共有branchにmutationを残さず、コピーのsourceも原状へ戻す。通常版の反復結果とmutationの失敗原因をPRへ記録する。

## 公開登録の負荷制限（AUD-07 / Issue #110）

RegistrationRateLimiterTest / RegistrationClientAddressFilterTest / RegistrationSecurityITを追加する。
実MariaDB・prod・実embedded HTTP・実Securityで、既定5回/分・同時2件を変更せず検証する。
clockとlatchで窓・並行処理を制御し、長時間sleepを使わない。

- 正常登録のROLE_USER/BCrypt/DTO、JWT、401/403、重複409と元hash保持。
- 窓の直前/直後・Retry-After切上げ、source分離、IP正規化、state上限と退避/期限切れ、時計後退。
- 20並列中2受付/18拒否。拒否側のDB/BCrypt未実行、待ち行列なし、完了直後の再受付。
- lookup/encode/saveの各失敗も枠を消費し、permit解放・安全な500・元DB状態を維持する。
- trusted peerの単一XFF、未信頼偽装・chain・重複・欠落・Forwarded競合・source不明503、ログ/拒否本文の非露出。
- 登録とloginの回数・permitの独立性。LoginSecurityITを含む既存回帰をfull verifyで実行する。
- 既存10顧客のUNIQUE競合テストは、送信側Semaphore(2)と別clientで新しい受付契約を守る。
  実装の上限、DB pool、timeoutは変更しない。HTTP超過は新規20並列テストで直接検証する。
- Docker検証scriptも登録5回/429・別source・login独立・proxy不正503と未挿入を確認する。

```powershell
.\mvnw.cmd '-Dtest=RegistrationRateLimiterTest,RegistrationClientAddressFilterTest,AuthControllerTest,ProductionConfigurationTest' test
.\mvnw.cmd test-compile failsafe:integration-test failsafe:verify '-Dit.test=RegistrationSecurityIT,LoginSecurityIT,AuthApiIT'
.\mvnw.cmd verify
```

実本番peer・edge設定・分散DoS耐性の検証とは区別し、GitHub CIのDocker evidenceと全5ジョブをPRへ記録する。

## 注文作成の明細数上限（AUD-08 / Issue #112）

OrderControllerTest / OrderServiceTest / OrderRequestFingerprintTestを補強し、OrderItemLimitITを追加する。
既存テストは保持し、実MariaDB12.2.2とJWT/認可を有効にしたHTTP、実TransactionのService両入口を検証する。

- 0/100/101明細。入力配列が101なら重複商品であっても400 VALIDATION_ERROR / errors.itemsとなりServiceへ進まない。
- 直接Serviceは101/Integer.MAX_VALUEを表すListをsizeだけで拒否し、get/iterator・指紋生成・CurrentUser・業務Repository/EntityManagerへ進まない。
- 100商品の逆順入力でも商品ID昇順のPESSIMISTIC_WRITE取得/refreshを全件完了してから在庫更新する。
- 100明細の保存、金額/商品snapshot、stock/version、100在庫移動を照合。明細順を逆転した同key retryで5表の全行が不変。
- 101の商品はすべて実在させ、商品不存在による見せかけの400を避ける。HTTP/直接Serviceの超過、重複、最終明細の不正数量でproducts/orders/order_items/inventory_movements/users全行が不変。
- 拒否時にkeyを予約せず、既存keyへ過大な本文を送っても元注文は不変。その後の有効なretryが成功する。
- OpenAPIのOrderRequest.items minItems=1 / maxItems=100と400 ApiProblem参照を確認。保存済みOrderResponse配列へ上限を付けない。
- Dockerの実JRE/non-rootイメージでも101明細のValidation拒否と注文/在庫/履歴の不変、既存key retryを検証する。

```powershell
.\mvnw.cmd '-Dtest=OrderControllerTest,OrderServiceTest,OrderRequestFingerprintTest,OrderIdempotencyServiceTest' test
.\mvnw.cmd test-compile failsafe:integration-test failsafe:verify '-Dit.test=OrderItemLimitIT,IdempotentOrderIT'
.\mvnw.cmd verify
```

HTTPダンプを無効化し、DB snapshotはboolean比較でcredentialやkeyを失敗ログに載せない。
JSON本文サイズ/受信速度/頻度の上限や本番負荷試験とは区別して結果を記録する。

## DB障害と認証エラーの分類（AUD-10 / Issue #114）

- JwtDatabaseFailureTest / ServerFailureResponseTestは同じ14種の障害をフィルタとMVCへ注入する。
  接続不能/切断、接続取得/クエリtimeoutは503、DB認証設定・SQL/schema不備・未知の例外は500。
  有効JWTからの500/503が401に変わらず、業務Serviceへ進まないこと、6フィールド・固定detail・安全な型診断/requestIdを確認する。
- ユーザー削除は401、parser内部の予期しない例外は500。既存の署名/issuer/期限、未認証・認可・login/register制限のテストも保持する。
- AuthDatabaseFailureITは新規Testcontainers MariaDBだけをpauseし、実HTTPの注文一覧とログインを呼ぶ。
  readiness503/liveness200、匿名/不正JWT401、permit解放を確認し、finallyでunpauseする。
  再開後に同じJWTとログインが200へ戻り、ユーザーとpassword hashが不変で、応答/production consoleに秘密値が出ないことを確認する。
  試験専用の取得1000ms/validation500ms/connect/socket1000msは有界な障害再現用。本番設定は緩めない。

```powershell
.\mvnw.cmd '-Dtest=JwtDatabaseFailureTest,ServerFailureResponseTest,JwtAuthenticationTest,SafeExceptionLoggingTest,ProblemDetailContractTest' test
.\mvnw.cmd test-compile failsafe:integration-test failsafe:verify '-Dit.test=AuthDatabaseFailureIT,LoginSecurityIT,RegistrationSecurityIT,AuthorizationMatrixIT'
.\mvnw.cmd verify
```

故障試験の503は想定結果であり、JUnitのfailure/error/skipを許容するものではない。
本番のネットワーク・DB failover・接続プール枯渇への性能保証は未検証と区別する。

## DB待機予算とgraceful shutdown（AUD-11 / Issue #116）

DatabaseTimeoutITはprodの設定値を上書きせず、pool10の枯渇、独立Txのproduct row lock、
更新後の長いSQLとTx/query deadline、接続KILL後のrollback失敗、DB pause中の借用済みsocket待ちと新規handshakeを分けて検証する。
診断用SQL/HTTPはtest sources限定。既存100workerのpool110/取得30秒等は変更しない。
409/503と未分類rollback失敗500、全Tx rollback、接続/ロック解放、同JWT復旧、同key retryの二重処理なしを確認する。

DockerRuntimeContractTestではvalidation/取得/driver接続/Tx/socket/HTTP drain/Composeの予算関係を検査。
scripts/docker/verify_image.pyは新規tmpfs DBと実JRE imageで、進行中注文を保持してSIGTERMを送り、
新規受付停止→既存注文200 commit、またはrow wait409 rollback、pool shutdown/DB接続0、
正常exit・SIGKILL/OOMなし、再起動後の同key replayとDB不変を検証する。
既存のDocker hardening/API/ADMIN/rollback/Trivyも実行する。停止猶予を外す変更前probeではexit137とHTTP切断を観測した。

```powershell
.\mvnw.cmd '-Dtest=DockerRuntimeContractTest' test
.\mvnw.cmd test-compile failsafe:integration-test failsafe:verify '-Dit.test=DatabaseTimeoutIT,AuthDatabaseFailureIT'
.\mvnw.cmd verify
python scripts/docker/verify_image.py --scan
```

各deadlineはHTTP全体のSLAではない。計測条件・既定値・運用判断・非保証は[DB lifecycle](db-lifecycle.md)を参照。

### Windowsの行ロック待機測定（Issue #121）

DatabaseTimeoutITの行ロックケースは3反復し、専用MariaDBのnative待機時間/回数/現在待機数を前後で読む。
DB待機の判定は4,500ms以上・既存上限6,500ms未満で行い、ホストのHTTP観測時間では判定しない。
従来のFuture.get 8秒、blocker解放前の409、全Tx rollback・DB不変・同key retry/replay・pool残留0は維持する。
先に取得した商品ロックも、blockerを保持した別TxからNOWAITで取得できることを確認する。
既存ケースを削除せず、この3反復で実行件数を2件追加する。

```bash
./mvnw test-compile '-Dit.test=DatabaseTimeoutIT' failsafe:integration-test failsafe:verify
./mvnw verify
```

Windowsは`mvnw.cmd`とJava17で同じgoalを実行する。対象ITの成功をfull verify成功とは扱わない。
診断段階では旧判定を残したfull verifyも実行し、3反復のDB待機5,138/5,038/5,061msと
ホスト観測4,652/4,560/4,681msを確認した。この回は成功したが、過去のWindows full verifyでの
6,819/6,881msによる失敗を成功へ読み替えない。現行設定は5秒のまま。
隔離コピーだけに不正な7秒fixtureを用意して比較し、是正後は3回ともDB待機上限違反として検出した。
時計領域、環境条件、非保証は[測定境界](db-lifecycle.md#行ロック待機の計測境界issue-121)を参照。

## JWTフィルタ経路とauth境界（Issue #81）

JwtFilterRegistrationITはMockMvcだけでなく実Tomcat・ServletContext・SecurityFilterChainを用い、
使い捨てMariaDBと実JWTで登録数、入口/内部/解析/照会回数と順序を別々に測る。
変更前は通常の認証成功要求で入口2回/内部1回/解析1回、変更後はServlet登録0・Security内1・入口1回。
テスト専用のprefix類似ルートと/auth配下ルートで、過剰な除外/匿名許可を検出する。
これらはproduction APIへのルート追加ではない。診断時もJWT/password/hash/DB接続値は出力しない。

実HTTPの公開POST、署名/issuer/期限の不正、401/403/429とRetry-After、503/500の安全な故障注入、
SecurityContextのrequest間分離、server生成requestIdと安全eventを検証する。
JwtPublicEndpointMatcherTestはmethod・context path・canonical URI・類似/末尾/encoded pathの境界を検証。
両テストは既存JWT/障害分類/同時注文/DB待機テストを削除・skipせず追加する。

対象実行: mvnw testの-Dtest=JwtPublicEndpointMatcherTest,JwtAuthenticationTest,JwtDatabaseFailureTest、
およびmvnw test-compile failsafe:integration-test failsafe:verifyの-Dit.test=JwtFilterRegistrationIT。
最終検証はJava17 full verify、実Docker/Trivy、Secret正常/異常、SpotBugs正常/異常、OSV、
PythonとTerraform mockおよび当HEADのGitHub CI全5ジョブで行う。詳細は[認証仕様](auth.md)を参照。

## JWT B1の回帰（Issue #136）

v1.0の数値・監査snapshotは上記と過去節へ保存する。B1の実測件数・CI URLは本PR本文と新HEADのCIを参照する。
旧形式JWTを引き続き受け付けるという互換性はなく、[認証契約](auth.md#jwtライフサイクルと失効制約)と
[停止移行手順](security/jwt-revocation-migration.md)が現在の仕様である。

| 証拠 | 保証範囲 |
|---|---|
| [JwtRevocationClaimsTest](../src/test/java/com/example/demo/security/JwtRevocationClaimsTest.java) | 固定Clock、必須iat/expと期限境界、UUID主体、文字列世代、旧/欠落/不正型/負数/先頭0/overflow拒否 |
| [LoginTokenIssuerTest](../src/test/java/com/example/demo/service/LoginTokenIssuerTest.java) | ロック＋fresh read後のuid/保存username/hash/世代再確認。古い証明で新世代を発行しない |
| [AuthRevocationIT](../src/test/java/com/example/demo/security/AuthRevocationIT.java) | prod実HTTP＋MariaDB、世代/audit原子性、同version並行1成功、commit/rollback、古い業務snapshot、別ユーザー分離、同名再作成、偽装source、429/503/500、ログ非露出、logout/login/注文競合と冪等retry |
| [AuthIdentityMigrationIT](../src/test/java/com/example/demo/repository/AuthIdentityMigrationIT.java) | V6データ/FK/hash/role保全、UUID一度限り、DDL中断再開、不適切な部分schema/失敗履歴の停止、負世代/形式/旧CLI拒否 |
| [InitialAdminLifecycleIT](../src/test/java/com/example/demo/provisioning/InitialAdminLifecycleIT.java) / [ProvisioningIT](../src/test/java/com/example/demo/provisioning/InitialAdminProvisioningIT.java) | INSERT専用最小権限、V7履歴確認、並列作成、再起動後のuid/世代/監査保持と旧JWT拒否（同じ署名鍵） |
| [BackupRestoreIT](../src/test/java/com/example/demo/repository/BackupRestoreIT.java) | uid/非ゼロ世代/監査を含む全tableの実backup/restore。復元による失効巻戻りの安全性は保証しない |
| [Docker検証](../scripts/docker/verify_image.py) | clean archive build、従来9段階デモ＋B1別検証、logout→旧JWT401→再ログイン、同一image設定故障復帰後も世代/監査保持。異なる旧binaryへのrollbackは未検証 |

既存JWT6否定系・DB障害分類・ロック/注文/在庫・ADMIN CLIのテストは削除/skipしない。
fixtureのraw INSERTとprincipalを新uidへ更新し、製品のpool10/取得5秒/ロック5秒/Tx10秒を緩和しない。
Windows旧DatabaseTimeoutIT初回3失敗の根因未確定と、baseline main CIの失敗→再実行の履歴は、今回の成功で消さない。
TrivyのOS検出、Flyway互換性WARN、実環境の未確認も別に報告する。

## JWT否定系の受入条件（Issue #123 第一PR / AUD-23）

監査のAUD-23はSHOULD、v1.0 DoDは明示テストを要求していた。
本項では未署名・改ざんを含む次の拒否条件をv1.0の回帰条件として明文化する。
Issue #123のREADME/文書同期・通しデモは別PRであり、この検証だけで同Issue全体の完了とはしない。

| 入力 | 作り方・受入条件 |
|---|---|
| 未署名JWT | `alg=none`、有効なissuer/expと既存ADMINのsub、空署名。署名付きJWT専用parserが拒否 |
| payload改ざん | 正常USER JWTのheader/署名を維持し、payloadのsubを既存ADMINへ変更。再署名しない。署名検証で拒否 |
| 署名だけが異なるJWT | 正常header/payloadのまま、MACの有効bitを変更して再エンコード。署名検証で拒否 |
| 別鍵 | 実行時に生成した別の鍵で署名した有効形式のJWTを拒否 |
| issuer不一致 | 正しい鍵・未来の期限でも別issuerを拒否 |
| 期限切れ | 正しい鍵・issuerでも過去のexpを拒否。sleepや時刻依存の短い待機を使わない |

[JwtUtilTest](../src/test/java/com/example/demo/security/JwtUtilTest.java)は、既存の別鍵/issuer/期限等の検証を保持し、
未署名のUnsupportedJwtExceptionと、payload/MAC改ざんのSignatureExceptionを明示確認する。
[JwtNegativeTokens](../src/test/java/com/example/demo/security/JwtNegativeTokens.java)はtest専用の実行時生成器で、
source/公開fixtureに再利用可能なsecretやcompact JWTを追加しない。

[JwtFilterRegistrationIT](../src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java)は、
実Servlet/SecurityFilterChainと使い捨てMariaDBで6入力を検証する。
有効な注文POSTとADMIN商品作成POSTを各401 UNAUTHENTICATED・既存ProblemDetailで拒否し、
DBユーザー認証照会0、業務Service呼出0、users/products/orders/order_items/inventory_movements全行不変を要求する。
続く正常USER要求200、USERのADMIN要求403、正常ADMIN要求200、requestIdと安全なログも確認する。
JWT・鍵・password/hash・DB認証情報をresponse/application event/consoleへ出さない。

これはJWT失効、refresh、アルゴリズムallowlistの新設ではない。
`signWith(key)`の鍵長に応じた正規のHMAC方式を維持し、HS512という名前だけで拒否する契約は追加しない。
署名/issuer/期限の検証、DB role認可、公開auth除外、503/500/429等は既存契約を維持する。

## JWT B2の回帰（Issue #138）

基準mainは `3738628deb4bc58c659d82ee871523dbc0f4908d`（B1のJava 2,274件）。過去のsnapshot・計測値は変更しない。
B2の最終件数とCI URLはPR本文およびそのHEADに対応する実runを参照する。

| 証拠 | 常設する検証 |
|---|---|
| [AuthPasswordIT](../src/test/java/com/example/demo/security/AuthPasswordIT.java) | prod実HTTP・MariaDB。本人変更204、hash/世代/監査の原子性、全端末401、新password再login、別ユーザー/ADMIN分離、入力400・current不一致401・429/503/500、秘密非露出 |
| 同ITの競合 | logout-all/二重変更をlatchで同時開始。ロック前に異なるDB connection ID/Txを観測し、1 commit・1 stale401。BCryptはTx外、変更前login照合からの発行を拒否 |
| 同ITの境界/故障 | 未commit前の旧JWT認証とcommit後の拒否、rollback・監査INSERT故障時のhash/世代/audit不変、実5秒Userロックtimeout503、MAX世代500、同名再作成の旧uid拒否 |
| [PasswordMutationBoundaryTest](../src/test/java/com/example/demo/security/PasswordMutationBoundaryTest.java) | 操作別source窓、共有同時permit/状態上限、正確な保護POSTのsource捕捉、偽装XFF拒否。B1の21件limiter/9件sourceテストも維持 |
| [PasswordAuditMigrationIT](../src/test/java/com/example/demo/repository/PasswordAuditMigrationIT.java) | V7既存hash/非zero世代/注文/FK/audit・V1〜V7履歴を保持してV8適用、両actionだけ許可、repeat0件。新規DB/backup/旧Flyway補助の既存ITもV8まで検証 |
| [Docker検証](../scripts/docker/verify_image.py) | 従来9段階+B1を維持しB2を別検証。hash/世代2/監査計2行、旧password/JWT401、新login200、同一image設定故障復帰後も状態保持。旧binary rollbackの証明にはしない |

初回対象検証では、trim後6文字を401と期待したfixtureと、Java HttpClientが未対surrogateを送信時に置換したfixtureの2件が失敗した。
既存LoginRequestの8文字条件に合わせたfixtureと、明示JSON escapeによる不正Unicode入力へ是正した。実装の検査を弱めず再検証する。
B2初回full verifyでは、未変更のCaddyProxyITの2件で証明書/hostnameを検証するローカルOpenSSL clientがexit1となった。
clientの診断fileは当該テストのcleanupで破棄されており、初回原因を証明書時刻やEOF等に断定できない。
変更していないcompiled testを3つの新規使い捨てcontainerで別途実行した診断（4メソッド×3回）は成功し、検証無効化はしていない。
この再現不成立だけで初回失敗を解決済みとは扱わず、後続のfull verify/CI結果を区別して記録する。
Windowsの過去DatabaseTimeoutIT初回3失敗と証明書時刻の観測は根因未確定のまま保持する。今回の成功で過去の観測を消さない。
pool/timeout/lock予算、既存テスト、JWT否定系6種、業務認可・冪等性は緩和しない。

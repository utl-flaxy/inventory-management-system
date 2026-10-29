# バックエンド実装を説明する3つのデモ

[README](../README.md)から進む、Java / Spring Bootバックエンド職向けの実装・テスト案内。
対象はmain `1a647a393dac147825293e0ccba0fd84f30bc5fa`。実測の出典とP1〜P5は[2026-10-29照合の受入候補](release-status.md)を参照する。
[10月23日の受入追補](audit/portfolio-application-acceptance-2026-10-23.md)は当時の履歴として保持する。
新機能・公開サービス・本番負荷試験の紹介ではない。認証情報やJWTをコピーせず、既存の使い捨てharnessと実DBテストだけを使う。

## 1. ユーザー登録から注文・取消まで

Git、Docker daemon/Compose v2（linux/amd64）、Python3.12以上、依存を取得できるnetworkが必要。
repository rootでレビュー対象commitをcheckoutして実行する。imageは`git archive HEAD`から構築され、未コミットの実装は入らない。
所要時間は初回取得・host容量で変わり、90秒/5分での完了は保証しない。必要メモリとcleanupの制約は[既存デモ手順](demo.md#前提と実行)を先に確認する。

```bash
python3 scripts/docker/verify_image.py --scan --output target/portfolio-demo
```

Windowsでは`python3`をPython3.12以上の`python`へ読み替える。固定名の通常Composeを直接起動しない。
harnessが新規tmpfs DB、固有container/network、実行時生成のテスト用資格情報を用意し、自分の資源だけをcleanupする。
既存DB・volume・実secretは入力しない。公開の業務データは単価100・初期在庫10のテスト商品だけ。

|画面で説明すること|実際のharness操作と観測|
|---|---|
|USERとADMINの分離|登録200でROLE_USERを2人作り、専用offline ADMIN CLIを3並列実行してADMINは1人。USER/ADMINのlogin200、匿名/不正JWT401、USERのADMIN操作403|
|通常業務|ADMINの商品作成200 → USERが数量2を注文200 → 同key retryで同一注文 → ADMINの在庫+3 → USERが取消200 → ADMIN一覧でCANCELLEDを確認|
|二重減算・復元を防ぐ|在庫は**10→8→11→13**、注文金額200。再送で注文数/減算が増えず、取消で一度だけ戻る|

実装入口は[SelfOrderController](../src/main/java/com/example/demo/controller/SelfOrderController.java)から
[OrderService.createOrderResponse / cancelMyOrderResponse](../src/main/java/com/example/demo/service/OrderService.java)、
対応するAPI/DB検証は[verify_image.pyのapi_regression](../scripts/docker/verify_image.py)。[9段階の詳細](demo.md#実際に行う操作と成功条件)と対応する。

## 2. 在庫競合と注文の冪等性

Java17を`JAVA_HOME`に指定し、Docker daemonを利用できる状態で実行する。Windowsは`./mvnw`を`.\mvnw.cmd`へ読み替える。

```bash
./mvnw test-compile '-Dit.test=InventoryConcurrencyMariaDbIT,IdempotentOrderIT' failsafe:integration-test failsafe:verify
```

これは対象ITの実行で、全回帰の代用ではない。通常の`./mvnw verify`ではSurefire/Failsafeを両方実行する。

|説明する判断|実装を読む順番|直接対応するMariaDBテスト|
|---|---|---|
|異なる顧客の競合は、同一顧客の直列化だけでは防げない|[OrderService.createOrder](../src/main/java/com/example/demo/service/OrderService.java): READ_COMMITTED、User行→Product ID昇順のPESSIMISTIC_WRITE、refresh後に在庫を判断。[ProductRepository.findByIdForUpdate](../src/main/java/com/example/demo/repository/ProductRepository.java)|[InventoryConcurrencyMariaDbIT.twentyDistinctCustomersCompeteForTenUnitsWithoutOverselling](../src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java#L242): 実DBユーザー20人・別key・別接続/Txをlatchで同時開始。各fixtureで成功10/OutOfStockException10、最終在庫0、注文/明細/販売数量/履歴10、商品version10、成功顧客10人、金額/snapshot/再送不変。3反復|
|再送の意味を比較し、業務更新と同じTxへ保存する|[OrderRequestFingerprint.fromValidatedItems](../src/main/java/com/example/demo/service/OrderRequestFingerprint.java): 商品ID/数量を正規順序でSHA-256化。[OrderService.createOrder](../src/main/java/com/example/demo/service/OrderService.java): customer/key照会と指紋照合。[Order](../src/main/java/com/example/demo/entity/Order.java) / [V4 UNIQUE制約](../src/main/resources/db/migration/V4__add_order_idempotency.sql)|[IdempotentOrderIT.twentyConcurrentSameRequestsAllReturnOneOrderAndReduceStockOnce](../src/test/java/com/example/demo/controller/IdempotentOrderIT.java): 同一顧客のHTTP20並列が全200・同一注文、在庫減算1回。`tenOfEachBodyConcurrentlyYieldTenReplaysAndTenConflictsForEitherWinner`は同key異内容で200が10/409が10|
|失敗時に中途半端な注文やkeyを残さない|[OrderService.createOrder / validatedQuantities](../src/main/java/com/example/demo/service/OrderService.java): 全入力・金額検査後、注文/在庫/履歴を同一Txで保存。限定UNIQUE違反だけ409、rollback-only中にreplayしない|[IdempotentOrderIT.failureDoesNotConsumeKeyAndRetryAfterRestockCreatesExactlyOneOrder / specificUniqueViolationIs409AndRollsBackAttemptedStockChangesWithoutReplaying](../src/test/java/com/example/demo/controller/IdempotentOrderIT.java)。不足・競合・途中失敗のDB不変と有効retryを区別|

20顧客ケースは**実Service/DBの競合試験**で、20人が同時にHTTP loginした証拠ではない。HTTPの並行再送は別のIdempotentOrderITが担う。
Serviceの不足例外は[GlobalExceptionHandler](../src/main/java/com/example/demo/exception/GlobalExceptionHandler.java)で**400 OUT_OF_STOCK**、同key異内容は**409 IDEMPOTENCY_KEY_CONFLICT**。未指定/不正keyは400。
本文の明細順/JSON空白は意味比較へ影響せず、重複商品ID・不正数量・101明細は400。keyは顧客単位・大文字小文字を区別する1〜128文字で、自動TTL/削除はない。
replayは現在の注文状態を返すため、最初のHTTP bytes全体を固定cacheしているわけではない。

悲観ロックにはhot商品や同一顧客での待機コストがあり、@Versionは古い商品更新を検出する別の防御。
有限の反復成功は全スケジュールでのdeadlock不在やSLAの保証ではない。
[設計と代替案](design/decisions.md)、[Transaction境界](design/transactions-and-locking.md)、[過去の隔離lock除去対照](testing.md#異なる20顧客の在庫枯渇競合aud-06--issue-108)を参照する。今回はロックを除去しない。

## 3. JWT失効と旧トークン拒否

```bash
./mvnw test-compile '-Dit.test=AuthRevocationIT,AuthPasswordIT,AdminTokenRevocationIT' failsafe:integration-test failsafe:verify
```

各ITは使い捨てMariaDB・実HTTPで検証する。通常の全回帰は`./mvnw verify`。
同じDockerコマンドのsummaryでも、9段階の後の`jwt_revocation` / `password_change` / `admin_force_revocation`を確認できる。

|契約・設計理由|実装|直接対応するテスト|
|---|---|---|
|署名/issuer/iat/expだけでなく、immutable UUIDと世代をDBへ照会する。HTTP sessionは持たないが認証はDBに依存する|[JwtUtil.generateToken / verify](../src/main/java/com/example/demo/security/JwtUtil.java)、[JwtUserAuthenticator.authenticate](../src/main/java/com/example/demo/security/JwtUserAuthenticator.java)のREQUIRES_NEW / READ_COMMITTED、[CurrentUser.requireUser](../src/main/java/com/example/demo/security/CurrentUser.java)|[AuthRevocationIT.authenticationUsesCommittedStateAcrossARevocationCommitEvenInsideAnOldBusinessSnapshot](../src/test/java/com/example/demo/security/AuthRevocationIT.java): 古い業務snapshotでも失効commit後の認証照会が旧JWTを拒否。`usernameReassignmentCannotMakeAnOldUuidTokenReferToANewAccount`で同名別主体への誤認を防ぐ|
|POST /auth/logout-all: 本人の全端末を失効。世代増分と監査が同時commit後204|[AuthRevocationController.logoutAll](../src/main/java/com/example/demo/controller/AuthRevocationController.java)、[AuthRevocationTransactions.logoutAll](../src/main/java/com/example/demo/service/AuthRevocationTransactions.java#L31)|[AuthRevocationIT.logoutAllRevokesAllDevicesOnlyForTheAuthenticatedUserAndPersistsAfterNewReads / auditInsertionFailureRollsBackTheIncrementAndKeepsTheSameJwtUsable](../src/test/java/com/example/demo/security/AuthRevocationIT.java): 旧JWT/再送401、別ユーザー分離、監査失敗rollback|
|POST /auth/password: 現passwordを検証しhash/世代/監査を原子的に変更、204後は全旧JWT401、新passwordでlogin200|[PasswordChangeService.change](../src/main/java/com/example/demo/service/PasswordChangeService.java): BCryptはTx外。[PasswordChangeTransactions.change](../src/main/java/com/example/demo/service/PasswordChangeTransactions.java#L35): 行ロック後にhash/世代/roleを再照合|[AuthPasswordIT.commitsHashVersionAuditAndRevokesAllDevicesWithoutChangingAnotherUser / simultaneousLogoutAndPasswordChangeHaveOneCommitAndOneStaleRejection](../src/test/java/com/example/demo/security/AuthPasswordIT.java)|
|POST /api/v1/admin/users/{uid}/token-revocations: 現ADMIN権限と本人passwordを再認証し、対象の全端末を失効。actor/targetのロック順を固定|[AdminTokenRevocationService.revoke](../src/main/java/com/example/demo/service/AdminTokenRevocationService.java)、[AdminTokenRevocationTransactions.revoke](../src/main/java/com/example/demo/service/AdminTokenRevocationTransactions.java#L40): 内部ID昇順、最新actor再検証、世代と監査を一括commit|[AdminTokenRevocationIT.adminReauthenticatesOnceOutsideTransactionAndRevokesEveryTargetDevice / crossAdminRequestsUseAscendingLocksAndOnlyTheStillValidActorCommits](../src/test/java/com/example/demo/security/AdminTokenRevocationIT.java)|

**401**は未認証/不正・失効JWTや本人password不一致、**403**は有効な認証でも権限不足。
他人の注文は存在を明かさず**404 ORDER_NOT_FOUND**。ADMIN強制失効の対象不在は、ADMIN本人の再認証成功後だけ**404 USER_NOT_FOUND**になる。
根拠: [AuthorizationMatrixIT](../src/test/java/com/example/demo/security/AuthorizationMatrixIT.java)、[AdminTokenRevocationIT.absentTargetIs404OnlyAfterValidAdminProof](../src/test/java/com/example/demo/security/AdminTokenRevocationIT.java)、[SecurityConfig](../src/main/java/com/example/demo/security/SecurityConfig.java)。
DB障害は認証不一致へ潰さず503/500、制限超過429は[認証契約](auth.md)に従う。

失効commit後の新たな認証照会で拒否する設計であり、認証済みの進行中業務を強制rollbackする機能ではない。
[AuthRevocationIT.orderAuthenticatedBeforeLogoutCommitMayFinishAndReloginRetryDoesNotDuplicateIt](../src/test/java/com/example/demo/security/AuthRevocationIT.java)でその境界も確認する。
個別jti失効は[Issue #134](https://github.com/utl-flaxy/inventory-management-system/issues/134)で保留。refresh、password reset、role変更APIも未実装。
旧形式JWTは401、鍵/DB世代の巻戻りには再受理リスクがあり、[停止移行・rollback制約](security/jwt-revocation-migration.md)は別途必要。

## 結果を共有する前に

process exit0とsummaryの`result=PASS`、source_revision、各段階のstatus/DB不変を確認する。
raw JWT、password/hash、DB URL、解決済みCompose/env、生HTTP応答は共有しない。
Secret/SpotBugs/OSVとTrivyは異なる検査で、CI成功を「全脆弱性0」へ読み替えない。
Docker証跡の現在/previousが同じimage IDなら**設定故障復帰**であり、異なる旧バイナリへのrollbackの証拠ではない。
失敗時は原因/段階を記録し、skip・retry追加・timeout緩和で成功に寄せない。Windows #145の過去失敗も消さない。

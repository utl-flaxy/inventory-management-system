# 主要な設計判断とトレードオフ

Issue #128 / 基準main `186aad8cb41fa7d7227304aeed4a4c3d6436ad69`。
実装済みの選択と、比較のための代替案を区別する。代替案は過去に実装して性能比較したという意味ではない。
[Transactionと行ロック](transactions-and-locking.md)に実行順とテスト対応をまとめた。
ポートフォリオの正式GOは独立レビュー後で、実利用者向け本番運用は引き続きNO GO。

## 1. 一つのアプリケーションとMariaDBで整合性を閉じる

**現行:** [OrderService](../../src/main/java/com/example/demo/service/OrderService.java)・
[InventoryService](../../src/main/java/com/example/demo/service/InventoryService.java)からJPA経由でMariaDBへ書き込み、
注文・在庫・台帳を一つのDB Transactionでcommitするモノリス。

**代替:** 注文/在庫のサービス分割、キュー、Saga/outboxによる非同期整合。
現行では一括rollbackとDB制約を読みやすく検証できる反面、DBが共通の障害点・競合点になる。
非同期化すれば独立scaleの余地はあるが、二重配送・補償・整合途中の公開契約が新たに必要。
それを実装せずに「分散でもexactly-once」とは説明しない。

[InventoryConcurrencyMariaDbIT](../../src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java)、
[InventoryAdjustmentIdempotencyIT](../../src/test/java/com/example/demo/controller/InventoryAdjustmentIdempotencyIT.java)が
同じDBへの競合・途中失敗・台帳の整合を検証する。

## 2. 悲観ロックと楽観ロックを用途で使い分ける

**現行:** 在庫を読む→不足を判断→減算する間はProduct行をPESSIMISTIC_WRITEで守る。
複数商品はID昇順。注文作成/調整では先にUser行、取消ではOrder→操作User→商品昇順。
[Repositoryのロックquery](../../src/main/java/com/example/demo/repository/ProductRepository.java)と
[Productの@Version](../../src/main/java/com/example/demo/entity/Product.java)を両方使う。
商品名/価格PUTはクライアントversion確認とversion付きUPDATEで古い更新を拒否する。

**代替:** 全操作を楽観ロック+自動retry、条件付きatomic UPDATE、SERIALIZABLE、process内synchronized。
現行は不足/価格snapshot/台帳をロック下で一貫して計算できるが、ホット商品では待機が発生する。
同User行の直列化は別商品にも及ぶ。楽観retryには再試行回数/冪等性/副作用の設計が必要で、
process内ロックだけでは別processやDB直接更新を防げない。現行は自動的な業務コマンド再実行を導入していない。

[異なる20顧客・商品更新競合IT](../../src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java)と
[取消/手動調整のロック順IT](../../src/test/java/com/example/demo/controller/InventoryAdjustmentIdempotencyIT.java)を根拠にする。
有限の反復成功を全スケジュールでのデッドロック不在と解釈しない。

## 3. 冪等性をRedisではなくDBの業務記録へ結び付ける

**現行:** 注文はcustomer/key UNIQUEと本文指紋、手動調整はactor/key UNIQUEと本文指紋。
同じkey/意味は元の結果へ、別の意味は409。stockと同じTxで保存されるため、業務rollback時はkeyも残さない。
Redisは現行構成へ導入していない。

**代替:** Redis等の外部key台帳、HTTP responseのbyte列cache、短期間のin-memory重複排除。
現行は二重書込みの整合問題を増やさずDB制約を最終防御にできるが、Userロックと永続メタデータのコストがある。
keyの自動TTL/削除機能はない。同じ注文の状態は進行し得るので、replayは過去のHTTP byte列の完全再生ではない。
外部通知/決済を追加するなら別の冪等性設計が必要。

根拠: [指紋生成](../../src/main/java/com/example/demo/service/OrderRequestFingerprint.java)、
[V4](../../src/main/resources/db/migration/V4__add_order_idempotency.sql)、
[V6](../../src/main/resources/db/migration/V6__add_inventory_adjustment_idempotency.sql)、
[IdempotentOrderIT](../../src/test/java/com/example/demo/controller/IdempotentOrderIT.java)、
[手動調整IT](../../src/test/java/com/example/demo/controller/InventoryAdjustmentIdempotencyIT.java)。

## 4. 注文時snapshotと整数金額を保持する

**現行:** [OrderItem](../../src/main/java/com/example/demo/entity/OrderItem.java)へ商品名・単価をコピーし、
円のlong/BIGINTとExact演算で合計する。売上は[完了注文](../../src/main/java/com/example/demo/repository/OrderRepository.java)から集計する。

**代替:** 常に商品マスター価格をJOIN、浮動小数点、複数通貨/税/端数を持つMoney型。
現行は過去注文の金額がマスター変更で変わらずoverflowも拒否できるが、円の整数だけで小数・通貨換算は扱わない。
データをsnapshotで重複保持するコストもある。注文合計と明細の一致はJava側の不変条件で、DB CHECKだけでは全件を強制しない。

[MoneyApiIT](../../src/test/java/com/example/demo/controller/MoneyApiIT.java)と
[snapshot保持のMariaDB IT](../../src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java)が境界・DB不変を検証する。
履歴は業務APIで追記する方式であり、DB管理権限に対する改ざん不能な監査ログではない。

## 5. DTOでHTTP契約を閉じ、DB制約も残す

**現行:** [OrderServiceのDTO入口](../../src/main/java/com/example/demo/service/OrderService.java)で必要な関連を読み、
[OSIV=false](../../src/main/resources/application.properties)でJSON変換時の遅延queryを避ける。
商品名は255コードポイント、注文は1〜100明細、数量/ページ/検索を検証し、
CHECK/UNIQUE/FKでも不正データを防ぐ。

**代替:** Entityを直接JSON公開、OSIVで関連を後読取り、DBエラーだけで入力を検証。
現行は内部password/role/関連を不用意に公開せず、400のfield errorをDB書込み前に返せる一方、
DTOとOpenAPIとDBの同期が必要。入力validationだけで並行競合まで解決するわけではない。

根拠: [OsivApiIT](../../src/test/java/com/example/demo/controller/OsivApiIT.java)、
[ProductNameValidationIT](../../src/test/java/com/example/demo/controller/ProductNameValidationIT.java)、
[OrderItemLimitIT](../../src/test/java/com/example/demo/controller/OrderItemLimitIT.java)。
注文一覧はIDページ→projectionをread-only REPEATABLE_READで取得し、
collection JOINへ直接Pageableを適用する方式を避ける。[OrderPagingIT](../../src/test/java/com/example/demo/controller/OrderPagingIT.java)を参照。

## 6. JWTの署名検証と現在のDB認可を分ける

**B1現行:** [JwtUtil](../../src/main/java/com/example/demo/security/JwtUtil.java)で署名/issuer/iat/expとUUID/世代を検証し、
[Filter](../../src/main/java/com/example/demo/security/JwtAuthenticationFilter.java)からprimary DBの新しいcommitted readでuid/世代/現在roleを照合する。
tokenのroleを権限の正本とはしない。Servlet自動登録は無効で、SecurityチェーンがJWT filterの実行を所有する。

**代替:** roleもtokenだけで認可、session認証、jti失効台帳。
DB照会によりrole変更/ユーザー不在を次の認証に反映できるが、DB負荷とDB障害時の可用性に依存する。
不正JWT/未認証401、権限不足403、DB一時障害503、予期しない障害500を分ける。

logout-allの全端末失効を実装し、個別失効/refresh/password変更/ADMIN強制失効APIは未実装。
TTL既定3600000msは外部設定可能で実稼働値は未確認。password hashだけの直SQL変更は既発行JWTを失効させない。
uidで同名の別主体から分離するが、旧validator・旧鍵・過去DBへの復帰には再受理リスクがある。
[認証契約](../auth.md#jwtライフサイクルと失効制約)はv1.0の失効未実装記録も保存する。
[Issue #79](https://github.com/utl-flaxy/inventory-management-system/issues/79)はB2/B3が残るためOPEN、jti台帳は別段階とする。

根拠: [JwtFilterRegistrationIT](../../src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java)、
[AuthDatabaseFailureIT](../../src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java)。
公開registerはROLE_USER固定、初期ADMINは[明示オフラインCLI](../initial-admin-provisioning.md)だけ。
[InitialAdminProvisioner](../../src/main/java/com/example/demo/provisioning/InitialAdminProvisioner.java)は接続単位GET_LOCKと
READ_COMMITTED Txで既存ADMINを確認し、INSERTのみを行う。通常業務の行ロックと同じ仕組みではなく、
単一writable DB/協調するCLIを前提にする。[並行CLI IT](../../src/test/java/com/example/demo/provisioning/InitialAdminLifecycleIT.java)を参照。

## 7. 認証負荷を入口で制限し、信頼proxyを明示する

**現行:** [LoginRateLimiter](../../src/main/java/com/example/demo/security/LoginRateLimiter.java)と
[RegistrationRateLimiter](../../src/main/java/com/example/demo/security/RegistrationRateLimiter.java)は独立した枠。
接続元/時間窓、同時処理、状態保持数を制限する。信頼proxyの転送情報だけを採用し、
prodでは実測したpeer設定がなければ起動/受付をfail-closedにする。

**代替:** usernameだけの失敗回数によるlockout、共有global quota、無制限のBCrypt、edge/分散limiter。
現行は第三者アカウントや他接続元との不適切な枠共有を抑えるが、NAT共有の誤拒否や分散攻撃、
複数instanceでの状態共有を完全に解決しない。再起動でprocess内の状態は失われる。
本番公開ではCaddy/ネットワーク層の容量・制限も別に判断する。

根拠: [LoginSecurityIT](../../src/test/java/com/example/demo/security/LoginSecurityIT.java)、
[RegistrationSecurityIT](../../src/test/java/com/example/demo/security/RegistrationSecurityIT.java)、[認証設計](../auth.md)。

## 8. HTTP statusの互換性と安全な障害応答

**現行:** 商品/注文作成は200、在庫不足は400 OUT_OF_STOCK。
状態/版/冪等性の競合は409、validation400、認証401/認可403、制限429/Retry-After、
一時障害503/予期しない障害500を[ProblemDetail](../../src/main/java/com/example/demo/exception/GlobalExceptionHandler.java)で返す。

**代替:** 作成201、在庫不足409等へ変更するAPI再設計。
現行client・テストの契約を維持する判断であり、200/400が唯一のREST設計だという主張ではない。
変更するならversion付け・client移行を別責務で判断する。内部例外本文・認証情報を応答へ出さず、
server生成requestIdと安全なeventで照合する。

根拠: [APIエラー仕様](../api-errors.md)、
[ProblemDetailContractIT](../../src/test/java/com/example/demo/security/ProblemDetailContractIT.java)、
[SafeExceptionLoggingTest](../../src/test/java/com/example/demo/exception/SafeExceptionLoggingTest.java)。

## 9. 単一host Docker/TLSと、実運用を分ける

**現行定義:** [Compose](../../docker-compose.yml)はMariaDBをhost公開せず、
appはloopback8080、JRE/non-root/read-only rootfs、明示的メモリ制限。
host CaddyがTLS終端する想定で、TerraformはEC2/EIP/SG等を定義する。

**代替:** RDS、独立build/registry、コンテナ基盤、managed TLS、remote state、監視サービス。
単一hostは学習/構成説明が容易な反面、DB・app・OS・buildが容量と障害点を共有する。
1GiB hostを安全と推奨せず、[容量別予算](../docker-runtime.md)の実測が必要。
現行構成のAWS配備・公的TLS・実secret・本番backup/復旧/SLAは未検証で、導入済みとは説明しない。

実証は[隔離Dockerデモ](../demo.md)と[CaddyProxyIT](../../src/test/java/com/example/demo/deployment/CaddyProxyIT.java)。
DB imageはCompose/Java ITの可変tagと、Docker demoのdigest pinを区別する。
rollbackは同一image IDの故障設定復帰を実証した範囲で、旧binary/schema/鍵への互換復旧とは別。
DB migrationをアプリの切替でundoせず、[運用手順](../deployment.md)で判断する。
B1の追加V7では[認証移行runbook](../security/jwt-revocation-migration.md)を優先し、旧validatorへの復帰や混在受付を許可しない。

### v1.1 B1の認証主体・失効

[レビュー済みADR](jwt-revocation-v1.1.md)を実装契約とし、UUIDの不変主体と世代を採用する。
username再割当てから主体を分離し、同ユーザーの全JWTを1回の世代増分で失効させる。
通常認証はprimaryへの1 readを維持し、login成功時は短いUser行ロック確認を追加する。
旧JWTのdual acceptanceを避ける代償として、停止/drainと全端末再ログインが必要。
同一imageの設定復帰、異binary rollback、過去DB復元は別の保証範囲である。
jti個別失効、password変更、ADMIN強制失効は未実装で、B1をIssue #79全体の完了とは扱わない。
実装根拠は[認証契約](../auth.md#jwtライフサイクルと失効制約)と[commit/競合IT](../../src/test/java/com/example/demo/security/AuthRevocationIT.java)。

## 10. 品質ゲートと未保証事項を公開する

**現行:** [CI](../../.github/workflows/ci.yml)はJava full verify/MariaDB/Docker、Terraform mock、
Gitleaks、SpotBugs SECURITY、SBOM/OSVの必須5job。検出/実行エラーを隠して成功にはしない。
OSVとTrivyはaudit-onlyであり、job成功は脆弱性ゼロの宣言ではない。
基準mainのOSVは169 components/0 advisory、Trivyはapp imageのOS49件（MEDIUM27/LOW22）/library0。
DB/Caddy/hostや全git履歴・すべての攻撃をスキャン済みとは言わない。

**代替:** coverage閾値、脆弱性severityによる強制失敗、benchmark/SLA gate。
現行は実DBの不変条件と否定系を重視するが、件数/coverage率だけで品質や本番性能を保証しない。
本番のrisk policyとv1.0ポートフォリオ受入を混ぜず、残存findingは継続監査する。

Windows DatabaseTimeoutITの初回3失敗は根因未確定のまま
[最終監査](../audit/portfolio-v1-final-acceptance-2026-10-18.md#ローカル補助検証)へ残す。
単独再実行やLinux CI成功で最初の失敗を抹消しない。skip/timeout緩和はしない。
[現行証跡](../release-status.md)・[テスト手順](../testing.md)と本PRのCIを併読し、
Issue #128の文書整備だけで正式GOと判断しない。Draft PR #103はマージ対象外。

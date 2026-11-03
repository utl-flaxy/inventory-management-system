# Inventory Management System

**同時注文・重複リクエスト・認証失効があっても、注文・在庫・履歴の整合性を守る Java / Spring Boot の業務REST APIです。**

> **Portfolio v1.0 完成（2026-11-03）** — tag [`v1.0`](https://github.com/utl-flaxy/inventory-management-system/tree/v1.0) = `1383b64d5777387ef0674fc2c3a11deed3788638`
> 最終main CI: [Spring Boot CI #41646137190](https://github.com/utl-flaxy/inventory-management-system/actions/runs/41646137190) — 必須5ジョブ成功、Java **2,514件成功（失敗・エラー・skip 0）**、Docker業務検証PASS
> **本番環境には未配備です（PRODUCTION NO GO）。** 応募用作品としての完成であり、実利用者向けの本番運用承認ではありません。

## 30秒でわかる概要

- **何をするAPIか**: 顧客は商品を見て注文・取消し、ADMINは商品・在庫・出荷/完了・売上・CSVと利用者JWTの失効を管理します。
- **技術**: **Java 17 / Spring Boot 4.1.1 / Spring Security 7 / MariaDB 12.2 / JPA・Hibernate / Flyway / Docker / GitHub Actions**
- **重視したこと**: 一つのアプリとDBの中で、Transaction・行ロック・DB制約を使って「二重に売らない・二重に処理しない・失効したトークンを通さない」を実装し、**実MariaDB（Testcontainers）で検証**しています。

## 3つの見どころ

|課題|実装したこと|ソースと常設テスト|
|---|---|---|
|**在庫の同時更新**<br/>20人が在庫10の商品を同時に購入|DB行ロックとTransactionで販売超過を防止。商品をID昇順でロックしてデッドロックを避け、注文・在庫・履歴を一括commit/rollback|[OrderService.createOrder](src/main/java/com/example/demo/service/OrderService.java#L121) → [20顧客・在庫10の競合IT](src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java#L242)。各試行で成功10・不足10、最終在庫0|
|**注文の冪等性**<br/>通信再送で同じ注文POSTが重複|顧客別`Idempotency-Key`＋リクエスト本文の指紋＋DB UNIQUE制約。業務更新と同じTransactionに保存し、二重注文・二重減算を防止|[OrderService](src/main/java/com/example/demo/service/OrderService.java#L121) / [指紋生成](src/main/java/com/example/demo/service/OrderRequestFingerprint.java) → [HTTP並行再送IT](src/test/java/com/example/demo/controller/IdempotentOrderIT.java#L282)。同key同内容は200で同じ注文、別内容は409|
|**JWT認証の失効**<br/>発行済みJWTを後から無効化|UUID主体＋DBの認証世代照合。全端末logout、本人のpassword変更、再認証したADMINによる強制失効を、世代更新・監査記録と同時にcommit|[logout](src/main/java/com/example/demo/service/AuthRevocationTransactions.java#L31) / [password](src/main/java/com/example/demo/service/PasswordChangeTransactions.java#L35) / [ADMIN](src/main/java/com/example/demo/service/AdminTokenRevocationTransactions.java#L40) → [logout IT](src/test/java/com/example/demo/security/AuthRevocationIT.java) / [password IT](src/test/java/com/example/demo/security/AuthPasswordIT.java) / [ADMIN IT](src/test/java/com/example/demo/security/AdminTokenRevocationIT.java)。旧JWTは401|

設計理由→実装→テスト→保証の限界を面接向けにまとめた[3シナリオのウォークスルー](docs/portfolio-walkthrough.md)もあります。

<details>
<summary>その他の設計観点（途中失敗・認可・障害時の応答）</summary>

| 課題 | 実装上の判断 | 回帰の根拠 |
|---|---|---|
| 同時注文による販売超過・更新消失 | 商品の悲観ロック、商品ID昇順取得、Transaction、version管理。[OrderService](src/main/java/com/example/demo/service/OrderService.java#L121) / [Product](src/main/java/com/example/demo/entity/Product.java) | [InventoryConcurrencyMariaDbIT](src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java#L242)。異なる実DB顧客20人、在庫10で成功10・不足10、履歴・金額も検証 |
| 通信再送による二重処理 | 利用者単位のkey/指紋。[OrderService](src/main/java/com/example/demo/service/OrderService.java#L121) / [InventoryService](src/main/java/com/example/demo/service/InventoryService.java) | 同key/同内容は初回結果、別内容は409。[IdempotentOrderIT](src/test/java/com/example/demo/controller/IdempotentOrderIT.java#L282) / [在庫調整IT](src/test/java/com/example/demo/controller/InventoryAdjustmentIdempotencyIT.java) |
| 途中失敗と過去注文の書換え | 注文・在庫・履歴を一括commit/rollback、注文時snapshot。[OrderService](src/main/java/com/example/demo/service/OrderService.java#L121) / [OrderItem](src/main/java/com/example/demo/entity/OrderItem.java) | [実MariaDBの途中失敗・snapshot回帰](src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java#L242) / [金額境界IT](src/test/java/com/example/demo/controller/MoneyApiIT.java) |
| 認証と権限の混同 | 署名/issuer/期限を検証しDBの現在roleで認可。[JwtUtil](src/main/java/com/example/demo/security/JwtUtil.java) / [JwtAuthenticationFilter](src/main/java/com/example/demo/security/JwtAuthenticationFilter.java) | 実HTTPの[JWT否定系・USER/ADMIN境界](src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java) / [所有者認可IT](src/test/java/com/example/demo/security/AuthorizationMatrixIT.java) |
| 障害時の誤った成功判定 | 401/403/409/429、一時DB障害503・予期しない500を区別。[ServerFailureResponse](src/main/java/com/example/demo/exception/ServerFailureResponse.java) / [prod設定](src/main/resources/application-prod.properties) | [DB切断と回復IT](src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java) / [待機・rollback・再試行IT](src/test/java/com/example/demo/security/DatabaseTimeoutIT.java)。shutdownは[Docker検証](scripts/docker/verify_image.py) |

[異なる顧客の競合条件と過去のロック除去対照実験](docs/orders/inventory-concurrency.md)を参照してください。
その対照実験の結果は当時のSHAに対する証拠であり、今のmainからロックを除去しているわけではありません。

</details>

## v1.0の検証結果

対象: main / tag `v1.0` = `1383b64` の [push CI #41646137190](https://github.com/utl-flaxy/inventory-management-system/actions/runs/41646137190)（2026-11-03、event=push・head一致・completed/success）。数値はこのrunのログ・artifactで確認したものです。

|検査|結果|
|---|---|
|必須CI|`test` / `terraform` / `secret-scan` / `java-static-analysis` / `dependency-audit` の**5/5成功**|
|Java 17 全回帰|Surefire 1,469 ＋ Failsafe 1,045 ＝ **2,514件、失敗・エラー・skip 0**。Failsafeは実MariaDB/Testcontainers|
|Docker業務検証|clean `git archive`からbuildしたimageで**業務9段階**（登録→ADMIN作成→認証→認可→商品作成→注文と再送→在庫調整→取消→ADMIN確認）＋**JWT失効B1/B2/B3**がPASS。非root・read-only、Flyway V1〜V9、正常停止、同一imageの設定故障からの復帰|
|その他のゲート|Python 68件、Terraform mock 49件＋意図的な不正設定3件の拒否（AWS接続なし）、配備順序15件、Gitleaks 未解決0（421 files）、SpotBugs SECURITY違反0（160 classes）|
|依存・image監査|OSV: 169 components / advisory 0。Trivy（runtime image）: CRITICAL/HIGH 0、**OS 32件（MEDIUM 28 / LOW 4）残存**、library 0。どちらもaudit-onlyで、脆弱性ゼロの保証ではありません|

v1.0までの是正経緯（libbrotli1のHIGH是正、MariaDB Connector/J 3.5.11への更新）と受入判定P1〜P5は[release-status](docs/release-status.md)にまとめています。

### 現在のmain CIを確認する（live）

[mainへのpushに絞ったSpring Boot CI](https://github.com/utl-flaxy/inventory-management-system/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)を開き、
先頭runの対象SHAが[main HEAD](https://github.com/utl-flaxy/inventory-management-system/commits/main/)と一致するか、必須5ジョブがすべてcompleted/successかを確認します。
新しいrunが未完了・失敗なら、上のv1.0時点の成功を現在の成功へ読み替えません。PRのCIはPR本文で別に確認します。

必須ジョブの外部Actionは40桁SHA固定、権限はcontents:read、persist-credentials:false。mainはstrictな5チェック、管理者適用、linear history、force push/delete禁止を確認しています。
GitHubのrequired approving reviewsは0で、ChatGPTの独立レビューは人手の運用です。自動的に独立承認が強制されるとは説明しません。

## 技術構成

| 項目 | v1.0の構成 |
|---|---|
| Java / Framework | Java 17、Spring Boot **4.1.1**、Spring Framework 7.0.9、Tomcat 11.0.27 |
| Security | Spring Security 7、JJWT 0.12.7、BCrypt、Bearer JWT（HTTP sessionなし・DB世代照合） |
| Persistence | Spring Data JPA / Hibernate、MariaDB 12.2（Connector/J 3.5.11）、Flyway V1〜V6＋Java V7（認証uid/失効世代/監査）＋V8（password変更監査）＋V9（ADMIN強制失効監査） |
| Web / API docs | Spring MVC、Jakarta Validation、springdoc 3.1.1。Swaggerは明示したlocal profileのみ |
| Build / Tests | Maven Wrapper、JUnit、Surefire/Failsafe、Testcontainers |
| Container | digest固定Java17 JDK build + JRE runtime、Compose、UID/GID10001:10001 |
| Infra / CI | Terraform 1.15.6 / AWS provider 6.68.0、Caddy 2.11.7の定義（未配備）、GitHub Actions必須5ジョブ |

バージョンの正本は[pom.xml](pom.xml)、Dockerfile、workflow、解決済みSBOMです。
DBは通常Composeが`mariadb:12.2`、Java ITも主に`12.2.2`・一部`12.2`の可変tagです。
[Dockerデモ](scripts/docker/verify_image.py)だけは`12.2.2`の完全digestへ固定し、両者を同じbit再現性とは扱いません。
H2はテスト補助で、本番用DBではありません。schemaはFlywayが管理し、Hibernateは`validate`、OSIVは無効です。

## 構成と信頼境界

アプリ内の要求経路です。JWT filterとURL認可はControllerの**前段**にあり、業務Serviceでは所有者・状態・認証世代などを再確認します。

```mermaid
flowchart TD
    Client["Customer / Administrator"] --> Security["Spring Security filter chain<br/>JWT署名・UUID/世代・URL認可"]
    Security --> MVC["Spring MVC Controllers<br/>DTO・入力検証・メソッド認可"]
    MVC --> Service["Application Services<br/>業務認可・操作ごとのTx設計"]
    Service --> Repo["JPA Repositories<br/>行ロック・条件付き更新"]
    Repo --> DB[("MariaDB<br/>注文・在庫・履歴・認証世代")]
```

[SecurityConfig / JWT filter](src/main/java/com/example/demo/security/SecurityConfig.java)がHTTP入口を保護し、
認証時のDB世代照合は[JwtUserAuthenticator](src/main/java/com/example/demo/security/JwtUserAuthenticator.java)の独立したread-only Txで行います。
公開register/loginはpermitAllです。注文などのTx境界はService、関連を持たない公開登録などの保存はRepositoryのTxを使い、全処理が一つのTxになるわけではありません。
注文では[OrderService](src/main/java/com/example/demo/service/OrderService.java#L121)のTx内でUser→商品ID昇順のロック、key/指紋照合、注文時価格snapshotと在庫履歴の保存を行い、
[DB UNIQUE制約](src/main/resources/db/migration/V4__add_order_idempotency.sql)とともに二重処理を防ぎます。途中失敗は同じTxでrollbackします。
詳細な境界と例外は[Transaction設計](docs/design/transactions-and-locking.md)、各機能の根拠は[3シナリオ](docs/portfolio-walkthrough.md)を参照してください。
schemaはFlyway V1〜V9が起動時に管理し、Hibernateはvalidateで照合します。

**隔離デモの実構成:** host上のPython → `127.0.0.1:ランダムport` → 新規app → 新規Docker networkのtmpfs MariaDB。
公的TLSや本番ネットワークは含まず、Caddy/local CAは別の`CaddyProxyIT`で検証します。

<details>
<summary>本番想定ネットワークの定義（未配備・未承認）</summary>

以下は**本番想定の定義**です。使い捨てDockerデモの実構成とは区別します。

```mermaid
flowchart LR
    Client[Internet: 将来の公開クライアント] -->|80/443: 公開想定| Caddy[host Caddy / TLS]
    Caddy -->|127.0.0.1:8080| App[Compose app / Java17 JRE]
    App -->|Docker network: db:3306| DB[(MariaDB / host公開なし)]
    Operator[運用者CIDR] -->|限定SSH:22| Host[EC2想定host]
```

- appは`127.0.0.1:8080`だけにpublish。DBのhost3306、Caddy admin2019、app8080をpublicへ開く定義はありません。
- Caddyは転送ヘッダーを上書き/除去します。appのprod起動には実測した信頼proxy peerを`LOGIN_TRUSTED_PROXIES`へ設定する必要があります。Docker内部IPの決め打ちはしません。
- Caddyの実証は`CaddyProxyIT`の隔離network/local CAとmock hostまで。実DNS・EIP・公的証明書・実host peerは未確認です。
- runtimeは非root、read-only rootfs、capabilities削除、bounded tmpfs、明示メモリ制限です。[容量別予算](docs/docker-runtime.md)では、appとMariaDBを同居させる1 GiB hostを安全な本番構成とは推奨していません。

</details>

## APIと業務契約

| 利用者 | API | 主な契約 |
|---|---|---|
| 公開 | POST `/auth/register`、POST `/auth/login` | 登録はROLE_USER固定。独立した登録/ログイン制限、超過429/Retry-After、接続元判定不能503 |
| 本人 | POST `/auth/logout-all` | 全端末失効をcommit後204、旧JWT/再送401。独立した認証管理制限、移行時は旧JWTを一括拒否 |
| 本人 | POST `/auth/password` | 現password検証後、新hash＋世代＋監査を一括commitし204。旧JWT全401、新passwordで再ログイン。logout-allと同時受付枠を共有 |
| ADMIN | POST `/api/v1/admin/users/{uid}/token-revocations` | 現ADMIN権限＋本人password再認証、対象全端末を失効し204。対象不在404は再認証後だけ。自己失効も可能 |
| USER / ADMIN | GET `/products`、GET `/products/search?keyword=...`、GET `/products/{id}` | 一覧/検索のページング、keyword Unicode境界 |
| ADMIN | POST/PUT/DELETE `/products`系 | 商品名255コードポイントまで。PUTはversion必須、stockを変更しない |
| 本人 | POST/GET `/api/v1/me/orders`、GET `/{id}`、POST `/{id}/cancel` | 作成1〜100明細、Idempotency-Key必須、他者の注文は404 |
| ADMIN | `/api/v1/admin/orders`系 | 一覧・詳細・出荷・完了・CSV・売上・取消。詳細は設計文書 |
| ADMIN | `/api/v1/admin/products/{id}/inventory-adjustments`、`/inventory-movements` | 差分調整はkey必須、履歴を保存 |
| probe | `/livez`、`/readyz`（prod） | readinessはDBを含む。公開bodyはstatusのみ |

注文金額は円の整数long/BIGINT。商品名・単価は注文時にsnapshotし、overflowは変更前に拒否します。
旧`/orders/**`は廃止。Entityを直接返さずDTOを使います。

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> SHIPPED
    SHIPPED --> COMPLETED
    PENDING --> CANCELLED
```

cancelはPENDINGだけで、在庫を一度だけ戻します。エラーは`application/problem+json`と`code`、server生成`X-Request-Id`で診断します。
詳細: [認可](docs/security/order-authorization.md)、[状態遷移](docs/orders/state-transitions.md)、[冪等性](docs/orders/idempotency.md)、[ページング](docs/orders/pagination.md)、[CSV](docs/order-csv.md)、[売上](docs/sales.md)、[APIエラー](docs/api-errors.md)。

## デモとテストを手元で再現する

Java全回帰にはJava17、Git、Bash、Docker daemon、Python3.12以上を用意します。WindowsのBashはGit for Windowsの標準配置が必要です。
隔離DockerデモにはCompose v2、Terraformのoffline mockにはCLI **1.15.6**も必要です。productionの資格情報は使いません。
初回取得用network・前提の切り分け・Windows差分は[テスト手順](docs/testing.md#実行前提と安全な確認)を参照してください。

```bash
./mvnw test       # unit/slice/H2補助。full verifyの代わりにはしない
./mvnw verify    # 実MariaDB/Testcontainersを含む全回帰
python3 scripts/docker/verify_image.py --scan --output target/portfolio-demo
```

Windowsは`./mvnw.cmd`と`python`へ読み替えます。JVMは`JAVA_HOME`でJava17を指定してください。
最後のコマンドはclean `git archive HEAD`からimageを作り、固有名のcontainer/network、新規tmpfs DB、random資格情報を使用します。
既存の`inventory-app`/`inventory-db`/volumeは使用しません。cleanupは所有labelを照合した自身の資源だけです。
手順・期待する在庫10→8→11→13・秘密値を含まない証跡は[デモ手順](docs/demo.md)へまとめています。
[在庫競合・冪等性・JWT失効の対象ITコマンド](docs/portfolio-walkthrough.md)も掲載しています。有限の試験であり本番負荷測定ではありません。
通常Composeは固定名と永続volumeを持つため、この隔離デモの代わりに安易に`docker compose up`しないでください。

Swagger UIはprodで無効です。local開発設定は[設定文書](docs/configuration.md)、ADMINの明示CLIは[provisioning手順](docs/initial-admin-provisioning.md)を参照してください。
公開API・通常起動・migrationからADMINを作る仕組みはありません。

## 資料ガイド

| 目的 | 資料 |
|---|---|
| まず動きを見る | [隔離Dockerデモ](docs/demo.md)：認証 → 商品 → 注文 → 在庫調整 → キャンセル → ADMIN確認を1コマンドで再現 |
| 技術面接の準備 | [3シナリオのウォークスルー](docs/portfolio-walkthrough.md)：在庫競合・注文再送・JWT失効の理由→実装→テスト→保証限界 |
| 設計を深く読む | [Transaction・ロック設計](docs/design/transactions-and-locking.md) / [主要な設計判断と代替案](docs/design/decisions.md) / [注文設計](docs/orders/order-aggregate.md) / [在庫設計](docs/inventory.md) / [認証・JWT制約](docs/auth.md) |
| テスト・品質 | [テスト手順](docs/testing.md) / [CI品質ゲート](docs/ci-quality-gates.md) / [Action更新方針](docs/github-actions-pinning.md) |
| 受入判定と証拠 | [release-status（P1〜P5・対象SHA・既知のリスク）](docs/release-status.md#portfolio-v1-final-acceptance) |
| 配備・運用の設計（未配備） | [配備/rollback](docs/deployment.md) / [Docker/メモリ](docs/docker-runtime.md) / [TLS](docs/tls.md) / [Terraform](terraform/README.md) / [DB運用](docs/db/README.md) |

## 本番未配備と既知の制約

**現行構成はAWS未配備として扱います。** 実AWSでの配備・公開TLS・本番運用の確認証跡はありません。
TerraformとCaddyは本番を想定した定義であり、Deploy workflowは`disabled_manually`です。
過去のAWS画像は現行構成の稼働証明ではありません。Portfolio v1.0の完成と、本番運用のGO判定は別物で、**PRODUCTION NO GO**を維持します（本番受入は[Issue #166](https://github.com/utl-flaxy/inventory-management-system/issues/166)でBLOCKED）。

- Deployは**無効**。main CI成功だけで現在配備される状態ではありません。将来の配備はCIの`DEPLOY_SHA`、revision label、app-onlyの`--no-deps --no-build`切替を維持する設計です。
- 現CI/デモのrollbackは**同一image IDの設定故障から直前設定へ復帰**する試験です。DB container再作成なし・業務データ不変を確認します。異なる旧バイナリと新schemaの互換性、実EC2での復旧を保証しません。
- app rollbackはDB rollbackではありません。migrationは自動undoせず、[backup/restore](docs/db/backup-restore.md)とschema互換性の判断が別途必要です。
- B1のUUID主体＋世代/logout-all、B2の本人password変更、B3のADMIN再認証付き強制失効は実装済みです。[JWTライフサイクル](docs/auth.md#jwtライフサイクルと失効制約)と[停止移行・rollback制約](docs/security/jwt-revocation-migration.md)を参照。[Issue #79](https://github.com/utl-flaxy/inventory-management-system/issues/79)はCLOSED、個別jti失効は[Issue #134](https://github.com/utl-flaxy/inventory-management-system/issues/134)で保留。refresh・password reset・role変更APIは未実装です。認証済みの進行中業務を強制rollbackする機能ではなく、旧binary・旧鍵・過去DBへの復帰には再受理リスクがあります。
- Flyway/MariaDB12.2互換性WARNと、v1.0時点のTrivy **OS32（MEDIUM28/LOW4）**を開示して継続監査します。旧OS33/HIGH1の発見履歴と[公式修正版・新main CIによる是正](docs/release-status.md#p2-remediation-evidence)は分けて保存します。残存32件は未解消で、負荷・容量・監視・SLA・実secret・公的TLS・本番backup/restore・異版rollbackは未検証です。
- Windows DatabaseTimeoutITの初回観測CLI3失敗は根因未確定です。単独再実行やLinux CI成功で失敗を抹消せず、[監査記録](docs/audit/portfolio-v1-final-acceptance-2026-10-18.md#ローカル補助検証)を保持します。
  証明書時刻・Wait strategy・Caddy初回失敗も[Issue #145](https://github.com/utl-flaxy/inventory-management-system/issues/145)で追跡し、今回の成功で解消としません。
- Terraformのremote state/lockingは[Issue #75](https://github.com/utl-flaxy/inventory-management-system/issues/75)。RDS/Redisは将来拡張です。S3 backend/bootstrapの定義・静的検証と実AWSでの運用受入は分けます。
  [remote state ADR](docs/design/terraform-remote-state.md)に従うS3部分設定と[独立bootstrap root](terraform-bootstrap/README.md)を静的実装しています。実AWSでのbackend作成・移行・IAM/排他/復旧は未検証です。[移行・復旧手順](docs/operations/terraform-remote-state.md)は別承認が必要で、Issue #75はOPENです。
  [AWS無接続のsynthetic検証と拒否ゲート](terraform-sandbox-tests/README.md)を追加しています。B01〜B14は未承認、実TF-CはBLOCKEDで、CI成功はAWS実行許可を意味しません。

## 履歴・開発資料

- [再構成前READMEの全文（固定main SHA）](https://github.com/utl-flaxy/inventory-management-system/blob/5f0fae4ebd89754e23538cb09f97a1ff037255ed/README.md)。旧図・スクリーンショット・当時の説明をこの履歴に保存します。
- [2026-10-02監査46件の当時の台帳](docs/audit/2026-10-02-remediation-traceability.md)、[依存66件の元台帳](docs/audit/2026-10-12-dependency-advisories.md)、[Issue76の当時の実測](docs/audits/issue-76-validation.json)。現在値は[release-status](docs/release-status.md)で区別します。
- [10月23日の受入追補](docs/audit/portfolio-application-acceptance-2026-10-23.md)：当時のDoD16との対応と監査履歴。10月18日の[独立受入GO](https://github.com/utl-flaxy/inventory-management-system/issues/130#issuecomment-6251677031)は当時の記録です。
- [Epic #8](https://github.com/utl-flaxy/inventory-management-system/issues/8)、[Issue #123](https://github.com/utl-flaxy/inventory-management-system/issues/123)。Draft PR #103は統合検証用の履歴で、マージ対象・現行mainの品質証拠に含めません。

APIのOpenAPI版表示やPOMのSNAPSHOTは、製品v1.0のリリース承認を意味しません。

<details>
<summary>v1.0以前の検証snapshotと経緯（README旧版から原文保存。現在値ではありません）</summary>

以下はv1.0完成前のREADMEに掲載していた日付・SHA固定の記録です。数値・表現は当時のまま保存し、上のv1.0の結果へ読み替えません。

**最終仕上げの基準snapshot（2026-10-29、PR #172採用後）:** main `4cd791cecf36b473bead1a699a20182038eabfac`、Java **2,514件・F/E/S 0**、必須CI **5/5成功**。
[対象mainのpush CI](https://github.com/utl-flaxy/inventory-management-system/actions/runs/40870761655)は10月29日の実行記録です。
[P1〜P5の最終受入候補と保証限界](docs/release-status.md#portfolio-v1-final-acceptance)、[3シナリオの実行・source/test対応](docs/portfolio-walkthrough.md)へ進めます。
この日付固定の成功を将来のmainへ流用しません。[現在のmain CI](#現在のmain-ciを確認するlive)は別に確認します。

**判定案: PORTFOLIO v1.0 READY FOR FINAL APPROVAL。P1〜P5はPASS候補であり、正式独立承認・タグ作成は未実施。本番運用はNO GO。**
10月18日の[独立受入GO](https://github.com/utl-flaxy/inventory-management-system/issues/130#issuecomment-6251677031)は当時の記録として保存し、拡張後の本提案と区別します。
10月29日にOS33/HIGH1を発見し、[Issue #171](https://github.com/utl-flaxy/inventory-management-system/issues/171)→[PR #172](https://github.com/utl-flaxy/inventory-management-system/pull/172)でlibbrotli1を公式修正版`1.1.0-2ubuntu0.1`へ限定更新しました。新main CIで対象CVE非検出・CRITICAL/HIGH0・**OS32（MEDIUM28/LOW4）、library0**を確認し、[P2解除条件を満たした根拠](docs/release-status.md#p2-remediation-evidence)を提出します。
個別jti失効/refresh/password resetは未実装です。OSV advisory0とTrivy HIGH0は別の検査結果であり、残存32件を受容済み・解消済みと扱いません。
**2026-11-03:** PR #170 merge後のmain `808df99`のCIで、MariaDB Connector/J 3.5.10に新規advisory4件（MEDIUM3/LOW1）を検出しました。上の10月29日の結果は当時のsnapshotとして保持し、[Issue #173](https://github.com/utl-flaxy/inventory-management-system/issues/173)で3.5.11へ限定更新します（[時系列](docs/release-status.md#connector-j-remediation-2026-11-03)）。

#### 最終仕上げの基準snapshot（PR #172採用後・2026-10-29）

main **`4cd791cecf36b473bead1a699a20182038eabfac`** / [main push CI40870761655](https://github.com/utl-flaxy/inventory-management-system/actions/runs/40870761655)。
10月29日実行のログ・artifactを同日に取得し、event=push・head一致・completed/success・必須5/5を確認しました。

|検査|このSHA/runの実測|
|---|---|
|Java17 / Python|Surefire1,469＋Failsafe1,045＝**2,514、F/E/S 0/0/0** / **68件成功**|
|Terraform / 配備順序|host27＋bootstrap14＋synthetic8＝**49成功**、mutation拒否3 / **15シナリオ成功**。実AWS・実配備ではない|
|Secret / SpotBugs|例外0・未解決0（421 tracked files）/ SECURITY違反0（160 main classes）。実正常/異常self-test成功|
|OSV / Trivy|**169 components・advisory0** / **OS32（CRITICAL0/HIGH0/MEDIUM28/LOW4）、library0**。libbrotli1修正済み。異なる監査対象、audit-only|
|Docker / DB / 診断|非root/read-only JRE17、業務9＋B1/B2/B3、V1〜V9、shutdown・同image設定故障復帰、隔離backup/restore、cleanup前診断の回帰成功|

Flyway互換性WARN3件を保持。#145の根因解決や本番復旧の証明ではありません。
[受入候補P1〜P5と出典](docs/release-status.md)へ。新しいPR HEADのCIはPR本文の別runで確認し、以下の旧snapshotは遡及変更しません。

[CVE発見→Issue #171→PR #172→新mainの検証](docs/release-status.md#p2-remediation-evidence)を照合し、P2をPASS候補へ更新しました。正式GOは独立レビュー待ちです。

<details>
<summary>PR #172採用前の基準main1a647a3 / CI40328908744（履歴原文）</summary>

#### 最終仕上げの基準snapshot（2026-10-29照合）

main **`1a647a393dac147825293e0ccba0fd84f30bc5fa`** / [main push CI40328908744](https://github.com/utl-flaxy/inventory-management-system/actions/runs/40328908744)。
10月26日実行のログ・artifactを10月29日に取得し、event=push・head一致・completed/success・必須5/5を確認しました。

|検査|このSHA/runの実測|
|---|---|
|Java17 / Python|Surefire1,469＋Failsafe1,045＝**2,514、F/E/S 0/0/0** / **68件成功**|
|Terraform / 配備順序|host27＋bootstrap14＋synthetic8＝**49成功**、mutation拒否3 / **15シナリオ成功**。実AWS・実配備ではない|
|Secret / SpotBugs|例外0・未解決0（420 tracked files）/ SECURITY違反0（160 main classes）。実正常/異常self-test成功|
|OSV / Trivy|**169 components・advisory0** / **OS32（MEDIUM28/LOW4）、library0**。異なる監査対象、audit-only|
|Docker / DB / 診断|非root/read-only JRE17、業務9＋B1/B2/B3、V1〜V9、shutdown・同image設定故障復帰、隔離backup/restore、cleanup前診断の回帰成功|

Flyway互換性WARN3件を保持。#145の根因解決や本番復旧の証明ではありません。
[受入候補P1〜P5と出典](docs/release-status.md)へ。新しいPR HEADのCIはPR本文の別runで確認し、以下の旧snapshotは遡及変更しません。

</details>

<details>
<summary>2026-10-23の応募版snapshot（履歴）</summary>

#### 応募版の確認snapshot（2026-10-23）

main **`1632db72ebedfdc3cf316c442f199022e253133d`** / [main push CI39937244198](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39937244198)のログ・artifactを取得して照合しました。

|検査|このSHA/runの実測|
|---|---|
|Java17 / Python|Surefire1,384＋Failsafe986＝**2,370、F/E/S 0/0/0** / **68件成功**|
|Terraform|host27＋bootstrap14＋synthetic8＝**49件成功**、mutation3件を期待したassertionで拒否。AWS無認証・backend=false|
|Secret / SpotBugs|例外0・未解決0（397 tracked files）/ SECURITY違反0（151 classes）。実正常/異常self-testも成功|
|OSV / Trivy|**169 components・advisory0** / **OS32（MEDIUM28/LOW4）、library0**。異なる監査対象で、audit-only|
|Docker / Flyway|非root/read-only JRE17、業務9段階＋B1/B2/B3、V1〜V9、shutdown、同image設定故障復帰。公的TLS/実host/異版rollbackは未検証|

必須5ジョブは全SUCCESS。PR HEADの実CIはPR本文の別runで確認します。
出典・DoD16・残余は[日付付き受入追補](docs/audit/portfolio-application-acceptance-2026-10-23.md)へ。以下の過去snapshotは数値を置き換えず保存します。

</details>

依存監査の[2026-10-21追補](docs/audit/2026-10-21-dependency-rebaseline-supplement.md)では、
旧Boot3.5のJava advisory66件と、main `7d10cca` のOSV169 components/0件・Trivy OS31件を区別しています。
Boot4のソース採用済みと本番導入・復旧・異版rollback未検証も別に判定します。以下の10月18日snapshotは当時の記録として保持します。

#### 監査基準snapshot（2026-10-18 UTC確認）

<details>
<summary>10月18日の固定snapshot・以前のrun（現在値ではありません）</summary>

以下はmain **`0614d7d306fed5a3ec43ba7a7502b8bf7e65f3ed`**（PR #131 merge後）の
[main push CI 39136004541](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39136004541)を取得して確認した固定記録です。
mainが進んだだけでこのSHA/runを置き換える必要はありません。現在の成否は上のlive入口で確認し、監査時点の値と区別します。
旧snapshotの[186aad8 / CI39123950859](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39123950859)、
[f154801 / CI39127003622](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39127003622)、
[5f0fae4 / CI39109302086](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39109302086)も当時の証拠として保存します。
参照方針とD01/D12の是正根拠は[release-status](docs/release-status.md#d01d12の是正記録issue-132)へまとめています。

| 検証 | 監査snapshotで確認した結果 | 保証範囲 |
|---|---|---|
| Java17 full verify | **2,176件** = Surefire1,314 + Failsafe862、F/E/S=0 | MariaDB12.2.2 / JWT / 注文・在庫 / DB障害等。全負荷や将来の非フレーク性は未保証 |
| Python / Terraform | **29件 / mock27件**成功 | gate方針、Terraform構文・mock plan。実AWSへの適用ではない |
| Gitleaks / SpotBugs | 例外0・未解決0 / SECURITY違反0 | 当該SHAのtracked tree / main bytecode。履歴全体・全SASTではない |
| OSV / Trivy | Java169 components・advisory0 / **OS49件**（MEDIUM27/LOW22）、library0 | 別の監査対象。OSV0とimage脆弱性0を混同しない |
| Docker | clean build、非root、readiness、実API、shutdown、設定故障復帰PASS | 使い捨てlinux/amd64環境。公的TLS・実host性能・異版rollbackは未保証 |

</details>

</details>

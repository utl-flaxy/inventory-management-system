# CLAUDE.md

このファイルは、Claude Code 等の開発エージェントがこのリポジトリで安全に作業するためのガイドです。
記載は **main @ e1155a20ad22c09bd56f0f1c6e8b5e123208280c（2026-10-17確認）** を基準とします。古いIssue・過去の監査記録は現在の実装を保証しません。
変更前には対象ブランチの実コード・正本文書を再確認してください。

## 作業原則

1. **1 Issue = 1責務**。Issueと受入条件を確認し、専用branch → 小さなPR → 実CI → review → 運用者判断の順に進める。
2. `main`へ直接pushしない。merge、auto-merge、Deploy workflow有効化は運用者の明示的な承認なしに行わない。
3. **AWS/Terraform apply・destroy、本番DB/既存Docker volume、本番DNS/TLS、実secretや秘密鍵を操作しない**。必要な作業はrunbookと未実施事項として報告する。
4. PR前に Java 17 + Docker で `./mvnw verify`。unitだけの `test`、skipしたテスト、greenでないCIを完了と扱わない。Java/Terraform/secret/static-analysis/dependency-auditとDocker回帰を確認する。
5. 証拠は「コード上の対策」「使い捨て環境でのテスト」「GitHub実CI」「実本番適用」を区別する。変更しなかった領域を検証済みと断定しない。
6. トークン・password/hash・JWT・DB接続文字列・SSH鍵・`.env`・Terraform state・バックアップ・解決済みComposeの秘密値をログ、PR、artifactに載せない。実値を確認・転載しない。

## プロジェクトと現行技術スタック

在庫・注文の整合性を主題とする Spring Boot REST API。Controller → Service → Repository の責務分離と、DTOによる外部契約を採用する。
詳細は [README](README.md)、[注文設計](docs/orders/order-aggregate.md)、[在庫設計](docs/inventory.md) にある。

| 分類 | mainの実装・正本 |
|---|---|
| Java | 17（`pom.xml`、CI） |
| Spring Boot | **4.1.1**（`pom.xml` parent） |
| Web | `spring-boot-starter-webmvc`、Jakarta Validation |
| Security | Spring Security、JJWT 0.12.7、BCrypt。署名鍵は `JWT_SECRET` 外部入力 |
| Persistence | Spring Data JPA、MariaDB 12.2、Flyway V1〜V6、Hibernate `ddl-auto=validate` |
| Testing | Surefireのunit/slice/H2補助テスト、Failsafeの実MariaDB/Testcontainers `*IT` |
| API docs | springdoc OpenAPI 3.1.1。Swaggerは明示した `local` profileのみ |
| Infra | Docker Compose、Terraform 1.15.6 / AWS provider 6.68.0、EC2・Caddy想定 |
| CI/CD | GitHub Actionsの5ジョブCI。Deploy workflowは **disabled**。EC2実配備・本番運用は未確認 |

H2はtest scopeであり本番DBではない。アプリは `spring.jpa.open-in-view=false`、SchemaはFlywayが正本。
Spring Boot 4移行の履歴と残余課題は [Epic #8](https://github.com/utl-flaxy/inventory-management-system/issues/8) に記録する。
`README.md`などに旧Boot 3.5等の記載が残る場合、**実コードの版を優先して不整合として報告**し、黙って版を戻さない。

## API・ドメインの守るべき契約

- 登録 `POST /auth/register` は `ROLE_USER` 固定。通常起動でADMINを作成しない。初期ADMINは [offline provisioning](docs/initial-admin-provisioning.md) の運用者専用CLIで作る。
- 商品は `/products`。POSTは初期stockを指定できるが、PUTはname/price/versionのみ。stock変更は注文・cancel・ADMIN手動調整で行い、履歴を残す。
- 注文は `/api/v1/me/orders`（本人）と `/api/v1/admin/orders`（ADMIN）。旧 `/orders/**` は廃止。所有者不一致は404、認証/認可は401/403。
- 注文作成・手動在庫調整の `Idempotency-Key` は必須。同一keyの正常retryで二重減算・二重調整をしない。状態遷移は `PENDING → SHIPPED → COMPLETED`、またはPENDINGからCANCELLED。cancelは在庫を一度だけ戻す。
- `Product`/`Order` のversion管理、User/Order/Productの行ロック、複数商品のID昇順取得、Transaction境界を壊さない。途中失敗では在庫・注文・履歴を一括rollbackする。
- 金額は円の整数 `long/Long`・MariaDB BIGINT。価格・商品名を注文時にsnapshotし、計算overflowは在庫変更前に拒否する。
- EntityをHTTPへ直接返さない。注文・登録・商品・在庫はResponse DTO。商品・注文・在庫履歴のページング契約を維持する。
- エラーは [ProblemDetail / `application/problem+json`](docs/api-errors.md)。単純な `{"code","message"}` の旧形式を新しいAPIへ戻さない。
- JWTとlogin試行制限の現行仕様は [Auth仕様](docs/auth.md) と対象ブランチの実装を照合する。**Issue #97 / PR #98 のlogin改善はこのmainへmerge済み**。本番proxy peerの実測・設定と公開経路の確認は未実施として区別する。

主な正本: [注文認可](docs/security/order-authorization.md)、[状態遷移](docs/orders/state-transitions.md)、[冪等性](docs/orders/idempotency.md)、[CSV](docs/order-csv.md)、[売上](docs/sales.md)。

## テスト・検証

```bash
./mvnw test          # Surefireの高速テスト。Docker不要
./mvnw verify        # Surefire + Failsafe実MariaDB/Testcontainers。PR前必須
git diff --check     # 空白・パッチ形式の確認
terraform fmt -check -recursive terraform
terraform -chdir=terraform init -backend=false -input=false -lockfile=readonly
terraform -chdir=terraform validate
terraform -chdir=terraform test -no-color  # AWS mock。実plan/applyではない
```

Windowsは `mvnw.cmd test` / `mvnw.cmd verify`。**JAVA_HOMEはJava 17** を選び、full verify時はDocker daemonを起動する。
Mavenのunitは主に `*Test`、統合は `*IT`。Controller sliceはBoot4に対応した `@MockitoBean` / `@MockitoSpyBean` を使い、旧 `@MockBean` を新規追加しない。
認可はfilterを無効にしたsliceテストだけで合格とせず、Security実動作・JWT・MariaDBの統合テストで確認する。
合否は件数だけでなく failures/errors/skipped=0、Surefire/Failsafe別結果、GitHub CIの実run、セキュリティ検査を記録する。既存のテスト削除・無根拠skipはしない。
詳細: [テスト手順](docs/testing.md)、[CI品質ゲート](docs/ci-quality-gates.md)。

## DB・構成・インフラ

- `src/main/resources/db/migration` の Flyway **V1〜V6** がschemaのsource of truth。mainへ取り込まれたmigrationを変更・削除しない。新しい変更はVxを追加し、backup・preflight・reviewを行う。無検証の `baseline` / `repair` / `clean` を使わない。
- `application.properties` とprod設定は `spring.jpa.hibernate.ddl-auto=validate`、`spring.flyway.clean-disabled=true`、OSIV=false。DDLの安全なrollbackを仮定しない。
- DB username/password/URLとJWT_SECRETは環境から与える。prodでroot接続やURL内認証情報、弱いJWT_SECRETは拒否する。認証・profileは [設定手順](docs/configuration.md)。
- ComposeはMariaDBをhostへpublishしない。appは `127.0.0.1:8080` のみ。appはnon-root・read-only root filesystemで、`APP_MEMORY_LIMIT` を実host容量確認の上指定する。
- Dockerfileはdigest固定Java17ビルドimageとJRE runtimeのmulti-stage。skipTestsのpackageは**CIのverify後のimage生成工程**であり、検証省略の許可ではない。
- TerraformはVPC/EC2/EIP/限定SSH/80・443/Caddyを**定義**するが、実AWS apply・公開TLS稼働を証明しない。stateはlocalで、remote state/lockは [Issue #75](https://github.com/utl-flaxy/inventory-management-system/issues/75) の残余。
- CDはmain push後のCI成功SHAを対象にした設計だが **workflow自体がdisabled**。PR成功で配備しない。DB container/volumeを停止せずappだけ切替し、readiness失敗時は旧app imageへrollbackする設計。実本番適用は未検証。
- 手動配備、DNS、プロキシ、DB既存データ、backup/restoreの危険操作は [配備手順](docs/deployment.md)、[TLS](docs/tls.md)、[DB運用](docs/db/README.md)、[backup/restore](docs/db/backup-restore.md) に従い別途運用者の承認を得る。

## ログ・セキュリティの注意

- prodは構造化JSONログとserver生成の `X-Request-Id` を使う。秘密のheader/body、username、JWT、password、Idempotency-Key、SQL/constraint messageをapplication eventへ出さない。
- 信頼プロキシ、レート制御、JWT FilterなどのSecurity変更は必ずproxy topology・400/401/403/429・匿名入口・偽装header・再試行・例外時cleanupを区別してテストする。
- OSV/Trivyの数値は**scanner・DB・対象SHA・日付・依存/OSの別**を添える。Java依存のOSV 0件をOS image脆弱性ゼロと表現しない。audit-onlyのTrivyは合格ゲートではない。
- `terraform.tfvars`、`*.tfstate*`、`*.pem`、`.env*`（例外:サンプル）、`.deploy/`、backup bundle、解決済みComposeをコミット・転載しない。
- code reviewでは未検証事項・運用前提・回帰リスクを明記する。実装を超えた「production ready」「監査完了」を断定しない。

## PR作業の流れ

1. Issue、関連PR、対象mainのSHAと最新変更を確認する。先行PRの未merge変更をmain実装として扱わない。
2. 対応する正本docsと周辺コードを読む。互換性・Transaction・ロック・role・secret boundaryを明文化する。
3. 専用branchで最小差分を作り、テストを追加/更新する。不要な整形や領域外のリファクタはしない。
4. 対象テスト→`./mvnw verify`→Terraform mock/CIゲート→GitHub実CIを確認する。利用できない検証はそのまま記録する。
5. PRへIssue・base/head SHA・変更一覧・実測結果・残余リスク・本番未実施・rollback境界を記載する。**自動mergeしない**。

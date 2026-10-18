# 現行構成と監査証跡 — Issue #123 / #128

確認日: **2026-10-18**。基準main: **`186aad8cb41fa7d7227304aeed4a4c3d6436ad69`**（PR #127 merge後）。
[main CI 39123950859](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39123950859)は同SHAでcompleted/success、必須5ジョブすべてSUCCESS。
Issue #128の文書PRの結果はPR本文と、その新HEADのCI artifactで別に記録する。
これはportfolio v1.0の正式完成承認でも実利用者向け本番運用のGOでもない。独立レビューと最終判断を待つ。

## 現在値と根拠

| 項目 | 基準mainで確認した値 | 一次資料・境界 |
|---|---|---|
| Java / Boot / Framework | Java17 / 4.1.1 / 7.0.9 | POMと解決済みSBOM。旧Boot3.5を現行構成へ戻さない |
| Security / Persistence | Security7.1.1、Hibernate7.4.5.Final、Flyway12.4.0、MariaDB JDBC3.5.10 | dependency-audit artifact。MariaDB12.2の上流互換性WARNは残る |
| Web / JSON | Tomcat11.0.26、Jackson3.1.7、JJWT用Jackson2.21.7 | 同SBOM。異なるJackson majorの存在を単純な重複削除の根拠にしない |
| Testcontainers / API docs | 2.0.5 / springdoc3.1.1 | POM / SBOM。Swaggerはlocalのみ、prodは404 |
| Java全回帰 | **Surefire1,314 + Failsafe862 = 2,176**、F/E/S=0 | main CIのfull verify、実MariaDB/Testcontainers。既存2,167 + JWT否定系9 |
| Python / Terraform | **29 / mock27成功** | secret-scan / terraform job。実AWS provider APIへの適用ではない |
| Secret | Gitleaks findings0、例外0、未解決0、正常/異常self-test成功 | current tracked tree。Git履歴全体・未追跡物・secret storeの検査ではない |
| Java静的解析 | SpotBugs SECURITY、main118 classes、違反0、正常/異常self-test成功 | 全カテゴリ・test code・設計欠陥の網羅検査ではない |
| Maven依存監査 | **OSV169 components / 0 advisory** | production/test等の解決済みSBOM。Maven plugin依存やOSは別 |
| app runtime image監査 | **Trivy OS49件（MEDIUM27 / LOW22）、library0、C/H0** | finding instances。OSV0と混同しない。audit-only、未知脆弱性や全到達性を保証しない |
| Docker | clean archive build、JRE17、UID/GID10001:10001、read-only rootfs、/readyz、実API、V1〜V6、shutdown、設定復帰PASS | linux/amd64の使い捨てfixture。本番容量・公的TLS・異版rollbackは未検証 |

[POM](../pom.xml)、[workflow](../.github/workflows/ci.yml)、[Dockerfile](../Dockerfile)、[Compose](../docker-compose.yml)が設定の正本。
CI artifactは`dependency-audit-186aad8...`、`docker-validation-186aad8...`等。保存期間は30日なので、長期保持する場合はmaintainerが期限内に値を除去した証跡を取得する。
DBはComposeが`mariadb:12.2`、Java ITも主に`12.2.2`、一部`12.2`の可変tag。
例: [JwtFilterRegistrationIT](../src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java) / [AuthDatabaseFailureIT](../src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java)。
[Dockerデモ](../scripts/docker/verify_image.py)のDB_IMAGEは12.2.2の完全digestへ固定する。全ITがdigest固定とは説明せず、同じbit再現性とは扱わない。

## 保護・Deployの実設定（read-only API確認）

- main: required checksは`test` / `terraform` / `secret-scan` / `java-static-analysis` / `dependency-audit`の5件。
- strict=true、enforce_admins=true、linear_history=true、force push/delete禁止。
- required approving reviews=0。独立レビューは人手の運用であり、GitHubの必須承認1件が設定済みとは記載しない。
- Deploy workflow: **disabled_manually**。定義上のworkflow_run条件やDEPLOY_SHAは有効化されたCDの実績ではない。
- 外部Action参照は40桁SHA固定、contents:read、persist-credentials:false。PRからproduction secretやAWSへ進むジョブは追加しない。
- 本PRは保護設定・Deploy状態を変更しない。再確認には`gh api repos/utl-flaxy/inventory-management-system/branches/main/protection`と`actions/workflows/deploy.yml`の非秘密metadataを用いる。

## 実証・定義・未実施を分ける

| 領域 | 実証済み | 未保証・運用前必須 |
|---|---|---|
| app / DB | 実MariaDBのmigration V1〜V6、認証/認可、注文/在庫/取消/冪等性、backup/restore IT | 実本番DBの復元、RPO/RTO、schema変更時の異版互換 |
| Docker | 非root/read-only、明示メモリ、app-only設定故障復帰、DB container ID/起動時刻・業務data不変 | EC2容量・ピーク負荷・旧バイナリへのrollback |
| Caddy / TLS | mock host、network=none/local CAのCaddyProxyIT、prod forwarded HTTPS処理のIT | 実DNS/EIP・公的CA証明書・更新・実hostのproxy peer。デモはloopback HTTP |
| Terraform | fmt/init backend=false/validate/mock27。network/EC2/Caddy定義 | 実AWSへのapply、実account/state/費用/host、remote state/lock（#75） |
| 認証ライフサイクル | 署名/issuer/exp、DBユーザー・role照会、未署名・改ざん拒否、JWT契約の文書化 | 個別失効/refresh未実装（#79）。旧JWTが有効な間のusername再割当て禁止等の運用制約 |

現行構成は**AWS未配備として扱う**。本作業ではAWSへアクセスして実accountの状態を調査しない。
過去のEC2スクリーンショット、Terraform定義、CI成功を現行本番配備の証拠へ読み替えない。
現在のEC2 RAM・SLA・TLS・実secret・本番DB・復旧は未確認で、実利用者向け本番運用はGOとしない。

### rollbackの限定

基準mainのDocker artifactは現在imageとprevious_image_idがともに
`sha256:2eeb8c5db8aff07a2fe5b6041baa03da2b56bab19f1827c68d89d5e37e1b744f`。
`--baseline-image`未指定で、container loopbackの接続不能DB設定を与えたappから、**同一image IDの直前設定へ復帰**した。
DBを再作成せず、注文/在庫とADMIN状態が保持されることを検証した範囲である。
これは`deploy.sh`の全経路を実EC2で動かした結果でも、異なる旧バイナリ・新schema・旧鍵との互換性の保証でもない。
app rollbackでDBのDDL/dataが戻るとは仮定せず、[配備手順](deployment.md)と[DB復元](db/backup-restore.md)で別に判断する。
旧基準main5f0fae4 / CI39109302086の同一image設定復帰では、image/previousとも
`sha256:0afd208108ecc38c02e5e34e84fd102784f0e75c8bbc8155e85c03cb5f3512ba`だった。別buildの当時の証拠として保存する。

## 監査史と最新の是正

古い数値を削除して現在の改善を作り直さず、確認したSHAごとに保持する。

| 歴史的記録 | 当時の値・現在との関係 |
|---|---|
| main `5f0fae4ebd89754e23538cb09f97a1ff037255ed` / [CI39109302086](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39109302086) | PR #124 merge後の初回文書基準。2,176件・OS49件などは当時のsnapshotとして保存し、現行は上記186aad8の実CIで再確認 |
| [2026-10-02の46件台帳](audit/2026-10-02-remediation-traceability.md) / #71 | 2026-10-11 / main1278d456...の20 fixed / 21 deferred / 1 accepted / 4 unverifiedは当時のsnapshot。現行の46件再集計とは主張しない |
| [依存元66件の台帳](audit/2026-10-12-dependency-advisories.md) | 137 components、C8/H22/M28/L8。その後のTomcat/BOM/Jackson/Boot4の是正史はそのまま保持。現在のOSV0件とは別基準 |
| [Issue76の実測](audits/issue-76-validation.json) | Boot3.5 / 当時のOSV8件、旧JDK/rootへのローカル対照・Boot4 PoC等はそのSHAの記録。現行mainの全結果ではない |
| 2026-10-16 Claude監査 / 2026-10-18リリース監査 | #123に基準と残作業を記録。37件の監査と旧46件台帳を合算せず、過去の「未マージ」「未保護」を現在値へ流用しない |
| [再構成前README全文](https://github.com/utl-flaxy/inventory-management-system/blob/5f0fae4ebd89754e23538cb09f97a1ff037255ed/README.md) | 旧図・画像・当時のAPI例はimmutableなGit履歴に保持。現行の入口は短縮したREADME |

| 最新mainへ入った是正 | 証拠 |
|---|---|
| AUD-01 Secret例外4→0 | [PR #105](https://github.com/utl-flaxy/inventory-management-system/pull/105)、例外の期限延長なし |
| AUD-05 商品名Unicode長さ255/256 | [PR #107](https://github.com/utl-flaxy/inventory-management-system/pull/107)、実DBのPOST/PUT境界 |
| AUD-06 異なる20顧客の在庫競合 | [PR #109](https://github.com/utl-flaxy/inventory-management-system/pull/109)、10成功/10不足、過去の隔離ロック除去対照 |
| AUD-07/08 登録負荷制限・注文1〜100明細 | [PR #111](https://github.com/utl-flaxy/inventory-management-system/pull/111)、[PR #113](https://github.com/utl-flaxy/inventory-management-system/pull/113) |
| AUD-10/11 障害分類・DB予算・shutdown | [PR #115](https://github.com/utl-flaxy/inventory-management-system/pull/115)、[PR #117](https://github.com/utl-flaxy/inventory-management-system/pull/117) |
| JWT経路・ライフサイクル文書・Windows計測 | [PR #118](https://github.com/utl-flaxy/inventory-management-system/pull/118)、[PR #120](https://github.com/utl-flaxy/inventory-management-system/pull/120)、[PR #122](https://github.com/utl-flaxy/inventory-management-system/pull/122) |
| AUD-23 JWT否定系9件追加 | [PR #124](https://github.com/utl-flaxy/inventory-management-system/pull/124)、main5f0fae4...、今回の基準CIで全2,176成功 |
| README・資料同期・隔離Dockerデモ9段階 | [PR #125](https://github.com/utl-flaxy/inventory-management-system/pull/125)はMERGED、Issue #123はCLOSED。ローカル証跡は当時の入力commitを保持し、基準main CIでも9段階を実行 |
| AUD37 / 旧46 / DoD16の最終監査記録 | [PR #127](https://github.com/utl-flaxy/inventory-management-system/pull/127)はMERGED。[監査報告](audit/portfolio-v1-final-acceptance-2026-10-18.md)のCONDITIONAL GOは当時の判定提案。正式GO承認ではない |

この表は対象の是正へのリンクであり、監査全件・本番適用の完了宣言ではない。
Epic #8の旧1836/OS17/protected=false欄は履歴として保持し、同Epicの2026-10-18同期欄とこの文書を現在の参照先にする。
Draft PR #103は過去の統合検証用で、マージ対象・現行mainの証拠へ含めない。

## 残余と次の判断

1. [Issue #128](https://github.com/utl-flaxy/inventory-management-system/issues/128)でF01/F02の[Transaction・ロック設計](design/transactions-and-locking.md)、[設計判断](design/decisions.md)、READMEのsource/testリンクを提案する。監査時点の達成12/一部3/未達1を遡及変更せず、文書PRの独立レビュー後に正式完成を再判定する。
2. JWT失効（#79、v1.1候補）、OS49 findings、Flyway/MariaDB互換性WARN、過去の証明書時刻問題を追跡する。0件のスキャンは将来の安全保証ではない。
3. 本番運用前には容量・負荷・TLS・信頼proxy・鍵/権限・backup/restore・異版rollback・監視/復旧目標を実環境で別途判断。今回それらを実行しない。
4. Windows DatabaseTimeoutITの初回観測CLI3失敗は根因未確定。対象IT再実行9件の成功と区別し、[監査§10](audit/portfolio-v1-final-acceptance-2026-10-18.md#ローカル補助検証)の失敗を保持する。skip・pool/timeout緩和は行わない。

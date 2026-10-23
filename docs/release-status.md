# 応募版の構成と監査証跡 — 2026-10-23 / Issue #153

**PORTFOLIO GOは今回の提案。独立レビューによる正式承認を待つ。PRODUCTION NO GOは継続。**
10月18日の[独立受入GO](https://github.com/utl-flaxy/inventory-management-system/issues/130#issuecomment-6251677031)は当時の記録として保存し、拡張後の応募版の提案と区別する。

## 今回照合したmain snapshot

main **`1632db72ebedfdc3cf316c442f199022e253133d`** / [push CI39937244198](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39937244198)。
event=push・head一致・completed/success・必須5/5 SUCCESSをログ/artifactで確認した。
未来のmainの成功とは主張せず、[main pushのlive一覧](https://github.com/utl-flaxy/inventory-management-system/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)と[main HEAD](https://github.com/utl-flaxy/inventory-management-system/commits/main/)を再照合する。PR HEADの結果はPR本文の別run。

|検証|このmain/runの実測と限界|
|---|---|
|Java17 / Python|1,384＋986＝**2,370件、F/E/S 0/0/0** / **68件成功**|
|Terraform|host27＋bootstrap14＋synthetic8＝**49件成功**、mutation3件をassertionで拒否。backend=false、AWS受入0件|
|Secret / static|397 files、例外0/未解決0 / SpotBugs SECURITY151 classes・違反0。正常/異常self-test成功|
|依存 / image|OSV169 components・advisory0 / Trivy **OS32（MEDIUM28/LOW4）、library0**。監査成功は全脆弱性0ではない|
|Docker / DB|clean archive、JRE17、10001:10001、read-only、業務9段階＋B1/B2/B3、Flyway V1〜V9、shutdown/同image設定故障復帰成功|

出典、DoD16、残存Issueと応募用判断は[受入追補](audit/portfolio-application-acceptance-2026-10-23.md)。
実装と常設ITを辿る入口は[3シナリオ](portfolio-walkthrough.md)、[Dockerデモ](demo.md)、[テスト手順](testing.md)。

## 本番への未達条件

AWS実配備・公的TLS/実DNS・実secret/信頼proxy peer・本番DB復旧、異なる旧binaryへのrollback、実host負荷/容量/監視/SLA/RPO/RTOは未検証。
#75 OPEN / TF-C BLOCKED（C01/C02/C05、B01〜B14未承認）、#145 Windows根因未確定、#134 jti保留、Flyway WARNとOS32を保持する。
#79はB1〜B3適用後CLOSEDであり、全端末失効とjti単体失効を同じ未実装課題にしない。
Deploy disabled、PR #103 Draft/OPEN・対象外。既存state/DB/volume/実secretやAWSをこの作業で参照・操作していない。

## 以前の固定snapshotと是正記録

以下は元のrelease-status全文。2,176件/Python29/Terraform27/OS49、古いIssue状態や提案は当時の値として保持し、上のsnapshotへ読み替えない。

<details>
<summary>2026-10-18以前のrelease-status全文（履歴原文）</summary>

# 構成と監査証跡 — snapshot / live（Issue #132）

**portfolio v1.0の正式GOは独立レビュー・最終承認後。実利用者向け本番運用はNO GO。**
本書は監査時点の証拠と、現在の状態を確認する入口を分ける。後続commitの存在だけで過去の監査記録が未達へ戻る契約にはしない。

## 現在のmain CI（live）

[main pushに絞ったSpring Boot CI](https://github.com/utl-flaxy/inventory-management-system/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)の先頭runを開く。
[main HEAD](https://github.com/utl-flaxy/inventory-management-system/commits/main/)とのSHA一致と、`test` / `terraform` / `secret-scan` / `java-static-analysis` / `dependency-audit`の5ジョブがすべてcompleted/successであることを確認する。
未完了・失敗・対応するrunがない場合、下のsnapshot成功を現在の成功へ読み替えない。
PRの新HEADはPR本文のrun/headで別に検証する。main CIとPR CIを代用しない。

## 監査基準snapshot（2026-10-18 UTC確認）

基準はmain **`0614d7d306fed5a3ec43ba7a7502b8bf7e65f3ed`**（PR #131 squash後）、
[main push CI39136004541](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39136004541)。GitHub APIのmain SHA、run head、event=push、completed/success、全5ジョブを照合した。
このSHA/runは固定し、mainが更新された場合の現在確認はlive入口で行う。新しい監査が必要になった場合は別の時点として追記し、旧記録を書き換えない。

|対象|このsnapshotで確認した値|保証範囲|
|---|---|---|
|Java17 full verify|Surefire1,314 + Failsafe862 = **2,176**、F/E/S=0、BUILD SUCCESS|MariaDB/Testcontainersを含む有限の回帰。持続負荷/SLAの保証ではない|
|Python / Terraform|**29 / mock27成功**|品質gate方針、構文/validate/mock。実AWS applyなし|
|Gitleaks / SpotBugs|tracked332、例外0・findings0・未解決0 / main118 classes、SECURITY違反0。正常/異常self-test成功|当該SHAのtree/対象bytecode。全git履歴・全SASTではない|
|OSV / Trivy|**169 components / 0 advisory**、imageは**OS49（MEDIUM27/LOW22）、library0、C/H0**|異なる監査対象。audit-onlyの処理成功を脆弱性ゼロと扱わない|
|Docker|clean archive、JRE17、UID/GID10001:10001、read-only、V1〜V6、readiness/API、9段階デモ、shutdown/設定復帰PASS|使い捨てlinux/amd64。公的TLS・実host容量・異版rollbackは未実証|
|保護 / Deploy|strictな必須5check、enforce_admins/linear_history有効、force push/delete禁止、必須承認数0 / disabled_manually|確認時点のread-only API結果。人手の独立レビューとGitHubの必須承認を区別|

Docker image/previous_image_idはともに`sha256:540afb28d769dfb0f4dbb416da925ccb177e235f6600293bd3c6872a6e235105`。
DB container ID/起動時刻・注文/在庫不変を確認した**同一image IDの設定故障復帰**であり、異なる旧binary・新schema・旧鍵との互換rollbackではない。
CI artifactは`dependency-audit-0614d7d...`、`docker-validation-0614d7d...`等。保存30日のため長期保持はmaintainerが期限内に値を除去した証跡を確保する。

## D01/D12の是正記録（Issue #132）

|項目|PR #131時点の残差|今回の是正・受入根拠|
|---|---|---|
|D01|READMEの基準が186aad8/39123950859のままで、最新mainの確認先と区別できていない|[READMEの検証結果とCI](../README.md#検証結果とci)を0614d7d/39136004541の固定snapshotへ同期し、現在確認用のmain push filterを併記。[testing](testing.md#品質ゲートの参照方針snapshot--live)も同じ契約にする。main更新だけでsnapshotのSHAを追い続ける必要はない|
|D12|Epic上段がPR #129 OPEN/未反映と表示|[Epic #8](https://github.com/utl-flaxy/inventory-management-system/issues/8)の上段のみ、APIで確認したPR #129/#131のMERGEDと下表のmerge SHAへ同期（API updatedAt=`2026-10-18T14:50:12Z`）。旧歴史本文・計測値は保持。更新前後のバックアップと差分を保存し、保持検証の根拠はこのPR本文へ記録する|

|統合済みの文書PR|APIで確認した状態と固定merge SHA|
|---|---|
|[PR #129](https://github.com/utl-flaxy/inventory-management-system/pull/129)|MERGED、2026-10-18T12:22:20Z、`f15480170d4b58b34d54198dcc11a9a399f1aa36`。設計文書2本とREADMEの5組source/testリンクは基準mainに含まれる|
|[PR #131](https://github.com/utl-flaxy/inventory-management-system/pull/131)|MERGED、2026-10-18T14:26:34Z、`0614d7d306fed5a3ec43ba7a7502b8bf7e65f3ed`。[受入再判定](audit/portfolio-v1-acceptance-recheck-2026-10-18.md)の14/2/0はその監査時点の記録で、正式GO承認ではない|

D01/D12の文書上の残差は上のsnapshot/live契約とEpic同期で解消する。新HEADの5ジョブ成功と是正diffをPR本文で示し、受入判断は独立レビューへ委ねる。
[最終監査](audit/portfolio-v1-final-acceptance-2026-10-18.md)の12/3/1と、再判定の14/2/0を遡及変更しない。この追記で正式GOや本番GOを宣言しない。

## 残余と履歴の扱い

- Windows DatabaseTimeoutITの過去の初回DB観測CLI3失敗は**根因未確定**。[初回記録](audit/portfolio-v1-final-acceptance-2026-10-18.md#ローカル補助検証)と[後続の成功/限界](audit/portfolio-v1-acceptance-recheck-2026-10-18.md#7-windowsの失敗履歴と今回の補助検証)を別に保持し、成功で初回を抹消しない。skip/pool/timeout緩和なし。
- Trivy OS49、Flyway/MariaDB12.2互換性WARN、JWT個別失効未実装（#79）、異版rollback未検証、過去の証明書時刻事象を保持する。OSV0とimage脆弱性0は別。
- AWS未配備として扱い、実secret/本番DB/既存volume、実DNS・公的TLS、信頼proxy peer、容量/負荷/SLA、本番backup/restore・復旧は未検証。Deployは無効、PRODUCTION NO GO。
- [PR #103](https://github.com/utl-flaxy/inventory-management-system/pull/103)は統合検証用Draft、マージ対象・現在品質証拠へ含めない。Issue #126/#130は正式受入前に閉じない。
- 下の旧release-statusはIssue #123/#128時点の全文。186aad8/39123950859、5f0fae4/39109302086の数値・image ID・旧提案は**当時の記録**であり、現在値へ流用しない。旧46件/依存66件/Issue76の原台帳・JSON、旧監査報告書は改変しない。

<details>
<summary>履歴原文: Issue #123/#128時点のrelease-status（全文保存）</summary>

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

</details>

</details>

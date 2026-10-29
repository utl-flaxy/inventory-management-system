# Portfolio v1.0 最終仕上げ・受入候補 — 2026-10-29

**提案: BLOCKED（P2）。新規Trivy HIGH 1件の対応・リスク判断を独立レビューへ提出する。正式承認・merge・tag作成は未実施。実利用者向け本番運用はPRODUCTION NO GO。**
応募用作品として「実装→設計理由→常設テスト→安全な再現→未保証事項」を説明できることを評価する。本番接続が必要な条件を応募版へ追加しない。

## 今回の基準snapshotと現在確認先

main **`1a647a393dac147825293e0ccba0fd84f30bc5fa`**（PR #167/#168/#169 squash後）。
[main push CI40328908744](https://github.com/utl-flaxy/inventory-management-system/actions/runs/40328908744)は2026-10-26 UTCの実行で、10月29日にログ・artifactを再取得した。
event=push、head一致、completed/success、test / terraform / secret-scan / java-static-analysis / dependency-auditの5/5 SUCCESS。
[現在のmain push一覧](https://github.com/utl-flaxy/inventory-management-system/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)と[main HEAD](https://github.com/utl-flaxy/inventory-management-system/commits/main/)は別に照合する。
本PR HEADの結果はPR本文の別runへ記録する。下表を将来のmainや実配備の成功へ流用しない。

|対象|基準main CIの実測・出典と限界|
|---|---|
|Java17|Surefire1,469＋Failsafe1,045＝**2,514件、F/E/S=0/0/0、BUILD SUCCESS**。test job、MariaDB/Testcontainersを含む有限の回帰|
|Python / Terraform|**68件 / host27＋bootstrap14＋synthetic8＝49成功、mutation拒否3**。secret-scan / terraform job。backend=false・AWS受入0件|
|Gitleaks / SpotBugs|**420 tracked files、例外0/未解決0 / main160 classes、SECURITY違反0**。各artifactと実正常/異常self-test。全履歴・全SASTの保証ではない|
|SBOM/OSV|**169 components / advisory0**、抑制なし、scanner exit0。Maven plugin・OS・未知脆弱性は別|
|Trivy|**OS32（MEDIUM28/LOW4）、library0**、scanner exit0。runtime imageだけのaudit-only。脆弱性ゼロではない|
|Docker / DB|clean archive、Java17 JRE、10001:10001、read-only、業務9段階＋B1/B2/B3、Flyway V1〜V9、shutdown・同image設定故障復帰PASS。Flyway WARN3件保持|
|配備/復旧/診断|配備順序15、[BackupRestoreIT](../src/test/java/com/example/demo/repository/BackupRestoreIT.java)、[診断回帰](testing-container-diagnostics.md)成功。実配備・本番restore・Windows根因解消ではない|
|解決済み依存|Boot4.1.1 / Framework7.0.9 / Security7.1.1 / Tomcat core/el/websocket11.0.27 / Hibernate7.4.5.Final / Flyway12.4.0 / MariaDB JDBC3.5.10。dependency-auditのbom.json|

Docker artifactのsource_revision/revision_labelは上記mainに一致。clean_archive_build=trueとworkspaceのsource_dirty=trueを別々に保持する。
current/previous image IDは同じで、rollbackは設定故障復帰。異なる旧binary、旧鍵、過去DBとの互換性を保証しない。
artifactはrunのActions画面から取得する（保存30日）。長期保管時は値を除去した証跡を保ち、raw JWT・env・DB認証情報を転載しない。

<a id="portfolio-v1-final-acceptance"></a>

## Portfolio v1.0の最終受入候補（P1〜P5）

下のPASSは応募用の判定案であり、独立承認済みという意味ではない。本PRの文書表示・リンク・新HEAD CIはPR本文で提出する。

|ID / 判定案|根拠ファイル・テストと実行結果|残余リスク|v1.0完成への影響|本番運用への影響|
|---|---|---|---|---|
|P1 **PASS** 主要業務/API/認可|[OrderService](../src/main/java/com/example/demo/service/OrderService.java)、[SecurityConfig](../src/main/java/com/example/demo/security/SecurityConfig.java)。[20顧客競合](../src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java#L242)、[HTTP冪等性](../src/test/java/com/example/demo/controller/IdempotentOrderIT.java)、[認可matrix](../src/test/java/com/example/demo/security/AuthorizationMatrixIT.java)、B1/B2/B3の実MariaDB回帰が基準CIで成功|jti/refresh/reset/role変更API未実装、失効前に認証済み業務は完遂し得る|未実装範囲を明示し、応募版の機能を説明可能|実artifact/認証方式/外部設定は#166で別受入|
|P2 **BLOCKED** 品質|[ci.yml](../.github/workflows/ci.yml)、[品質方針](ci-quality-gates.md)。基準mainと下記PRのJava/Python/TF/Secret/SpotBugs/OSV、CI5/5は成功。PRのTrivyは**OS33（HIGH1/MEDIUM28/LOW4）**|新規CVE-2026-111497の修正版未適用、到達性未確定。Flyway WARN、#145不安定性・診断不能も残る|新規HIGHの別対応・リスク判断前に最終受入を通過させない|audit-onlyのCI成功は脆弱性受容ではなく、PRODUCTION NO GO|
|P3 **PASS** 安全な再現|[demo](demo.md)、[testing](testing.md#実行前提と安全な確認)、[configuration](configuration.md)。Git archiveの使い捨てDocker9段階＋B1/B2/B3が基準CIでPASS、Windows/Linuxの再現コマンドを実装と照合|network/host資源に依存、強制終了時cleanup未完の可能性。公的TLSは別|既存volumeや実secretを使わず追跡可能|本番接続や既存DB復元の許可にはならない|
|P4 **PASS** 説明資料|[README](../README.md)、[3シナリオ](portfolio-walkthrough.md)、[Transaction](design/transactions-and-locking.md)、[設計判断](design/decisions.md)。Security前段/Service境界とsource/test実在を照合。表示・リンク検証はPR証拠へ|90秒は導線の目標で、第三者ユーザビリティ測定ではない|今回の小さな導線・図修正を独立レビューへ提出|図は実AWS稼働の証明ではない|
|P5 **PASS** 証拠/残余|本書、[旧DoD16](audit/portfolio-application-acceptance-2026-10-23.md#3-dod16との照合と独立受入の履歴)、[Epic #8](https://github.com/utl-flaxy/inventory-management-system/issues/8)、[#166](https://github.com/utl-flaxy/inventory-management-system/issues/166)。snapshot/live/PRを分離し旧監査原文を保存|本番担当・承認・期限未合意、実運用証拠なし|P2の未達を開示。正式GO・tagは未実施|#166はBLOCKED、PRODUCTION NO GOを維持|

過去のDoD16の12/3/1、14/2/0、独立受入16/16はそれぞれのSHA/日付の記録として保存する。
今回のP1〜P5へ数え直して過去の承認を上書きしない。Draft PR #103はマージ対象・現行品質証拠へ含めない。

## PR検査で確認した差分 — 2026-10-29

初回の[PR #170 CI40855988546](https://github.com/utl-flaxy/inventory-management-system/actions/runs/40855988546)、head `f19eb86992e4e8e859ee31550a51cfa9831a0317`は5/5・workflow SUCCESS。
Java1,469＋1,045＝2,514、F/E/S=0、Python68、TF49＋mutation拒否3、配備順序15、Secret未解決0、SECURITY違反0、OSV169/advisory0、Docker業務9段階＋B1/B2/B3を実ログ/artifactで確認した。
ただしTrivyは**OS33（HIGH1/MEDIUM28/LOW4）、library0**。基準main CIのOS32は当時のsnapshotとして保持し、今回の結果へ書き換えない。文書追補後の最終HEAD/runはPR本文で別に照合する。

|新規検出|実測と判断|
|---|---|
|CVE-2026-111497 / libbrotli1|installed `1.1.0-2build2`、fixed version `1.1.0-2ubuntu0.1`、Trivy severity source=ubuntu、**HIGH**。status=fixedは修正版が存在する意味で、このimageへの適用済みを意味しない|
|一次資料（10月29日確認）|[Ubuntu CVE](https://ubuntu.com/security/CVE-2026-111497) / [USN-8956-1](https://ubuntu.com/security/notices/USN-8956-1)。細工したBrotli圧縮streamをdecoderが処理する条件でメモリ破壊・DoS・コード実行の可能性。Nobleの上記修正版を公式表と照合|
|到達性と限界|POM・production Java/config・ComposeにBrotliを直接呼ぶ設定は見つからないが、runtimeのnative packageは存在する。間接利用・外部設定・実本番artifactを未検証のため、非到達/安全とは断定しない。攻撃入力を送信していない|
|変更と別対応案|本PRはMarkdownのみで、image/依存更新やsuppressionをしない。独立Issue候補として公式JRE17の修正済みdigestまたは限定package更新を評価し、全finding比較・非root/read-only・full verify・Docker/TLS/rollback・CIで別検証する。今回はそのIssue/修正PRを無断作成しない|

修正版がある新規HIGHの扱いを過去のMEDIUM/LOW残余へ自動的に含めない。P2をBLOCKEDとし、依存是正または根拠あるリスク判断を独立レビューへ委ねる。CIのaudit-only契約は変更しない。

Windowsのfull verify（上記head、Java17.0.18、Docker29.8.1、Testcontainers2.0.5）は**2,514件、F/E/S=0、BUILD SUCCESS、23分24秒**。
OrderItemLimitIT / ProductDeletionIT / InitialAdminLifecycleITの起動中には、各1件のWait strategy failedとcertificate not yet validのログを観測した（3組）。
最終的な各suiteは成功したが、これを初回起動障害の解消としない。このログからJavaのCertificateNotYetValidExceptionや時刻差の根因を確定せず、過去履歴と#145 OPENを維持する。診断回帰自身の故障注入はこの3組とは分けて集計した。
DatabaseTimeoutITは9件成功、DB waitは5,000/5,006/5,000ms、HTTP409。DB内部予算とhost観測時間の既存区別を変えていない。

READMEはGitHub上のPC表示・3つのMermaid SVG・受入anchorへの遷移を確認した。新しい縦図は幅248pxのSVGだが、browserのmobile viewport指定が反映されず、実mobile表示は**NOT VERIFIED**。PC確認をmobile実測と表現しない。

## 参考構成図・索引と実ソースの照合

10月29日に公開の[GitDiagram](https://gitdiagram.com/utl-flaxy/inventory-management-system)と[Gitingest](https://gitingest.com/utl-flaxy/inventory-management-system)を確認した。
GitDiagramは20 components / 24 connectionsの高水準図で、Java excerpts未提供の注意書きがあり、対象commitの固定表示はない。
Gitingestは上記main SHA、395 files、50kB上限。testing.md等の大きいfileは除外され、全tracked treeの検査ではない。
以下は図の真偽を図だけで決めず、一次ソースで補った対応である。外部サービスはREADME表示/実行の必須依存にしない。

|領域|実ソースの経路・注意点|
|---|---|
|商品/在庫|[ProductController](../src/main/java/com/example/demo/controller/ProductController.java)→ProductService、[InventoryController](../src/main/java/com/example/demo/controller/InventoryController.java)→InventoryService。ADMINメソッド認可と在庫履歴を分離|
|顧客注文/取消|[SelfOrderController](../src/main/java/com/example/demo/controller/SelfOrderController.java)→OrderService。所有者query、Tx、商品ロック、key/指紋とDB UNIQUE、注文時価格snapshotを確認|
|ADMIN出荷/完了/売上/CSV|[AdminOrderController](../src/main/java/com/example/demo/controller/AdminOrderController.java)→[OrderService.getSales](../src/main/java/com/example/demo/service/OrderService.java#L282) / OrderCsvExportService。図のSalesResponseリンクはDTOであり、集計Serviceではない|
|認証/失効|SecurityFilterChain→JWT検証/DB世代照合→Controller。AuthRevocationControllerはlogout/password、AdminTokenRevocationControllerは再認証付き失効。図のsessions表現をHTTP session保持と解釈しない|
|Service/Repository|Tx境界はServiceの対象メソッド。JPA Repositoryのロックqueryと認証用JDBC projectionは同じものではない|
|MariaDB/Flyway|永続化とUNIQUE/CHECK、起動時V1〜V9。図だけでは分からないmigration・監査とrollback境界を[設計文書](design/transactions-and-locking.md)で補足|
|Docker/CI/信頼境界|[READMEの図](../README.md#構成と信頼境界)とdemo、TLS/CI文書を正本とする。Internet/Caddy/app/DBの本番想定と隔離loopback HTTPを区別|

## 残存Issueと本番受入の停止条件

- [#145](https://github.com/utl-flaxy/inventory-management-system/issues/145): PR #167/#168/#169の安全な診断はmain採用済み。Windows時計差/CertificateNotYetValid/過去Wait・Caddy・DB CLIの根因は未確定。初回DB観測CLI3失敗も保存し、後続成功で消さない。
- [#75](https://github.com/utl-flaxy/inventory-management-system/issues/75): backend/bootstrap/syntheticの定義・mockと実運用は別。B01〜B14未承認、TF-C BLOCKED。実AWS/既存state操作をしていない。
- [#134](https://github.com/utl-flaxy/inventory-management-system/issues/134): 個別jti失効は保留。B1〜B3の全端末失効と混同しない。refresh/reset/role変更APIも未実装。
- [#86](https://github.com/utl-flaxy/inventory-management-system/issues/86) / [#87](https://github.com/utl-flaxy/inventory-management-system/issues/87) / [#89](https://github.com/utl-flaxy/inventory-management-system/issues/89): ソース是正と本番適用/外部設定の未確認を分離し、#166へ追跡。独断で閉じない。
- Trivy OS32は基準main SHA/runのsnapshot。今回PRのOS33・新規HIGH1を別に記録し、受入を保留する。Flyway WARN、本番DB restore・schema drift、旧binary/鍵/過去DBへのrollback、実hostのRAM/CPU/負荷/容量/監視/SLA/RPO/RTOは未検証。
- 実AWS配備、公的TLS/実DNS、実artifact/digest・外部profile・secret/信頼proxy peer、運用担当/承認者/期限は未確認または未合意。[#166](https://github.com/utl-flaxy/inventory-management-system/issues/166)は**BLOCKED BY OWNER / APPROVAL**。

これらを開示した応募用作品の完成候補と、実利用者向け本番承認を分離する。Deployはdisabled_manually、PRODUCTION NO GO。
本作業で実AWS/EC2/SSH、既存DB/volume/state/実secretを参照・操作せず、Epic/Issue本文と過去の監査資料も変更しない。

## 以前の受入資料（原文保存）

以下の「今回」「main」「現行」と数値は当時のsnapshotであり、上記の現在照合へ読み替えない。

<details>
<summary>2026-10-23以前のrelease-status全文（履歴原文）</summary>

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

</details>

# 応募用ポートフォリオ受入追補 — 2026-10-23 / Issue #153

## 判定案と対象

**提案: PORTFOLIO GO（Java / Spring Bootバックエンド職への応募用成果物）。正式承認は今回も独立レビュー待ち。PRODUCTION NO GOは継続する。**
新機能やテスト件数の増加を完成条件にせず、実装の難所、選択理由、対応するテスト、再現方法、保証しないことを第三者が追跡できることを条件にした。
本書はCodexによる正式GO宣言や、実利用者を受け入れる許可ではない。

基準main **`1632db72ebedfdc3cf316c442f199022e253133d`**（PR #154 squash後）。着手時にfetchして一致を確認。
[Issue #153の最新指示](https://github.com/utl-flaxy/inventory-management-system/issues/153#issuecomment-6362552361)、mainの実コード/テスト、GitHubのIssue/PRと当該push CIを一次資料とした。
Java/POM/migration/runtime/Docker/Terraform/CI/Deployは変更しない。37件/旧46件すべての攻撃プローブを再実施する監査でもない。

## 1. 最新確認snapshotとliveの分離

[main push run **39937244198**](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39937244198)を独立取得し、
`event=push`、headが上記main SHA、`completed/success`、必須5ジョブすべてSUCCESSを照合した。
前の[PR #154 CI](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39934654121)をmain CIの代用にしていない。
下表は**このmain/runに固定した測定**。更新後の状態は[main push一覧](https://github.com/utl-flaxy/inventory-management-system/actions/workflows/ci.yml?query=branch%3Amain+event%3Apush)と[main HEAD](https://github.com/utl-flaxy/inventory-management-system/commits/main/)を照合する。
本PRの新HEADはPR本文の別run・artifactで検証する。未完了/失敗を過去の成功で置換しない。

|領域|main CIの実測|証拠と保証の限界|
|---|---|---|
|Java17|Surefire **1,384**＋Failsafe **986**＝**2,370**、F/E/S **0/0/0**、BUILD SUCCESS|test jobのfull verify。MariaDB/Testcontainersを含む有限の回帰で、全負荷/SLAの保証ではない|
|Python|**68件成功**|secret-scan job。品質方針、TF公開拒否ゲート、実CLI終了コードやsynthetic否定系契約|
|Terraform1.15.6|host27＋bootstrap14＋synthetic8＝**49**、mutation **3件を期待したassertionで拒否**|terraform job。公開sourceの隔離export、backend=false、mock/内部plan-only。AWSのIAM/lock/復旧受入0件|
|Gitleaks|397 tracked files、例外0・finding0・未解決0。実正常/異常self-test成功|secret-scan artifact。Git履歴全体・実secret storeの検査ではない|
|SpotBugs SECURITY|151 main classes、違反0・scanner exit0、実正常/異常self-test成功|java-static-analysis artifact。全カテゴリ・test code・設計の網羅検査ではない|
|SBOM/OSV|169 components、advisory0、scanner exit0|dependency-audit artifact。production/test等の解決済みMaven依存。OS/plugin/toolは別対象|
|Trivy|**OS32（MEDIUM28/LOW4）、library0、C/H0**、scanner exit0|Docker artifactのimage-scan.json。32はfinding instancesで25 unique CVE、31件は修正版表示なし。audit処理成功は脆弱性0でも到達性評価完了でもない|
|Docker|clean archive、JRE17、10001:10001、read-only、業務9段階＋B1/B2/B3、V1〜V9、readiness、認可、shutdown成功|summary.jsonの段階別assert。現在/previous imageは同じIDで設定故障復帰のみ。公的TLS・異版binary・実host未検証|

mainのDocker `source_revision`は基準mainそのもの。`clean_archive_build=true`とworkspaceの`source_dirty=true`を別fieldとして保持する。
`jwt_revocation` / `password_change` / `admin_force_revocation`の世代1/2/対象3・ADMIN1と監査1/計2/計4行は各段階の観測で、全部を最終値と呼ばない。
Linux ubuntu-24.04の当該runでcertificate not yet valid/Wait strategy failedは0。Flyway/MariaDB互換性WARNは保持する。

OS49は10月18日、OS31は10月21日等の当時のsnapshot。
直前のOS31からOS32への追加は`libgnutls30t64` `3.8.3-1.1ubuntu3.6`の`CVE-2026-88648`（MEDIUM、修正版表示なし）。
本書は[旧66件の台帳](2026-10-12-dependency-advisories.md)や[依存再基準追補](2026-10-21-dependency-rebaseline-supplement.md)を書き換えず、OSV0とTrivy32を別の監査対象として表示する。

## 2. 3つの強みと面接で示す根拠

[デモ・source/test対応表](../portfolio-walkthrough.md)で、実在するクラス/メソッドと常設ITを一対一で辿れる。

|説明できる能力|実装と観測|過大に説明しないこと|
|---|---|---|
|Transactionと在庫競合|[OrderService.createOrder](../../src/main/java/com/example/demo/service/OrderService.java)と[20顧客IT](../../src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java)。User→商品昇順、READ_COMMITTED、ロック後refresh。20接続/Tx、成功10/不足10、在庫0、注文/明細/販売数量/履歴10、version10、retry不変を3反復|Service/DBテストとHTTP負荷を区別。過去の[隔離lock除去対照](../testing.md#異なる20顧客の在庫枯渇競合aud-06--issue-108)は当時の証拠で、今回は再mutationしていない|
|再送可能な注文API|[指紋生成](../../src/main/java/com/example/demo/service/OrderRequestFingerprint.java)、[V4のcustomer/key UNIQUE](../../src/main/resources/db/migration/V4__add_order_idempotency.sql)、[IdempotentOrderIT](../../src/test/java/com/example/demo/controller/IdempotentOrderIT.java)。同key/同内容は同じ注文、別内容409、業務rollbackでkeyも残らない|HTTP bytesの固定cache、無期限の分散exactly-once、未実装の決済/外部通知までの保証ではない|
|認証・失効の原子性|[JwtUserAuthenticator](../../src/main/java/com/example/demo/security/JwtUserAuthenticator.java)、[logout Tx](../../src/main/java/com/example/demo/service/AuthRevocationTransactions.java)、[password Tx](../../src/main/java/com/example/demo/service/PasswordChangeTransactions.java)、[ADMIN Tx](../../src/main/java/com/example/demo/service/AdminTokenRevocationTransactions.java)と[各IT](../portfolio-walkthrough.md#3-jwt失効と旧トークン拒否)。UUID/世代、DBの現在role、監査との一括commitとrollback、旧JWT401|認証はDB依存。失効前に認証済みの進行中業務は完了し得る。jti単体失効/refresh/reset/role変更APIは未実装|

401/403/404、400 OUT_OF_STOCKと409 IDEMPOTENCY_KEY_CONFLICT、503/500を実際のHandler/ITに対応させた。
READMEの5設計観点のsource/testリンク、[Transaction文書](../design/transactions-and-locking.md)、[代替案とトレードオフ](../design/decisions.md)も維持する。

## 3. DoD16との照合と独立受入の履歴

元のローカル監査`portfolio-audit-2026-10-16.md` §10の16項目と、
[PR #127の監査](portfolio-v1-final-acceptance-2026-10-18.md)、[PR #131の再判定](portfolio-v1-acceptance-recheck-2026-10-18.md)、
[Issue #132のsnapshot/live是正](../release-status.md#d01d12の是正記録issue-132)を照合した。
前回の12/3/1、14/2/0を遡及変更しない。
[Issue #126](https://github.com/utl-flaxy/inventory-management-system/issues/126#issuecomment-6251676628)と
[#130](https://github.com/utl-flaxy/inventory-management-system/issues/130#issuecomment-6251677031)には、PR #133後の**独立レビューによる16/16・PORTFOLIO GO**が既に記録され、両Issueと#132はCLOSEDだった。
この過去の承認を否定せず、B1〜B3等を含む現在の応募版について、改めて以下を**判定案**として提出する。

|ID|従来条件を維持した確認|今回の根拠 / 判定案|
|---|---|---|
|D01|main CI5・F/E/S0・README run|§1のmain SHA/push runとREADME snapshot/live分離。本PR HEADの5ジョブはPR本文で別途提出。達成候補|
|D02|Secret例外0・期限由来事故除去|[例外空配列](../../.github/security/exceptions.json)と実scan/self-test。将来の全CI成功を保証しない。達成|
|D03|#99/#101/#102統合、#103の扱い|3PRはMERGED。明示指示を優先して#103はDraft/OPEN・対象外で維持。closeしない。達成|
|D04|商品名256/注文101は400|[ProductNameValidationIT](../../src/test/java/com/example/demo/controller/ProductNameValidationIT.java)、[OrderItemLimitIT](../../src/test/java/com/example/demo/controller/OrderItemLimitIT.java)、main実CIの18/11件とDB不変。達成|
|D05|別顧客競合とlock除去失敗の証拠|§2の20顧客・3反復とPR #109当時の対照記録を区別。main実CIで通常43件成功。達成|
|D06|DB停止+有効JWTは503|[AuthDatabaseFailureIT](../../src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java)と[ServerFailureResponse](../../src/main/java/com/example/demo/exception/ServerFailureResponse.java)。503/500を分離。達成|
|D07|Servlet二重登録解消|[SecurityConfig.jwtServletRegistration](../../src/main/java/com/example/demo/security/SecurityConfig.java)でdisabled、[JwtFilterRegistrationIT](../../src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java)で実HTTP/順序検証。達成|
|D08|alg=none/改ざんの明示回帰|同ITの6ベクトル、401・DB不変・認証照会/業務呼出0。mainログも照合。達成|
|D09|JWTライフサイクルと後続|[auth](../auth.md)と[移行runbook](../security/jwt-revocation-migration.md)。旧v1.1候補のB1〜B3は実装済み、#79 CLOSED、jtiは#134 OPEN。履歴の未実装表記は当時のまま。達成|
|D10|短い入口、5組source/test|README先頭の3テーマ＋保持した5設計観点、詳細walkthroughから実ファイル/メソッドへ。90秒は導線の設計目標で、第三者ユーザビリティ測定ではない。達成候補|
|D11|Caddy/TLS/loopback構成図|READMEのhost Caddy→127.0.0.1:8080→app→非公開DB。定義/local CA/公的TLS未検証を区分。達成|
|D12|README/docs/Epic/台帳の時点整合|現確認値を§1とREADME/release/testingへ追加。[Epic #8](https://github.com/utl-flaxy/inventory-management-system/issues/8)の上段は10/18の明示snapshot/live契約で、今回本文を編集していない。旧数値を現在へ書換えない。達成候補|
|D13|検証済み/本番未検証の対照|§4とREADME、Docker/TLS/DB運用文書。達成|
|D14|設計文書2本|transactions-and-locking.mdとdecisions.md、実コードと照合。F01/F02は解消済みで保持。達成|
|D15|クリーンデモ手順と証跡|既存harness/公開データ/3シナリオを整理。mainの9段階＋B1/B2/B3、新HEADのlocal/CI結果はPR本文に分離。達成候補|
|D16|Deploy無効、AWS不操作、残余開示|DeployのGitHub状態を確認し、§4の残存Issueは閉じない。実AWS/既存state等は未操作。達成|

**提案は16/16を応募用の範囲で受け入れること**。うちD01/D10/D12/D15は本PRの文書差分、リンク・再現検証、新HEAD CIを独立レビューで確認する条件付きの候補であり、正式な受入済16/16と書き換えない。
技術面接では、設計理由→source→対応テスト→有限の実証→未保証事項の順に説明する。

## 4. 残存Issueと本番運用の停止条件

|残余|応募版を阻害するか / 継続する判断|
|---|---|
|#75 OPEN、TF-C BLOCKED|実クラウド運用の開始条件。TF-A/B/C0、synthetic49/拒否ゲート、[C05追補](cloudtrail-digest-c05-design-2026-10-23.md)はソース/静的検証。C01/C02/C05、B01〜B14全BLOCKED、T01〜T17全NOT_RUNでありAWS受入0件。応募版ではこの技術判断を説明できることを受入とする|
|#152 CLOSED|オフライン設計Issueの完了だけ。C05の実権限/署名/配送が検証済みという意味ではない|
|#145 OPEN|Windowsのcertificate not yet valid、Wait strategy、Caddy初回失敗、DB観測CLI初回3失敗は根因未確定。Linux成功や後続成功で解消しない。応募版に非保証を開示し、再現環境/ログ改善は別作業。試験skip/retry/timeout緩和なし|
|#134 OPEN|jti単体失効は保留。現行はユーザー全端末単位の失効で、他端末も失効する。refresh/reset/role変更も範囲外。未実装を明示すれば応募版の必須追加機能ではない|
|OS32・Flyway WARN|脆弱性0/上流保証済みとはしない。OSの31→32差分と監査対象を開示し、継続監査。本番の許容判断・上流互換性判断は別途必要|
|実AWS・TLS・secret・DB復旧|現行構成のAWS実配備、公的TLS/実DNS、実secret/信頼proxy peer、本番DBのbackup/restoreは未検証。使い捨て[BackupRestoreIT](../../src/test/java/com/example/demo/repository/BackupRestoreIT.java)の成功と区別。**本番運用前必須**|
|rollback・容量・監視/SLA|同image設定故障復帰とDB不変は実証。異版binary/旧鍵/過去DBへの復帰、実hostの負荷・容量・監視・RPO/RTO/SLAは未検証。アプリ復帰はDBのundoではない。**本番運用前必須**|

現在の残余は、明示した応募用作品の機能証明を妨げる未修正バグと一括評価しない。
一方、実利用者向けの運用承認へ読み替えず、**PRODUCTION NO GOを継続**する。
PR #103はDraft/OPEN・auto-mergeなしで、今回の品質証拠やマージ対象へ含めない。
過去の37/46/66件の原記録、10/18監査/再判定、JSON証跡、Epic歴史本文は改変しない。
今回新たな重大実装欠陥を実証した場合はこの文書PRへ修正を混ぜず、再現条件付きの別Issue案を提出する。

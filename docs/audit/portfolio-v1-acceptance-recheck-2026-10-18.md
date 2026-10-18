# ポートフォリオv1.0 最終受入再判定 — 2026-10-18 / Issue #130

## 判定提案

**CONDITIONAL GO（portfolio成果物）。実利用者向け本番運用はPRODUCTION NO GO。**

PR #129でF01/F02の設計文書・READMEのsource/testリンク不足は解消した。最新mainの実CIも必須5ジョブ成功、Java2,176件F/E/S=0を確認した。ただし、前回と同じ16項目を厳格に採点すると **達成14・一部2・未達0・未検証0**。READMEが参照する基準CIと、Epicの「現在」欄に残るPR状態について、D01/D12を無条件の達成へ丸めない。

残条件は§5のmetadata同期、または独立レビューによる「時点固定snapshotと本書の最新証拠の連結を受入とする」という明示判断である。新機能や本番コードの変更は不要。本書だけで基準を緩めたり、正式GOを宣言したりしない。ChatGPTの独立レビュー・マージ判断を待つ。Issue #126はOPENを維持する。

## 1. 対象と証拠の時点

- remoteをfetchし、GitHub APIとも照合したmain: **`f15480170d4b58b34d54198dcc11a9a399f1aa36`**（[PR #129](https://github.com/utl-flaxy/inventory-management-system/pull/129) squash merge）。作業branch: `audit/issue-130-v1-acceptance-recheck`。
- 最新main push CI: **[39127003622](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39127003622)**。`event=push`、headは上記main、`completed/success`。run metadata、全job、実ログ、4種類のartifactを新たに取得した。
- 前回: [PR #127](https://github.com/utl-flaxy/inventory-management-system/pull/127) / [最終監査§5:100][previous-dod]。当時の監査対象mainは`f88bb8805e4fae0b0c97977d867ee39daac6a73c`、監査文書のmerge先は`186aad8cb41fa7d7227304aeed4a4c3d6436ad69`。前回main CI39118938200、PR #129のCI39125717712を今回main CIの代わりには使わない。
- 本書のソース行参照は特記しない限り**f1548017に固定**。本書を追加するPRのHEAD/runはPR本文に別記し、そのHEADとの一致と5ジョブ成功を提出条件にする。自己のcommit SHAを本文へ埋め込む循環は作らない。
- 変更対象は本書1ファイルだけ。旧監査・Epic・READMEをこの監査で書き換えず、既存ソース、テスト、POM、runtime設定、migration、CI設定には触れない。

原資料はローカルの`portfolio-audit-2026-10-16.md`（105,963 bytes、SHA-256 `513f332419b43c4dbdc6045eaa963ffddc195ab701852fb335cf3925ed379523`）と`portfolio-v1-release-audit-2026-10-18.md`（59,191 bytes、SHA-256 `ecf1490bf6fb6329b9823075bdf2df148eb5592050398cc7ac2a2a34c749eb48`）。実物を再読し、前者§10:1038〜1053のDoD16件を前回のIDで維持した。AUD37件、旧46件、元依存66件、Trivy49件は別の母集団であり、合算・全件修復済み扱いをしない。

## 2. 最新main CIの独立確認

|必須job|status / conclusion|今回取得した実測と範囲|
|---|---|---|
|test|completed / success|Java17 full verify、Surefire **1,314** + Failsafe **862** = **2,176**。Failures/Errors/Skipped **0/0/0**、BUILD SUCCESS。MariaDB/Testcontainers、Docker動的検証を実行|
|terraform|completed / success|fmt、backendなしinit、validate、**mock27 passed / 0 failed**。AWS適用ではない|
|secret-scan|completed / success|Python **29**成功。実Gitleaks、正常/異常self-test。tracked **331**、findings0、unresolved0、[例外0][exceptions]|
|java-static-analysis|completed / success|SpotBugs SECURITY、main **118 classes**、violations0、scanner_exit0。実スキャナーの正常/異常self-test成功|
|dependency-audit|completed / success|解決済みSBOM **169 components / advisory0**、severity={}、scanner_exit0。`AUDIT_ONLY_NOT_A_SECURITY_PASS`|

[workflow:1][workflow]と[品質ゲート実装:199][gates]を照合した。OSVの対象はMaven解決依存、Trivyはapp runtime imageで、対象が異なる。audit-only jobのSUCCESSを脆弱性ゼロと読み替えない。Flyway/MariaDB12.2互換性WARNはログに残っている。

main保護もread-only APIで再確認: 必須5checkは表の名前と一致、strict/enforce_admins/linear_history=true、force push/delete禁止、required approving reviews=**0**。独立レビューは人手の運用でありGitHubの必須承認1件ではない。Deployは**disabled_manually**。設定変更・Deploy実行はしていない。

### PR #124の否定系が失われていないこと

mainログでUNSIGNED、PAYLOAD_TAMPERED、WRONG_SIGNATURE、WRONG_KEY、WRONG_ISSUER、EXPIREDの6種すべてを確認した。各ケースは`rejectedCommands=2 status=401 dbUnchanged=true rejectedLookup=0 rejectedBusinessCalls=0`。実Servletの[JwtFilterRegistrationIT:244][jwt-it]、[JwtUtil:65][jwt-util]と照合し、署名/issuer/期限・DB照会前拒否・USER/ADMIN境界の回帰を維持する。PR #124の追加9件は既存2,167件に加わったままで、今回新しいJavaテストは追加していない。

## 3. DoD16項目 before / after

前回の「一部」は残差がある状態であり、元々の達成条件を無断で差し替えない。「達成」は有限のfixture・記載した契約の範囲だけで、本番SLAや将来の無故障を意味しない。

|ID|前回の条件・残差|PR #127時点|最新mainでの判定|根拠と変化|
|---|---|---|---|---|
|D01|最新main CI5job、F/E/S0、READMEにrun URL|一部|**一部**|mainの5/5・2,176件は§2で達成。[README:112〜115][readme-ci]は186aad8/39123950859の明示snapshotで、最新f1548017/39127003622ではない。古い値は虚偽ではないが、文字どおりの「main HEADのrunをREADMEに記載」は未同期。§5|
|D02|Secret例外0、11/11失効事故の原因除去|達成|**達成**|[exceptions.json:1][exceptions]は空。main実scan・Python29・正常/異常self-test成功。将来の新secretや環境障害まで成功を保証しない|
|D03|#99/#101/#102統合、#103の扱い|達成|**達成**|3PRはAPIでMERGED確認。#103はOPEN/Draft、auto-mergeなし。元DoDのclose要求よりユーザーの明示指示「マージ対象外」を優先し、現行品質証拠へ含めない|
|D04|商品名256 / 注文101明細を400拒否|達成|**達成**|[ProductNameValidationIT:46][product-name]と[OrderItemLimitIT:55][order-limit]を照合、今回main CIで18件/11件成功。実MariaDBのDB不変を含む|
|D05|別顧客競合IT、ロック除去で失敗する証拠|達成|**達成**|[InventoryConcurrencyMariaDbIT:238][concurrency]の20顧客・在庫10・10成功/10不足、3反復を維持。[PR #109当時の隔離mutation記録][mutation]は歴史証拠で、今回ロック除去は再実施していない|
|D06|DB停止+有効JWTを503に分類|達成|**達成**|[AuthDatabaseFailureIT:82][db-auth]と[ServerFailureResponse:18][server-failure]。今回main CI成功。一時障害503と予期しない/設定障害500を区別|
|D07|JWT filterのServlet重複登録解消|達成|**達成**|[SecurityConfig:31][security-config]で自動登録disabled、[実Servlet IT:142][filter-owner]でSecurityチェーン単独実行。Issue #81 CLOSED|
|D08|alg=none / 改ざんJWTの明示回帰|達成|**達成**|§2の6否定ベクトルと実HTTP401/DB不変/業務呼出0。現在のコードはPR #124以後不変|
|D09|JWTライフサイクル明文化、#79後続|達成|**達成**|[JWT設定:1][jwt-doc]、[auth:42][auth-doc]。Issue #79 OPEN、tokenVersion+uidはv1.1候補、jti台帳は別段階。失効API実装済みとは説明しない|
|D10|短いREADME、5点すべてのsource/testリンク|一部|**達成**|149行の[README:22〜26][readme-design]。5行ともsrc/mainとsrc/testへの直接リンクを実ファイルで検証。F02解消、§4|
|D11|Caddy/TLS/loopbackの構成図|達成|**達成**|[README:48][diagram]。想定SG→Caddy→127.0.0.1:8080→app→DB。公的TLS実配備との区別を維持|
|D12|README/docs/Epic/台帳の現行整合|一部|**一部**|Boot4.1.1/2,176/29/27/OS49/保護は現CIと一致し、F04/F05は解消。ただしEpicの「現在」欄がPR #129をOPEN/未反映と表示し、実際はMERGED。dated snapshotへの依存を解消せず達成扱いしない。§5|
|D13|検証済み / 本番未検証の対照表|達成|**達成**|[README:117][readme-scope]、[release-status:41][release-scope]。実AWS/TLS/容量/backup等を非保証として区分|
|D14|指定の設計文書2本|未達|**達成**|[transactions-and-locking:1][tx-doc]と[decisions:1][decisions]が存在。境界/分離/ロック順/rollback、代替案/トレードオフ/未保証事項を実コードと照合。F01解消、§4|
|D15|クリーンなローカルデモ手順と実行証跡|達成|**達成**|[demo:17][demo-doc]、[ローカル証跡:1][demo-json]の実行入力が現mainと一致。main CIで同じ9段階を新たに実行（§6）。5分以内・公的TLSの保証ではない|
|D16|Deploy無効、AWS未変更、本番残課題を開示|達成|**達成**|Deploy disabledをAPIで確認。[README:130][readme-risks]と[release-status:93][release-risks]、#75/#79/#86/#87/#89等を継続。本監査でAWS/本番には触れない|

|集計|達成|一部|未達|未検証|合計|
|---|---|---|---|---|---|
|before / PR #127|12|3|1|0|16|
|after / f1548017|**14**|**2**|**0**|**0**|**16**|
|変化|+2|-1|-1|0|0|

設計説明のBLOCKER F01/F02は解消した。D01/D12は情報の参照時点・現在欄の残条件であり、14/16を16/16と報告しない。Windows初回失敗の根因解決を新たな達成根拠にしていない。

## 4. F01/F02の直接検証

PR #129は文書8ファイルだけの変更。merge前186aad8から今回mainまで、`src/`、POM、Docker/Compose、scripts、workflow、Terraformに差分はない。

|対象|実物で照合した内容|
|---|---|
|[Transaction設計:7][tx-boundary]|DTO公開入口のREAD_COMMITTED、self-invocation、flush≠commit、一覧/CSVのREPEATABLE_READ、履歴MANDATORY、参照DEFAULTを区別。OrderService:72/120/221/386、InventoryService:39/91、ProductService:99/121と照合|
|[行ロック順:37][tx-locks]|作成User→商品ID昇順、本人/ADMIN取消Order→操作User→商品、手動調整User→商品、商品PUTのversion検査。RepositoryのPESSIMISTIC_WRITEとrefreshを確認。全SQL・全状況の無デッドロック保証とは書かない|
|[rollback・待機:118][tx-rollback]|同Txの注文/明細/stock/履歴rollback、DDL/importとの区別、pool10/acquire5s/lock5s/Tx10s/driver/終了猶予を正本と照合。HTTP SLAとは別|
|[テスト対応:147][tx-tests]|明示するテストメソッド12個の宣言が現ソースに存在。別顧客、取消競合、snapshot、履歴失敗rollback、key再試行等を確認。H2補助とMariaDB行ロック証拠、過去mutationを区別|
|[設計判断:8〜171][decisions]|モノリス、排他/版、DB冪等性、snapshot/整数、DTO、JWT、認証制限、HTTP互換性、Docker/TLS、品質gateの10判断。代替案を実測比較済みとは主張しない|

READMEの5行は以下の直接リンクを確認した。単にclass名の文字列が存在するだけでは合格としていない。

|README行|sourceへの入口|testへの入口|
|---|---|---|
|22 販売超過/更新消失|OrderService / Product|InventoryConcurrencyMariaDbIT|
|23 二重処理|OrderService / InventoryService|IdempotentOrderIT / InventoryAdjustmentIdempotencyIT|
|24 途中失敗/snapshot|OrderService / OrderItem|InventoryConcurrencyMariaDbIT / MoneyApiIT|
|25 認証/権限|JwtUtil / JwtAuthenticationFilter|JwtFilterRegistrationIT / AuthorizationMatrixIT|
|26 障害分類|ServerFailureResponse / prod properties|AuthDatabaseFailureIT / DatabaseTimeoutIT、加えてDocker検証script|

この表の参照元は[固定mainのREADME:22][readme-design]。相対パス/Markdown anchor、設計文書の相互リンク、テストメソッド宣言を検査した。外部URL全件の将来の永続性や、あらゆる業務スケジュールの網羅を保証する検査ではない。

## 5. F03〜F05、Epic、歴史資料

|指摘|再確認結果|残差・処置案|
|---|---|---|
|F03 / 現行参照時点|README/testing/Docker/TLS/release-statusの基準は5f0fae4から186aad8へ更新済み。5f0fae4のrun/imageは履歴として保持。Epicは#124/#125/#127 MERGED、#123 CLOSEDへ同期した|**一部**。最新はf1548017/39127003622。README:112〜115の基準は旧run。Epic上段はupdatedAt=`2026-10-18T12:19:49Z`のままPR #129 OPEN/未反映と記載。実際のmerge後状態と分ける|
|F04 / DB fixtureの固定範囲|[README:44][db-pin]、[release-status:26][release-pin]はCompose12.2 tag、Java IT主に12.2.2/一部12.2 tag、Docker demoだけ完全digestと区別。実際のCompose、IT宣言、verify_image.py:25と一致|**解消（文書）**。全DB imageのbit固定を実装したわけではなく、可変tagは運用前の検討事項として残る|
|F05 / 起動runbook|[configuration:79][config-compose]はmulti-stageによるhost jar不要、full verify、APP_MEMORY_LIMIT、検証したLOGIN_TRUSTED_PROXIES、fail-closed、V1〜V6を説明。[同:119][config-prod]はjar用APP_LOGIN_RATE_LIMIT_TRUSTED_PROXIESとCompose変数を区別|**解消（文書）**。LOGIN_TRUSTED_PROXIESは現workflowのSSH転送変数ではなくhost側準備。実peer/容量/本番適用は未実施|

F03は旧snapshotが虚偽だからではなく、「現在の参照先」として残る表示が最新mergeを反映していないための残差である。[Epic #8](https://github.com/utl-flaxy/inventory-management-system/issues/8)の現在欄/折り畳んだ歴史本文は、Issue #128で保存した全文と今回API取得値が一致。過去の1836/OS17/protected=false等を現在値へ読み替えない。Epic/Issueの本文は本監査で編集していない。

**正式受入前の選択肢:** (a) READMEの現在基準をf1548017/39127003622へ追記し、旧基準は残す。Epic上段もPR #129 MERGED・設計2文書はmain反映済みへ追記する。(b) 独立レビューでdated snapshot運用を明示承認し、本書の最新証拠への連結でD01/D12を受け入れる。ここでは(a)を推奨し、(b)を無断で採用しない。本PRはreport-onlyを保つ。

前回監査、旧46件台帳、元依存66件台帳、Issue76のJSON、PR125のデモJSONをPR #127 merge時のGit blobと比較し、いずれも**byte一致**を確認した。旧監査の達成12/一部3/未達1、旧46件の歴史的F20/D21/A1/U4、前回再分類F29/D12/A1/U4を遡及修正しない。AUD-13のF01/F02は今回解消、AUD-14のmetadataは上記残差、AUD-11のWindows根因は未確定。37件/46件の攻撃プローブ全件を今回再実行したという意味ではない。

## 6. Docker実測とrollbackの保証範囲

最新mainの`docker-validation-f1548017…/summary.json`を取得し、[PR125の値を出さない証跡][demo-json]と9段階が完全一致することを検証した。

|段階|最新main CIの結果|
|---|---|
|1 register|2ユーザー、ROLE_USER、通常起動によるADMIN生成0|
|2 offline ADMIN|3並行CLIで1人作成、再実行で変更なし|
|3 authentication|USER/ADMINとも200|
|4 authorization|匿名401、不正JWT401、USERのADMIN操作403|
|5 product|ADMIN商品作成200、単価100、在庫10|
|6 order/retry|数量2、合計200、在庫8、同keyは同じ注文|
|7 inventory|ADMINが+3、在庫11|
|8 cancel|CANCELLED、在庫13|
|9 ADMIN query|200、注文1、状態CANCELLED|

- source/revisionは**f1548017**、clean_archive_build=true、固定UID/GID10001:10001、JRE17.0.20.1、read-only runtime、明示メモリ上限、V1〜V6、readiness/APIのassert成功。
- image ID: `sha256:81a56c95210dc55368cb734c1c7e102bf1a29b298471900eeec328f937cbdf69`、337,301,413 bytes。READMEの基準186aad8のimageと別buildで、image IDの違いをAPI回帰や再現不能の証拠とはしない。
- graceful shutdown: commit経路HTTP200/停止0.232秒、deadline経路409 CONCURRENT_UPDATE/5.109秒。両方で新規受付停止、終了143、OOM/強制killなし、pool終了・残接続0・lock waits0、再起動後の同key retry/replay成功。
- app-only復帰: readiness故障から復帰、DB ID/起動時刻・注文/在庫不変。ただし**previous_image_idと上記image IDは同一**。同一バイナリの故障設定復帰であり、異なる旧バイナリ/新schema/旧鍵との互換rollbackの実証ではない。

今回ローカルでDockerデモ全体を追加再実行したとは報告しない。新たな動的証拠はmain CIと本書PR CIの隔離実行、ローカルのデモ証跡は過去のclean入力が同じことを確認して利用した。Testcontainersも各試験の使い捨て資源だけを使用し、既存コンテナ/volume・本番DBには操作していない。

## 7. Windowsの失敗履歴と今回の補助検証

[前回監査§10:283][windows-history]の事実を保持する。

|実行|観測と評価|
|---|---|
|Issue #126のWindows初回full verify|Surefire1,314/Failsafe862、**Failures3 / Errors0 / Skipped0、BUILD FAILURE**。`productLockTimeoutReturns409RollsBackAndSameKeyCanRetry()`の3反復でDB観測CLI exit1。Java2,176件実行を「全件成功」と書かない|
|同時点の対象IT再実行|9件成功、DB待機5033/5000/5043ms。初回の失敗原因が解決した証明ではない|
|Issue #128のWindows full verify|2,176件F/E/S=0、BUILD SUCCESS、18:09（PR #129の履歴）。これも初回3失敗を消さない|
|今回Issue #130のWindows補助full verify|Java17.0.18、`mvnw.cmd verify` **BUILD SUCCESS、19:54、exit0**。Surefire1,314 + Failsafe862 = **2,176、F/E/S=0**。DatabaseTimeoutIT9件成功、行ロックDB待機5066/5003/5000ms。main/PR Linux CIとは別評価とし、初回根因未確定を維持|

初回失敗はDatabaseTimeoutIT:181→373→378の観測CLI検査で発生し、DBネイティブ待機4500〜6500msの判定に達していない。stderrが当時のテストで公開されないため**根因未確定**。以前のホストHTTP観測6819/6881msや証明書時刻エラーと同一原因と断定せず、「単なるフレーク」とも認定しない。

pool10、接続取得5秒、行ロック5秒、Tx10秒、driver timeoutは不変。skip、sleep追加、失敗の握りつぶし、成功するまでのfull verify反復は行わない。後続Issue案は「Windows DB観測CLI exit1の秘密を出さない原因分類・Docker/WSL時刻/負荷との相関記録」。再現時の終了分類、計測起点、DB側時計とhost時計を分離し、初回stderr未取得を埋める推測はしない。

## 8. 残余リスクと優先順位

|対象|今回の評価|受入/後続の扱い|
|---|---|---|
|D01/D12 metadata|実装BLOCKERは解消。現在参照のrun/PR状態に残差|**portfolio正式受入前**に§5の追記または明示的なsnapshot受入判断。独立レビューまで条件付き|
|Windows初回3失敗|根因未確定。現在CI成功は初回診断の代替にならない|portfolioでは失敗を開示し再現診断を推奨。一般的なWindows安定性・本番SLAを保証しない|
|Trivy OS49|**MEDIUM27/LOW22、CRITICAL/HIGH0、library0**。49 finding instances / unique CVE33。今回artifactは49件ともFixedVersionなし、scanner_exit0|監査時点のDB結果。OS49を未解消として開示し継続監査。Caddy/DB/host image全体のscanではない。修正版公開後にbase更新・全回帰を評価|
|OSV|169 components、advisory0、実行成功。Trivyとは別|0件は未来/未知/全plugin依存の安全保証ではない。抑制や検査無効化なし|
|Flyway/MariaDB|Flyway12.4.0/MariaDB12.2の互換性WARNを実ログで確認、V1〜V6/validate成功|有限fixtureの成功と上流の正式な互換性保証を区別。警告を隠さず継続確認|
|JWT個別失効|logout失効、password変更、強制/個別失効API、uid/tokenVersion/jtiなし|#79 OPEN。v1.0は制約開示、v1.1候補uid+tokenVersion、jtiは別段階。username再割当て・旧鍵rollbackの旧JWT再受理リスクを保持|
|証明書時刻事象|過去のMariaDB起動時エラー観測を保持、今回の成功で根因解決とはしない|Docker/WSL/host時計・image/cert生成・再現条件の切り分け候補。TLS検査を無効化しない|
|rollback/復旧|同一imageの設定故障復帰だけ実証、異版binary/schema/旧鍵は未検証|**運用前必須**。DB migration自動undoなし、本番backup/restore・RPO/RTO・旧版互換を別途確認|
|実運用の容量/境界|AWS未配備として扱う。実DNS/公的TLS/secret/proxy peer/本番DB/持続負荷/SLA/監視/障害復旧は未検証|**PRODUCTION NO GO維持**。1GiB同居を安全と扱わず、容量と負荷の実測、remote state/lock(#75)、権限/backup等を運用前に検証|
|証跡の寿命|CI artifact保存30日。runリンクが残ってもartifact保持を保証しない|maintainerによる期限内の値を除去した証跡保管を推奨。今回の取得/判断は固定SHA/run/件数で記録|

旧46件の本番未検証4件等を本書で閉じず、#75/#79/#86/#87/#89などの未適用課題を保持する。AUD-02〜04/18〜21等の運用準備を、ポートフォリオの文書・テスト完成と混同しない。

## 9. 本監査PRの検証と独立レビューへの引継ぎ

- 既存8文書と本書の相対パス/anchor **214箇所**、本書の固定SHA行参照 **36件**、READMEの5組source/test、設計のテストメソッド宣言12個、歴史資料の同一性、git diff --checkを検証した。外部URL全件の到達性保証はしない。
- Java17ローカルfull verifyは§7の履歴と分離してPR本文へ結果を記載。本書を追加した**PR HEADのCI**も新たに実行し、5jobのstatus/conclusion、head一致、Java/Python/Terraform/Secret/Static/OSV/Docker/Trivyの実測を確認する。
- PR CIのDocker sourceがGitHubの生成merge refなら、親が監査mainとPR HEADであることを確認して記載する。main push SHAやPR headと同一だと偽らない。
- PR本文に最新head・CI URL・job別結果・今回ローカル結果・リンク検証件数を付記する。未完了や失敗は成功へ読み替えず、scan件数が変化したら差分を明示する。
- report-only、未マージ、auto-mergeなし。PR #103はOPEN/Draftのまま対象外、Issue #126はOPEN。AWS/Deploy/実secret/本番DB/既存volume操作なし。

**結論はCONDITIONAL GO提案 / PRODUCTION NO GO。** F01/F02を再び未解消とは評価しない一方、D01/D12の残条件とWindowsの根因未確定を消さない。正式GOは独立レビューで判断する。

## 固定SHAの参照

[previous-dod]: https://github.com/utl-flaxy/inventory-management-system/blob/186aad8cb41fa7d7227304aeed4a4c3d6436ad69/docs/audit/portfolio-v1-final-acceptance-2026-10-18.md#L100
[exceptions]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/.github/security/exceptions.json#L1
[workflow]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/.github/workflows/ci.yml#L1
[gates]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/scripts/ci/quality_gates.py#L199
[jwt-it]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java#L244
[jwt-util]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/main/java/com/example/demo/security/JwtUtil.java#L65
[readme-ci]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/README.md#L112
[product-name]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/test/java/com/example/demo/controller/ProductNameValidationIT.java#L46
[order-limit]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/test/java/com/example/demo/controller/OrderItemLimitIT.java#L55
[concurrency]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java#L238
[mutation]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/orders/inventory-concurrency.md#L1
[db-auth]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java#L82
[server-failure]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/main/java/com/example/demo/exception/ServerFailureResponse.java#L18
[security-config]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/main/java/com/example/demo/security/SecurityConfig.java#L31
[filter-owner]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java#L142
[jwt-doc]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/security/jwt-configuration.md#L1
[auth-doc]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/auth.md#L42
[readme-design]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/README.md#L22
[diagram]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/README.md#L48
[readme-scope]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/README.md#L117
[release-scope]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/release-status.md#L41
[tx-doc]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/design/transactions-and-locking.md#L1
[decisions]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/design/decisions.md#L1
[demo-doc]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/demo.md#L17
[demo-json]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/evidence/issue-123-demo.json#L1
[readme-risks]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/README.md#L130
[release-risks]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/release-status.md#L93
[tx-boundary]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/design/transactions-and-locking.md#L7
[tx-locks]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/design/transactions-and-locking.md#L37
[tx-rollback]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/design/transactions-and-locking.md#L118
[tx-tests]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/design/transactions-and-locking.md#L147
[db-pin]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/README.md#L44
[release-pin]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/release-status.md#L26
[config-compose]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/configuration.md#L79
[config-prod]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/configuration.md#L119
[windows-history]: https://github.com/utl-flaxy/inventory-management-system/blob/f15480170d4b58b34d54198dcc11a9a399f1aa36/docs/audit/portfolio-v1-final-acceptance-2026-10-18.md#L283

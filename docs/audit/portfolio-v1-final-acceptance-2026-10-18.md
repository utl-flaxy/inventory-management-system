# ポートフォリオv1.0 最終受入監査 — 2026-10-18 / Issue #126

## 判定提案

**推奨: CONDITIONAL GO（ポートフォリオ成果物）。PRODUCTION NO GO（実利用者向け本番運用）。**

現mainのコード・JWT否定系・隔離Dockerデモ・必須CIの証拠は確認できた。ただし、原監査のv1.0 Definition of Doneが明示した設計文書2ファイルと、READMEの設計ハイライトからコード・テストへ進むリンクは未達である。これらを完了するか、代替資料を受入条件と同等と認める独立レビューの明示判断が必要であり、PR #124/#125のマージだけを条件免除とは解釈しない。

今回のWindows補助full verifyではDB観測CLIに起因する3失敗を観測した。対象ITの再実行は成功したが、初回の根因は未確定（F08/§10）。現mainのLinux CI成功とは分けて記録し、Windows full verify成功とは報告しない。

本書は監査者による**判定提案**であり正式GO宣言ではない。ChatGPTによる独立レビュー・マージ判断を待つ。監査PRの変更は本書1ファイルのみ。既存のコード・テスト・構成・migration・歴史的台帳・Issue/Epic本文は変更しない。Issue #126は`Refs #126`で関連付け、Issue #79等を閉じない。

## 1. 対象・方法・一次資料

対象は main **`f88bb8805e4fae0b0c97977d867ee39daac6a73c`**（[PR #125](https://github.com/utl-flaxy/inventory-management-system/pull/125) squash merge後）。着手時にremote mainをfetchし、GitHub APIのmain SHAとも一致を確認した。専用branchは`audit/issue-126-v1-final-acceptance`。既存の未コミット変更はなく、先行作業を破棄していない。

- 最新main CI: [39118938200](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39118938200)。`event=push`、`headSha=f88bb8805e4fae0b0c97977d867ee39daac6a73c`、`completed/success`。
- GitHubから現在のrun/jobs/log/artifacts、main保護、Deploy status、PR/Issue状態、Epic #8本文をread-only取得した。古いPRの成功を最新mainの成功として流用していない。
- ソース・テストの行参照は、特記しない限り上記SHAに固定する。最新PR HEADのCI URL・結果は本書を追加するPR本文で追跡する。自己のcommit SHAを本文へ埋め込んで循環させない。
- 設定値の実本番適用、AWS、実EC2、実secret、公的TLS、本番DB/既存volumeは確認・操作していない。GitHub APIのread、隔離されたテスト実行、監査Markdown追加だけを実施した。
- 2026-10-18のリリース監査が参照するAUD37件の原定義は2026-10-16のClaude報告である。新しい37件を創作せず、元のAUD-IDを保持した。旧46件・依存66件・Trivy49件とは母集団が異なり、合算しない。

|資料ID|参照資料と由来|監査上の扱い|
|---|---|---|
|S1|ローカル `portfolio-v1-release-audit-2026-10-18.md`、59,191 bytes。SHA-256 `ecf1490bf6fb6329b9823075bdf2df148eb5592050398cc7ac2a2a34c749eb48`|実物を読んだ。基準main b1af9ff、CONDITIONAL GOのB1〜B3を今回再照合|
|S2|ローカル `portfolio-audit-2026-10-16.md`、105,963 bytes。SHA-256 `513f332419b43c4dbdc6045eaa963ffddc195ab701852fb335cf3925ed379523`|実物のAUD01〜37、§10のDoD16項目を使用。原判定を確定バグと盲信しない|
|S3|[docs/audit/2026-10-02-remediation-traceability.md:1][src1]|旧46件の出典索引・歴史的判定F20/D21/A1/U4を保存。元S1/S2原文そのものの再取得は行わず、台帳が記録する指摘範囲を再照合|
|S4|[Issue #71](https://github.com/utl-flaxy/inventory-management-system/issues/71)、[Issue #126](https://github.com/utl-flaxy/inventory-management-system/issues/126)、[Epic #8](https://github.com/utl-flaxy/inventory-management-system/issues/8)|#71 CLOSED。#126/#8 OPEN。Epicの現在欄と折り畳まれた履歴を区別|
|S5|[現main CI](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39118938200) の実ログ・artifact|Java集計、Python/Terraform、Secret/Static/OSV、Docker/Trivy、警告を独立抽出|

S1/S2のローカル原本は `C:\Users\y03iw\Documents\Codex\2026-10-09\` にある。上記ハッシュは資料同定用であり、GitHubに原本全文を公開したという意味ではない。監査補助ログは作業ディレクトリの兄弟 `issue-126-final-audit-2026-10-18/` に保存した。公開レビューで追える根拠は本書の固定SHAリンク・run・既存台帳とし、ローカル原本にしかないDoDは§5へ要約転記した。原本の入手不能を隠してはいない。

元の監査には設計改善・運用未検証・選択的な提案も含まれる。以下の「完了」は対象範囲内の是正・証拠があることを意味し、分散攻撃耐性、将来の無故障、実本番SLAまで保証する意味ではない。

## 2. 最新mainの独立検証

|必須job|status / conclusion|実測内容と限界|
|---|---|---|
|test|completed / success|Java17 full verify: Surefire 1,314 + Failsafe 862 = **2,176**、Failures/Errors/Skipped **0/0/0**、BUILD SUCCESS。Docker build/API/shutdown/rollback/Trivyも実行|
|terraform|completed / success|fmt/validate、mock **27 passed / 0 failed**。実AWS apply/実リソースの保証なし|
|secret-scan|completed / success|Python **29**、Gitleaks実scanと正常/異常セルフテスト。tracked files **328**、findings0、unresolved0、例外**0**|
|java-static-analysis|completed / success|SpotBugs SECURITY正常/異常セルフテスト、analyzed_classes **118**、violations0、scanner_exit0|
|dependency-audit|completed / success|SBOM/OSV: **169 components / advisory0**、severity={}、scanner_exit0。`AUDIT_ONLY_NOT_A_SECURITY_PASS`|

検査入口は [.github/workflows/ci.yml:1][src2]、検出・異常ケース契約は [scripts/ci/quality_gates.py:1][src3] と [docs/ci-quality-gates.md:9][src4]。OSV/Trivyは実行エラーを成功にせず、既知脆弱性の存在は監査として出力する設計。job SUCCESSと「脆弱性ゼロ」は同義ではない。

main保護をAPIで確認: 必須5check名は上記と一致、`strict=true`、`enforce_admins=true`、linear history必須、force push/delete禁止。必要承認数は**0**であり「GitHubが1人以上の承認を強制する」とは書かない。ChatGPTの独立レビューは今回の人による手順である。Deploy workflowは **`disabled_manually`**、GitHub environmentsは0。設定変更は行っていない。

新たなローカルJava17 full verifyも補助証拠として実施する。結果は§10に記録する。現main CIの成功とWindowsローカルの成否は別々に評価し、いずれかの失敗を他方で隠さない。

## 3. PR #124 — JWT否定系の受入証拠

[PR #124](https://github.com/utl-flaxy/inventory-management-system/pull/124) はMERGED、旧head `dedd01091518976114d20f16498bbb37fa78f7a1`、squash main `5f0fae4ebd89754e23538cb09f97a1ff037255ed`。変更はテスト3ファイルとtesting.mdだけで、本番JWT動作変更なし。最新mainの該当3テストファイルはこのmerge時から変更なし。追加はパラメーター展開で**9件（単体3 + 実Servlet IT6）**。既存の別鍵・issuer・期限テストも維持されている。

[src/main/java/com/example/demo/security/JwtUtil.java:48][src5] はusernameをsubとしてissuer/iat/expを発行し、[src/main/java/com/example/demo/security/JwtUtil.java:64][src6] は署名・issuer・期限を1回のsigned-claims parseで検証する。[src/main/java/com/example/demo/security/JwtAuthenticationFilter.java:55][src7] はDB照会より前に検証する。実Web contextでの登録経路一本化とexact POST認証パスは [src/main/java/com/example/demo/security/SecurityConfig.java:33][src8] / [src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:142][src9] で確認した。

|否定ケース|コード・テスト|現main実行で確認したこと|
|---|---|---|
|alg=none / 未署名|[src/test/java/com/example/demo/security/JwtNegativeTokens.java:12][src10]<br>[src/test/java/com/example/demo/security/JwtUtilTest.java:73][src11]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:244][src12]|UNSIGNED。単体はUnsupportedJwtException。保護された注文/ADMIN商品コマンド2件とも401|
|payload改ざん / MAC改ざん|[src/test/java/com/example/demo/security/JwtUtilTest.java:73][src11]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:244][src12]|PAYLOAD_TAMPERED / WRONG_SIGNATURE。単体はSignatureException。ITは各2コマンド401|
|別署名鍵|[src/test/java/com/example/demo/security/JwtUtilTest.java:42][src13]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:244][src12]|WRONG_KEY。401|
|issuer不一致|[src/test/java/com/example/demo/security/JwtUtilTest.java:58][src14]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:244][src12]|WRONG_ISSUER。401|
|期限切れ|[src/test/java/com/example/demo/security/JwtUtilTest.java:101][src15]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:244][src12]|EXPIRED。401。期限なし拒否も既存単体テストで維持|

現main CIログの6種類すべてで `rejectedCommands=2 status=401 dbUnchanged=true rejectedLookup=0 rejectedBusinessCalls=0` を確認。ITはusers/products/orders/order_items/inventory_movementsのスナップショット不変、認証ユーザー照会0、業務呼出0をassertする。正常JWT200、USERのADMIN操作403、ADMIN200の対照も [src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:285][src16] にある。レスポンスProblemDetail/requestId、安全なログとSecurityContext解放も検証する。

これは固定した6攻撃ベクトルと既存検証契約の回帰証拠であり、暗号実装の完全証明、すべてのalg/異常入力の網羅、JWT個別失効機能の存在を意味しない。

## 4. PR #125 — README・Dockerデモ・rollbackの証拠

[PR #125](https://github.com/utl-flaxy/inventory-management-system/pull/125) は2026-10-18T10:21:37ZにMERGED、旧head `a36d4729e080f9d9da9377e39575c8e8c16d5b35`、squash mainは監査基準f88bb880。文書/デモJSON/値を出さない検証helperの計12ファイル変更で、本番ソース・Compose・Dockerfile・workflow変更なし。Issue #123は同日10:21:59ZにCLOSED。

コミット済みローカルデモ [docs/evidence/issue-123-demo.json:1][src17] は `source_revision=f2da455d996065e476cf2129610ddd003f7ad755`、source_dirty=false、clean archive build。実行入力（src/pom/Dockerfile/Compose/verify_image.py）をそのcommitから現mainへdiffして差分なし。squash前branchのcommitを「mainの祖先」と誤認せず、有効な入力の一致として扱う。今回そのローカルHTTPデモ全体を再実行したとは記載せず、**現main Linux CIで新たに実行された同じ9段階**と照合した。

|段階|現main CIの実測 / コミット済みJSONとの照合|
|---|---|
|1 register|2ユーザー作成、ROLE_USER、通常起動のADMIN生成0|
|2 offline ADMIN|専用CLIを3プロセス並列実行し作成1、再実行ADMIN_PRESENT、既存資格情報/権限不変|
|3 authentication|USER/ADMINログイン各200。認証情報・JWTを要約証跡に出力しない|
|4 authorization|匿名401、無効JWT401、ROLE_USERのADMIN操作403|
|5 product|ADMIN商品作成、価格100・在庫10|
|6 order / replay|数量2・合計200、同一Idempotency-Keyは同一注文、在庫8|
|7 inventory adjustment|ADMINが+3、在庫11|
|8 cancellation|注文CANCELLED、在庫13|
|9 ADMIN query|ADMIN注文取得200、件数1・状態CANCELLED|

`demo_steps`の9要素は、現main CI summaryとコミット済みJSONで完全一致した。JSONの必須項目・型・段階数・resultを確認した。再現手順は [docs/demo.md:17][src18]、実assertは [scripts/docker/verify_image.py:1][src19]。使い捨てプロジェクト・新規volume・一時資格情報を使用する設計で、既存volumeに対する検証ではない。

現main CI Docker summary:

- source/revision label `f88bb8805e4fae0b0c97977d867ee39daac6a73c`、clean_archive_build=true。job workspaceのsource_dirty=trueはarchive入力がcleanという別項目と区別した。
- Java17 JRE、固定UID/GID **10001:10001**、read-only rootfs・capability削除・tmpfs上限・明示メモリ制約。V1〜V6、DB接続、`/readyz=200`、headers/errorの検証成功。
- image ID `sha256:cbab56489fc61044d38e1c482411d6e2ed69e660b6d9ec0c0bf113884d0e8ea1`、337,301,413 bytes。ローカルの別build結果とbit同一とは扱わない。
- app API後のcgroup使用量355,000,320 bytes（peak363,245,568）、DB304,205,824（peak318,193,664）。app1GiB + DB512MiBの隔離fixtureでの一時観測であり、1GiBホストへの同居可能性・定常容量・SLAを保証しない。[docs/docker-runtime.md:96][src20] は1GiBを非推奨とし、2/4/8GiBの容量案を分ける。
- graceful shutdown commit経路: 進行中HTTP200、stop0.35秒。deadline経路: HTTP409 CONCURRENT_UPDATE、stop5.125秒。ともに新規受付停止、exit143、OOM/強制killなし、pool shutdown完了、残接続0・lock waits0、再起動後の同key retry/replay成功。
- app-only rollback: readiness接続先をcontainer loopback `127.0.0.1:1` にして失敗→復帰、DB ID/start timeと注文/在庫不変。**previous_image_idと実行image IDは同一**。これは同一バイナリの故障設定復帰の実証である。

**未実証:** 異なる旧バイナリ/新schema/旧鍵との互換rollback、実EC2のdeploy.sh全経路、公的DNS/TLS、本番RPO/RTO。DockerのHEALTHCHECK定義とcurlの存在はコードから読めるが、`State.Health.Status=healthy`をassertした証跡はなく、readiness HTTP成功とは別（AUD-25）。TLS文書の隔離Caddy/local CA試験も、公的CA・実ホストへは外挿しない。

## 5. v1.0受入条件（S2 §10）との対応

S2:1038〜1053の16項目を、現行ユーザー指示によるPR #103除外とJWT後続方針を優先して判定した。「達成」は限定された契約への証拠があること。「一部」は残差あり。将来日のCI成功や本番安全を保証する読み替えはしない。

|ID|要約|現在判定|証拠・残差|
|---|---|---|---|
|D01|最新main CI5job・F/E/S0・READMEのrunリンク|一部|最新main CIは達成（§2）。[README.md:110][src21] は明示的な5f0fae4/391093 snapshotで、最新f88 runへの同期は未済。F03|
|D02|Secret例外0・11/11失効事故解消|達成|[.github/security/exceptions.json:1][src22] は空。現main実scan/日付を含むPython検証成功。期限由来の失敗要因を除去したが、将来の新secret/環境障害まで成功保証しない|
|D03|#99/#101/#102統合、#103整理|達成|3PR MERGED。[PR #103](https://github.com/utl-flaxy/inventory-management-system/pull/103) はOPEN/Draft。原DoDのclose要求より現行指示「マージ対象外」を優先し、閉じず評価対象から除外|
|D04|商品名256 / 注文101明細は400|達成|[src/test/java/com/example/demo/controller/ProductNameValidationIT.java:46][src23]<br>[src/test/java/com/example/demo/controller/OrderItemLimitIT.java:55][src24]。実MariaDB、DB不変を含む|
|D05|異なる顧客の競合IT・lock除去失敗|達成|[src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java:237][src25]<br>[docs/testing.md:1][src26]。20顧客/在庫10を3回反復。隔離mutationは[PR #109](https://github.com/utl-flaxy/inventory-management-system/pull/109)の歴史証拠であり今回再実施なし|
|D06|DB停止+有効JWTは503|達成|[src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java:82][src27]<br>[src/main/java/com/example/demo/exception/ServerFailureResponse.java:18][src28]。一時障害503と構成/予期しない障害500を分離|
|D07|JWT filterのServlet二重登録解消|達成|[src/main/java/com/example/demo/security/SecurityConfig.java:33][src8]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:142][src9]。[#81](https://github.com/utl-flaxy/inventory-management-system/issues/81) CLOSED|
|D08|alg=none / 改ざんJWTの明示テスト|達成|§3。6ベクトル、追加9件、最新main実CIで401・DB不変・業務処理0|
|D09|JWTライフサイクル文書・#79後続|達成|[docs/security/jwt-configuration.md:1][src29]<br>[docs/auth.md:42][src30]。[#79](https://github.com/utl-flaxy/inventory-management-system/issues/79) OPEN、v1.1 tokenVersion+uid候補、jti台帳は別段階|
|D10|短いREADME、5ハイライトのcode/testリンク|一部|[README.md:19][src31]。145行と5点は達成、表中のクラス名はplain code textで直接リンクなし。F02|
|D11|Caddy/TLS/loopback構成図|達成|[README.md:46][src32]。SG→Caddy→loopback→app→DB、CI/Deploy境界を記載|
|D12|README/docs/Epic/台帳の数値・version・保護|一部|[docs/release-status.md:3][src33]<br>[docs/audit/2026-10-02-remediation-traceability.md:1][src1]。Boot4.1.1/2176/49/保護は整合、古い値は履歴。EpicのPR125未マージ表示とfixture固定範囲等はF03/F04|
|D13|検証済み / 本番未検証の対照表|達成|[README.md:114][src34]<br>[docs/release-status.md:39][src35]。AWS/TLS/容量/バックアップ等を非保証として分離|
|D14|指定の設計文書2本が存在|未達|`docs/design/transactions-and-locking.md` と `docs/design/decisions.md` は両方不存在。既存設計資料への同等性承認も未発見。F01|
|D15|クリーンなローカルデモと実行証跡|達成|[docs/demo.md:1][src36]<br>[docs/evidence/issue-123-demo.json:1][src17]。§4のローカル入力一致と現main CI9段階を照合。公的TLS/5分以内の所要時間保証ではない|
|D16|Deploy disabled / AWS未変更 / 本番残課題表示|達成|[README.md:6][src37]<br>[docs/release-status.md:41][src38]。[#75](https://github.com/utl-flaxy/inventory-management-system/issues/75)/[#79](https://github.com/utl-flaxy/inventory-management-system/issues/79)/[#86](https://github.com/utl-flaxy/inventory-management-system/issues/86)/[#87](https://github.com/utl-flaxy/inventory-management-system/issues/87)/[#89](https://github.com/utl-flaxy/inventory-management-system/issues/89)等で追跡。本監査で実AWS確認なし|

**集計: 達成12 / 一部3 / 未達1（計16）**。一部を完了へ丸めない。D01/D12のsnapshotは履歴として正しく、全てを虚偽記述と評価しない。現在の正式完成判定へ採用するには、本書の最新証拠との連結または独立レビューによるsnapshot運用の了承が必要。

S1の残条件B1（文書の現行化）は大部分達成・一部metadata残、B2（README/設計/デモ）はデモ達成・設計リンク/2文書残、B3（JWT否定系）は達成。前監査の「CONDITIONAL GO」を根拠なく自動GOへ変更しない。

## 6. AUD37件の現在マトリクス

出典列の行番号はS2。状態は「完了」「一部」「後続」「受容」「未検証」を区別する。後続は修正済みを意味しない。PRリンクは対応履歴であり、最新mainへの保持は§2の実CI/現ソースで確認した。

|AUD|出典S2行|対象|現在判定|理由・残存範囲|現main証拠 / 履歴|
|---|---|---|---|---|---|
|AUD-01|495|Secret例外4件の失効|完了|期限延長なしで例外0、Gitleaks実scan/異常検出成功。|[.github/security/exceptions.json:1][src22]<br>[docs/ci-quality-gates.md:55][src39][PR #105](https://github.com/utl-flaxy/inventory-management-system/pull/105)|
|AUD-02|519|root EBSの削除|後続 / 運用前必須|delete_on_termination=trueが残る。実EC2/既存DB配置は未確認。独立保存・復旧方針が必要。|[terraform/main.tf:125][src40]<br>[docs/db/backup-restore.md:49][src41]|
|AUD-03|540|同一host backup・外部保管/定期実行なし|後続 / 運用前必須|disposable restore ITはある。offsite/定期実行・実RPO/RTOは未証明。|[scripts/db/backup.sh:1][src42]<br>[src/test/java/com/example/demo/repository/BackupRestoreIT.java:37][src43]|
|AUD-04|547|SSH host fingerprint未設定|後続 / 運用前必須|Deploy disabled。無効状態はSSH相手検証を実装した証拠にならない。|[.github/workflows/deploy.yml:1][src44]<br>[docs/deployment.md:1][src45]|
|AUD-05|557|商品名255文字境界|完了|Unicode code point上限255、256は400/DB不変。UTF-16長とは区別。|[src/main/java/com/example/demo/dto/ProductRequest.java:14][src46]<br>[src/main/java/com/example/demo/dto/ProductUpdateRequest.java:19][src47]<br>[src/test/java/com/example/demo/controller/ProductNameValidationIT.java:46][src23][PR #107](https://github.com/utl-flaxy/inventory-management-system/pull/107)|
|AUD-06|579|異なる顧客の在庫競合|完了|20実DB顧客/在庫10/別key/同時開始、成功10不足10、接続/履歴/version/snapshot。3反復。mutationは歴史証拠。|[src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java:237][src25]<br>[src/main/java/com/example/demo/repository/ProductRepository.java:17][src48][PR #109](https://github.com/utl-flaxy/inventory-management-system/pull/109)|
|AUD-07|593|register負荷無制限|完了|登録専用window/source/in-flight/state上限、信頼proxy・503/429、ログイン枠と分離。単一processの限界あり。|[src/main/java/com/example/demo/security/RegistrationRateLimiter.java:14][src49]<br>[src/test/java/com/example/demo/security/RegistrationSecurityIT.java:56][src50]<br>[docs/auth.md:175][src51][PR #111](https://github.com/utl-flaxy/inventory-management-system/pull/111)|
|AUD-08|611|注文明細数無制限|完了|最大100、DTOとservice入口でDB参照/lock前に拒否。境界/重複/不正数量/再試行/DB不変。|[src/main/java/com/example/demo/dto/OrderRequest.java:16][src52]<br>[src/main/java/com/example/demo/service/OrderService.java:197][src53]<br>[src/test/java/com/example/demo/controller/OrderItemLimitIT.java:55][src24][PR #113](https://github.com/utl-flaxy/inventory-management-system/pull/113)|
|AUD-09|620|商品検索入力境界・未統合PR|完了|Unicode/keyword/page/sort検証、OpenAPI/ProblemDetail整合。#99/#101/#102はmain統合済み。|[src/main/java/com/example/demo/service/ProductPageableNormalizer.java:10][src54]<br>[src/test/java/com/example/demo/controller/ProductPagingIT.java:48][src55][PR #102](https://github.com/utl-flaxy/inventory-management-system/pull/102)|
|AUD-10|630|DB障害が認証401へ誤分類|完了|一時DB障害503、設定/予期しない失敗500、JWT不正401を維持。|[src/main/java/com/example/demo/exception/ServerFailureResponse.java:18][src28]<br>[src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java:82][src27]<br>[src/test/java/com/example/demo/security/JwtDatabaseFailureTest.java:32][src56][PR #115](https://github.com/utl-flaxy/inventory-management-system/pull/115)|
|AUD-11|640|pool/lock/Tx/shutdown未設定|一部（Linux fixture成功 / Windows失敗）|pool10/acquire5s/lock5s/Tx10s等を維持。Linuxでshutdown/rollback/再試行実証。今回Windows full verifyはDB観測CLIで3失敗、F08/§10。SLA非保証。|[src/main/resources/application-prod.properties:1][src57]<br>[src/test/java/com/example/demo/security/DatabaseTimeoutIT.java:154][src58]<br>[docs/db-lifecycle.md:1][src59][PR #117](https://github.com/utl-flaxy/inventory-management-system/pull/117)[PR #122](https://github.com/utl-flaxy/inventory-management-system/pull/122)|
|AUD-12|655|JWT lifecycle未定義|完了（文書） / 機能後続|TTL/鍵/DB照会/logout制約/username再利用/旧鍵rollbackを明示。個別失効未実装は意図的v1.1候補。|[docs/auth.md:42][src30]<br>[docs/security/jwt-configuration.md:1][src29][#79](https://github.com/utl-flaxy/inventory-management-system/issues/79)[PR #120](https://github.com/utl-flaxy/inventory-management-system/pull/120)|
|AUD-13|671|README・設計の伝達|一部 / BLOCKER|入口145行、5点/構成図/デモは改善。原DoDのcode/testリンクと設計2文書は未達（F01/F02）。|[README.md:19][src31]<br>[docs/orders/inventory-concurrency.md:1][src60][PR #125](https://github.com/utl-flaxy/inventory-management-system/pull/125)|
|AUD-14|683|文書/Epic/台帳が古い|一部 / SHOULD|数値/Boot/履歴分離を改善。本書で旧46行を現行再照合。Epic未マージ表示等の残差F03/F04/F05。|[docs/release-status.md:1][src61]<br>[docs/audit/2026-10-02-remediation-traceability.md:1][src1][#8](https://github.com/utl-flaxy/inventory-management-system/issues/8)|
|AUD-15|692|OSV/Trivy audit-onlyとOS49の説明不足|完了（開示） / リスク受容|OSV0とTrivy OS49/library0を明示。残存脆弱性そのものの修復を完了とはしない。|[docs/ci-quality-gates.md:9][src4]<br>[docs/docker-runtime.md:121][src62]|
|AUD-16|700|apt完全一致pinの将来build失敗|一部 / 後続|再現性優先でpin継続。上流削除でfail closed。更新・再build・scan手順はあるが継続監視/供給保証なし。|[Dockerfile:1][src63]<br>[docs/docker-runtime.md:17][src64]|
|AUD-17|707|container log rotationなし|後続 / 運用前必須|Composeにlogging driverのsize/file制限なし。hostディスク容量・保持/削除方針未実証。|[docker-compose.yml:1][src65]<br>[docs/deployment.md:1][src45]|
|AUD-18|713|host buildとt3.micro容量|一部 / 運用前必須|1GiB非推奨、容量別予算を文書化。IaC defaultはt3.micro、実容量/余裕/同居負荷は未確認。|[terraform/variables.tf:67][src66]<br>[docs/docker-runtime.md:96][src20]<br>[scripts/deploy.sh:1][src67]|
|AUD-19|719|deploy environment/移行前backupなし|後続 / 運用前必須|environments0、Deploy disabled。schema互換/承認/backup/復旧確認は運用前に必要。|[.github/workflows/deploy.yml:1][src44]<br>[docs/deployment.md:1][src45]|
|AUD-20|725|metrics/alarm/自動復旧なし|後続 / 運用前必須|probe/構造化ログはある。外部metrics、通知、復旧SLOの証拠はない。|[docs/deployment.md:1][src45]<br>[src/test/java/com/example/demo/logging/ProductionLoggingIT.java:53][src68]|
|AUD-21|731|Terraform local state|後続 / 運用前必須|remote backend/lock/KMS/最小権限は未実装。mock成功はstate運用保証でない。|[terraform/provider.tf:1][src69]<br>[terraform/README.md:1][src70][#75](https://github.com/utl-flaxy/inventory-management-system/issues/75)|
|AUD-22|741|JWT filter登録/prefix|完了|Servlet自動登録を無効化、Security経路のみ、exact公開POST、類似prefix保護を実HTTP測定。|[src/main/java/com/example/demo/security/SecurityConfig.java:33][src8]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:142][src9][PR #118](https://github.com/utl-flaxy/inventory-management-system/pull/118)|
|AUD-23|742|JWT明示否定系不足|完了|6ベクトル・9追加、実Servlet/MariaDBで401・DB不変・lookup/業務0（§3）。|[src/test/java/com/example/demo/security/JwtUtilTest.java:73][src11]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:244][src12][PR #124](https://github.com/utl-flaxy/inventory-management-system/pull/124)|
|AUD-24|743|作成200/在庫不足400|受容（互換性）|現行HTTP契約を保持。409等へ一括変更しない。安全なProblemDetailを文書化。|[src/main/java/com/example/demo/exception/GlobalExceptionHandler.java:36][src71]<br>[docs/api-errors.md:1][src72]|
|AUD-25|744|Docker HEALTHCHECKのhealthy未assert|未検証（残存）|curl/HEALTHCHECK定義とreadyzの実HTTP成功はあるがState.Health.Status=healthyをassertする証跡なし。|[Dockerfile:1][src63]<br>[scripts/docker/verify_image.py:1][src19]|
|AUD-26|745|coverage reportなし|後続（任意）|JaCoCo閾値/レポート未導入。2176成功をcoverage率へ換算しない。|[pom.xml:1][src73]|
|AUD-27|746|Compose MariaDB tag可変|後続 / 運用前必須|Composeは12.2可変tag。Docker demoはdigest固定だがJava ITは12.2.2/12.2のtag指定（F04）。|[docker-compose.yml:1][src65]<br>[src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java:49][src74]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:67][src75]|
|AUD-28|747|広いSSH CIDRの許可|後続 / 運用前必須|/0拒否だけでは/1等を拒否しない。実SG値は未確認。|[terraform/variables.tf:62][src76]<br>[terraform/tests/host.tftest.hcl:1][src77]|
|AUD-29|748|bootstrap JDK/古いcomment|後続（要用途確認）|userdataのJDK導入は残る。host build方式との依存を確認せず削除しない。|[terraform/userdata.sh:12][src78]<br>[scripts/deploy.sh:1][src67]|
|AUD-30|749|git history secret scan未実施|未検証（任意/公開前再評価）|現tree tracked scanを確認。全履歴secret不存在は認定しない。|[scripts/ci/quality_gates.py:202][src79]<br>[docs/ci-quality-gates.md:96][src80]|
|AUD-31|750|gitignore fence|後続 / SHOULD|先頭/末尾のMarkdown fenceが残る。scope外で未変更。|[.gitignore:1][src81]|
|AUD-32|751|OSV0でもUNREMEDIATED表示|後続 / SHOULD|件数0とaudit-only modeは正確、固定メッセージだけ誤解を招く。F06。|[scripts/ci/quality_gates.py:385][src82]|
|AUD-33|752|冗長DirtiesContext/名前|未検証 / 任意後続|命名/実行時間の包括評価なし。現在の実DB保証を削る最適化は提案しない。|[src/test/java/com/example/demo/service/ProductConcurrencyServiceTest.java:23][src83]<br>[src/test/java/com/example/demo/repository/OrderCancellationTransactionTest.java:39][src84]|
|AUD-34|753|CSV slow clientのTx保持|後続 / 運用前必須|ADMIN専用・keyset batchだがwriter.flush中もTransaction内。Tx10秒は任意socket書込み期限の保証でない。|[src/main/java/com/example/demo/service/OrderService.java:385][src85]<br>[src/test/java/com/example/demo/controller/OrderCsvExportIT.java:76][src86]|
|AUD-35|754|restoreがDROP後import|後続 / 運用前必須|明示replace確認等はあるが自動退避dumpなし。DDL/importの全体atomic rollbackは不可。|[scripts/db/restore.sh:80][src87]<br>[docs/db/backup-restore.md:49][src41]|
|AUD-36|755|CI Docker build cacheなし|後続（任意）|clean archive buildは確認。host跨ぎ永続cacheの効果は未測定、機能gateではない。|[scripts/docker/verify_image.py:1][src19]<br>[.github/workflows/ci.yml:40][src88]|
|AUD-37|756|旧依存Issue/将来拡張の整理|一部 / SHOULD|Boot4現main OSV0。#86/#87/#89は本番適用等と分けOPEN保持、#1/#6/#7はv1.0新機能にしない。無断closeしない。|[docs/release-status.md:66][src89]<br>[pom.xml:1][src73][#86](https://github.com/utl-flaxy/inventory-management-system/issues/86)[#87](https://github.com/utl-flaxy/inventory-management-system/issues/87)[#89](https://github.com/utl-flaxy/inventory-management-system/issues/89)|

AUD-11のLinux fixture成功とAUD-15の「完了（開示）」を、本番可用性・脆弱性修復済みへ拡張しない。Windowsの失敗は§10に分離して残す。37件をすべて「バグ」「修正済み」として集計しない。

## 7. 旧46件台帳の再照合

S3の歴史的F20/D21/A1/U4は書き換えない。本表は同じ46 IDの**現在のコードへの適用性**を別に記録する。F=指摘の是正確認、D=一部是正/後続、A=互換性を理由に受容、U=実運用証拠なし。S3各行のIssue/PR/merge SHA索引を継承し、対応が後から追加された項目は本表に追加PRを記す。

S1の基準b1af9ffから現mainの`src/main`、pom、Dockerfile、Compose、workflow、Terraform、deploy/backup/restoreに差分はない。今回すべての過去攻撃プローブを再実施したわけではなく、現行ソースを照合し最新main CIで既存テストが保持されていることを確認した。歴史的mutation/実host試験の未実施を隠さない。

|旧ID|元指摘要約|現在|理由・適用範囲|旧出典行 + 現main証拠|
|---|---|---|---|---|
|P0-01|固定JWT鍵|F|外部secret必須・32 UTF-8 bytes以上、署名/issuer/期限拒否。|[docs/audit/2026-10-02-remediation-traceability.md:49][src153]<br>[src/main/java/com/example/demo/security/JwtUtil.java:23][src90]<br>[src/test/java/com/example/demo/security/JwtConfigurationTest.java:17][src91]|
|P0-02|認証/所有者境界|F|DB role・所有者照会、他人の注文404統一、ADMIN境界。|[docs/audit/2026-10-02-remediation-traceability.md:50][src154]<br>[src/main/java/com/example/demo/security/SecurityConfig.java:24][src92]<br>[src/test/java/com/example/demo/service/OrderOwnershipServiceTest.java:31][src93]<br>[src/test/java/com/example/demo/security/AuthorizationMatrixIT.java:57][src94]|
|P0-03|不正数量|F|正整数/重複/集約検証、DB CHECK。|[docs/audit/2026-10-02-remediation-traceability.md:51][src155]<br>[src/main/java/com/example/demo/service/OrderService.java:197][src53]<br>[src/main/resources/db/migration/V1__baseline_schema.sql:1][src95]<br>[src/test/java/com/example/demo/service/OrderServiceTest.java:37][src96]|
|P0-04|状態/取消|F|合法遷移とrestockを同一Tx、重複取消防止。|[docs/audit/2026-10-02-remediation-traceability.md:52][src156]<br>[src/main/java/com/example/demo/service/OrderService.java:332][src97]<br>[src/test/java/com/example/demo/entity/OrderStateMachineTest.java:13][src98]<br>[src/test/java/com/example/demo/controller/OrderStateControllerTest.java:33][src99]|
|P0-05|販売超過/更新消失|F|User→商品昇順lock、別顧客競合。|[docs/audit/2026-10-02-remediation-traceability.md:53][src157]<br>[src/main/java/com/example/demo/repository/ProductRepository.java:17][src48]<br>[src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java:237][src25]|
|P1-01|例外/エラー契約|F|ProblemDetail、safe logging、DB障害503/500区別。|[docs/audit/2026-10-02-remediation-traceability.md:59][src158]<br>[src/main/java/com/example/demo/exception/GlobalExceptionHandler.java:36][src71]<br>[src/test/java/com/example/demo/security/ProblemDetailContractIT.java:43][src100]<br>[src/test/java/com/example/demo/exception/SafeExceptionLoggingTest.java:24][src101]|
|P1-02|Auth入力/ハッシュ/重複|F|ROLE_USER固定、BCrypt、UNIQUE、公開ADMIN作成不可。|[docs/audit/2026-10-02-remediation-traceability.md:60][src159]<br>[src/main/java/com/example/demo/service/AuthService.java:30][src102]<br>[src/test/java/com/example/demo/security/AuthApiIT.java:57][src103]|
|P1-03|売上の完了時点|F|COMPLETED集計、完了日時契約。|[docs/audit/2026-10-02-remediation-traceability.md:61][src160]<br>[src/main/java/com/example/demo/repository/OrderRepository.java:62][src104]<br>[src/test/java/com/example/demo/service/SalesServiceTest.java:20][src105]|
|P1-04|金額overflow|F|LongとExact演算、DB金額snapshot。|[docs/audit/2026-10-02-remediation-traceability.md:62][src161]<br>[src/main/java/com/example/demo/entity/OrderItem.java:61][src106]<br>[src/main/java/com/example/demo/entity/Order.java:98][src107]<br>[src/test/java/com/example/demo/controller/MoneyApiIT.java:44][src108]|
|P1-05|注文冪等性|F|顧客単位UNIQUE/指紋/再送、同一key別内容409。|[docs/audit/2026-10-02-remediation-traceability.md:63][src162]<br>[src/main/java/com/example/demo/service/OrderService.java:120][src109]<br>[src/main/resources/db/migration/V4__add_order_idempotency.sql:1][src110]<br>[src/test/java/com/example/demo/controller/IdempotentOrderIT.java:48][src111]|
|P1-06|商品PUT/stock/version|F|版競合・在庫調整履歴を保持。|[docs/audit/2026-10-02-remediation-traceability.md:64][src163]<br>[src/main/java/com/example/demo/service/ProductService.java:29][src112]<br>[src/main/java/com/example/demo/service/InventoryService.java:39][src113]<br>[src/test/java/com/example/demo/service/ProductConcurrencyServiceTest.java:23][src83]|
|P1-07|注文集約/customer/snapshot|F|Order/Itemの集約と顧客/価格snapshot。|[docs/audit/2026-10-02-remediation-traceability.md:65][src164]<br>[src/main/java/com/example/demo/entity/Order.java:24][src114]<br>[src/main/java/com/example/demo/entity/OrderItem.java:15][src115]<br>[src/test/java/com/example/demo/repository/OrderAggregatePersistenceTest.java:41][src116]|
|P1-08|migration|F|V1〜V6 validate、fresh/restore fixture成功。既存DB移行は別。|[docs/audit/2026-10-02-remediation-traceability.md:66][src165]<br>[src/main/resources/db/migration/V6__add_inventory_adjustment_idempotency.sql:1][src117]<br>[src/test/java/com/example/demo/repository/BackupRestoreIT.java:37][src43]|
|P1-09|DB secret/root/profile/port|U|prod fail-closed/非rootDB user/loopback設定は存在。実本番注入/既存DB反映は未確認。|[docs/audit/2026-10-02-remediation-traceability.md:67][src166]<br>[src/main/java/com/example/demo/config/ProductionConfiguration.java:17][src118]<br>[docker-compose.yml:1][src65]|
|P1-10|TLS/実DNS|U|隔離Caddy/local CAはIT。公的CA/実peer/DNS/実EC2は未確認。|[docs/audit/2026-10-02-remediation-traceability.md:68][src167]<br>[docs/tls.md:93][src119]<br>[src/test/java/com/example/demo/deployment/CaddyProxyIT.java:19][src120]|
|P1-11|Deploy/host build|D|SHA固定/app-only設計と静的テストはある。実EC2 deploy全経路・旧binary互換は未実証。|[docs/audit/2026-10-02-remediation-traceability.md:69][src168]<br>[scripts/deploy.sh:1][src67]<br>[src/test/java/com/example/demo/deployment/DeploymentScriptTest.java:12][src121]|
|P1-12|DB health/backup/restore|U|probeとdisposable restoreは成功。実DBのbackup保管/復旧/RPO/RTOは未確認。|[docs/audit/2026-10-02-remediation-traceability.md:70][src169]<br>[src/test/java/com/example/demo/repository/BackupRestoreIT.java:37][src43]<br>[src/test/java/com/example/demo/security/ProbeHealthIT.java:50][src122]|
|P1-13|IaC/bootstrap|U|mock27・script契約のみ。AWS apply/実SG/TLS/容量/既存hostなし。|[docs/audit/2026-10-02-remediation-traceability.md:71][src170]<br>[terraform/tests/host.tftest.hcl:1][src77]<br>[terraform/userdata.sh:1][src123]|
|P1-14|observability/ログ運用|D|構造化ログ/requestId/秘匿は実装。外部通知/rotation/実復旧監視なし。|[docs/audit/2026-10-02-remediation-traceability.md:72][src171]<br>[src/test/java/com/example/demo/logging/ProductionLoggingIT.java:53][src68]<br>[src/test/java/com/example/demo/logging/RequestCorrelationFilterTest.java:19][src124]|
|P1-15|実DB回帰テスト不足|F|現main2176・MariaDB競合/障害/冪等性/復元等を実行。網羅率/本番SLAではない。|[docs/audit/2026-10-02-remediation-traceability.md:73][src172]<br>[docs/testing.md:1][src26]<br>[src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java:69][src125]|
|P1-16|旧README11矛盾|F|元11論点は現仕様の入口と詳細へ整理、初期ADMIN手順/デモも追加。新たなDoD設計リンク不足はF01/F02として別評価。|[docs/audit/2026-10-02-remediation-traceability.md:74][src173]<br>[README.md:1][src126]<br>[docs/demo.md:1][src36]<br>[docs/initial-admin-provisioning.md:1][src127][PR #125](https://github.com/utl-flaxy/inventory-management-system/pull/125)|
|P2-01|DTO/OSIV/read境界|F|DTOへ変換、OSIV無効、service read Tx。|[docs/audit/2026-10-02-remediation-traceability.md:80][src174]<br>[src/main/resources/application.properties:1][src128]<br>[src/main/java/com/example/demo/service/OrderService.java:247][src129]<br>[src/test/java/com/example/demo/controller/OsivApiIT.java:54][src130]|
|P2-02|pagination/keyset CSV|F|Page上限/sort制御、ID keyset CSV。|[docs/audit/2026-10-02-remediation-traceability.md:81][src175]<br>[src/main/java/com/example/demo/service/OrderService.java:385][src85]<br>[src/test/java/com/example/demo/controller/OrderPagingIT.java:70][src131]<br>[src/test/java/com/example/demo/controller/OrderCsvExportIT.java:76][src86]|
|P2-03|CSV文字コード/formula|F|CSV escape/BOM/formula防御。|[docs/audit/2026-10-02-remediation-traceability.md:82][src176]<br>[src/main/java/com/example/demo/service/OrderCsvWriter.java:10][src132]<br>[src/test/java/com/example/demo/service/OrderCsvWriterTest.java:14][src133]|
|P2-04|商品404/delete409|F|欠落404/FK delete競合409契約。|[docs/audit/2026-10-02-remediation-traceability.md:83][src177]<br>[src/main/java/com/example/demo/service/ProductService.java:29][src112]<br>[src/test/java/com/example/demo/controller/ProductDeletionIT.java:44][src134]|
|P2-05|注文親子のresponse|F|OrderResponse/明細snapshotを公開。|[docs/audit/2026-10-02-remediation-traceability.md:84][src178]<br>[src/test/java/com/example/demo/repository/OrderAggregatePersistenceTest.java:41][src116]<br>[src/test/java/com/example/demo/controller/OrderControllerTest.java:33][src135]|
|P2-06|安全な例外応答|F|内部情報を出さないProblemDetail/安全event。|[docs/audit/2026-10-02-remediation-traceability.md:85][src179]<br>[src/main/java/com/example/demo/exception/ApiProblemFactory.java:13][src136]<br>[src/test/java/com/example/demo/exception/SafeExceptionLoggingTest.java:24][src101]|
|P2-07|login試行制限|F|信頼transport peer・有限state/concurrency、未設定prodはfail-closed。分散DoS完全防御とはしない。|[docs/audit/2026-10-02-remediation-traceability.md:86][src180]<br>[src/main/java/com/example/demo/security/LoginRateLimiter.java:16][src137]<br>[src/main/java/com/example/demo/security/LoginClientAddressFilter.java:19][src138]<br>[src/test/java/com/example/demo/security/LoginSecurityIT.java:53][src139][PR #98](https://github.com/utl-flaxy/inventory-management-system/pull/98)|
|P2-08|prod Swagger/H2|F|local & !prod限定、prod情報公開なし。|[docs/audit/2026-10-02-remediation-traceability.md:87][src181]<br>[src/main/java/com/example/demo/security/SecurityConfig.java:24][src92]<br>[src/test/java/com/example/demo/security/ProductionProfilesIT.java:37][src140]|
|P2-09|Terraform state/EBS/CIDR|D|AUD02/21/28の運用前課題が残る。|[docs/audit/2026-10-02-remediation-traceability.md:88][src182]<br>[terraform/main.tf:125][src40]<br>[terraform/variables.tf:62][src76][#75](https://github.com/utl-flaxy/inventory-management-system/issues/75)|
|P2-10|JDK/root/single stage/memory|F|multi-stage JRE/nonroot/readonly/budget。host容量は未保証。|[docs/audit/2026-10-02-remediation-traceability.md:89][src183]<br>[Dockerfile:1][src63]<br>[src/test/java/com/example/demo/deployment/DockerRuntimeContractTest.java:18][src141]<br>[docs/docker-runtime.md:70][src142][PR #95](https://github.com/utl-flaxy/inventory-management-system/pull/95)|
|P2-11|初期ADMIN|F|オフライン明示CLI、既存ADMIN不変/並行1作成。|[docs/audit/2026-10-02-remediation-traceability.md:90][src184]<br>[src/main/java/com/example/demo/provisioning/InitialAdminProvisioner.java:42][src143]<br>[src/test/java/com/example/demo/provisioning/InitialAdminLifecycleIT.java:25][src144][PR #96](https://github.com/utl-flaxy/inventory-management-system/pull/96)|
|P2-12|商品query DTO/検証|F|Unicode/byte/CodePoint/ページ境界を検証。|[docs/audit/2026-10-02-remediation-traceability.md:91][src185]<br>[src/main/java/com/example/demo/service/ProductPageableNormalizer.java:10][src54]<br>[src/test/java/com/example/demo/controller/ProductPagingIT.java:48][src55][PR #102](https://github.com/utl-flaxy/inventory-management-system/pull/102)|
|P2-13|HTTP status設計|A|作成200/在庫不足400など既存互換を維持。|[docs/audit/2026-10-02-remediation-traceability.md:92][src186]<br>[docs/api-errors.md:1][src72]<br>[src/main/java/com/example/demo/exception/GlobalExceptionHandler.java:36][src71]|
|P2-14|JWT refresh/revocation|D|parse強化済み。個別失効/refresh APIなし、v1.0文書化で受容、実装はv1.1候補。|[docs/audit/2026-10-02-remediation-traceability.md:93][src187]<br>[docs/auth.md:42][src30]<br>[src/main/java/com/example/demo/security/JwtUtil.java:48][src5][#79](https://github.com/utl-flaxy/inventory-management-system/issues/79)|
|P2-15|CI security/coverage|D|五必須job・保護・SHA pin・実scan導入済み。coverageや広範なpolicy gateは未導入。|[docs/audit/2026-10-02-remediation-traceability.md:94][src188]<br>[.github/workflows/ci.yml:1][src2]<br>[docs/ci-quality-gates.md:96][src80]|
|P3-01|Filter重複登録|F|登録経路一本化・実Servlet測定。|[docs/audit/2026-10-02-remediation-traceability.md:100][src189]<br>[src/main/java/com/example/demo/security/SecurityConfig.java:33][src8]<br>[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:142][src9][PR #118](https://github.com/utl-flaxy/inventory-management-system/pull/118)|
|P3-02|auth prefix除外|F|exact POSTのみ、類似prefixは保護。|[docs/audit/2026-10-02-remediation-traceability.md:101][src190]<br>[src/main/java/com/example/demo/security/JwtAuthenticationFilter.java:27][src145]<br>[src/test/java/com/example/demo/security/JwtPublicEndpointMatcherTest.java:16][src146][PR #118](https://github.com/utl-flaxy/inventory-management-system/pull/118)|
|P3-03|demo/package命名|D|com.example.demo等は残る。名称変更だけの大規模作業をv1.0へ追加しない。|[docs/audit/2026-10-02-remediation-traceability.md:102][src191]<br>[pom.xml:1][src73]<br>[src/main/java/com/example/demo/DemoApplication.java:7][src147]|
|P3-04|Entity setter|D|setterは残る。公開APIの制御を迂回する現実のバグと未実証。|[docs/audit/2026-10-02-remediation-traceability.md:103][src192]<br>[src/main/java/com/example/demo/entity/Product.java:16][src148]<br>[src/main/java/com/example/demo/entity/Order.java:24][src114]|
|P3-05|未使用DTO/Mockito重複|D|使用中DTOを未使用と誤認しない。依存整理の実害/互換効果は未検証。|[docs/audit/2026-10-02-remediation-traceability.md:104][src193]<br>[pom.xml:1][src73]<br>[src/main/java/com/example/demo/service/OrderService.java:56][src149]|
|P3-06|save/readOnly/汎用例外|D|型付き例外/read Txは改善。managed saveをSQL/flush検証なしに一括削除しない。|[docs/audit/2026-10-02-remediation-traceability.md:105][src194]<br>[src/main/java/com/example/demo/service/OrderService.java:247][src129]<br>[src/main/java/com/example/demo/service/ProductService.java:29][src112]|
|P3-07|JWT二重parse/Clock|D|二重parseは解消、Clock注入なし。時刻境界の設計余地を残す。|[docs/audit/2026-10-02-remediation-traceability.md:106][src195]<br>[src/main/java/com/example/demo/security/JwtUtil.java:48][src5]<br>[src/main/java/com/example/demo/security/JwtAuthenticationFilter.java:55][src7]|
|P3-08|gitignore fence/旧履歴|D|fence残存、現tree scan成功と旧履歴の安全は別。履歴一括scan未実施。|[docs/audit/2026-10-02-remediation-traceability.md:107][src196]<br>[.gitignore:1][src81]<br>[scripts/ci/quality_gates.py:202][src79]|
|P3-09|Action pin/permissions|F|外部Action SHA固定・read-only/persist-credentials false。runner/内部download残余は別。|[docs/audit/2026-10-02-remediation-traceability.md:108][src197]<br>[.github/workflows/ci.yml:14][src150]<br>[docs/github-actions-pinning.md:1][src151][PR #94](https://github.com/utl-flaxy/inventory-management-system/pull/94)|
|P3-10|TODO/直書き文言/code体系|D|stable codeあり、message catalog/i18nは未導入。日本語直書きを脆弱性と断定しない。|[docs/audit/2026-10-02-remediation-traceability.md:109][src198]<br>[src/main/java/com/example/demo/exception/ApiProblemFactory.java:13][src136]<br>[src/main/java/com/example/demo/dto/RegisterRequest.java:13][src152]|

本監査の再分類は **F29 / D12 / A1 / U4 = 46**。S1時点F28/D13/A1/U4からP1-16を元の11矛盾の範囲でFへ変更した。原台帳の歴史的F20/D21/A1/U4を置換するものではない。新しいDoDの設計2文書不足・READMEリンク不足・メタデータ/fixture記述は別項として明記しており、F29を「全監査完了」や「本番GO」の根拠にはしない。P0/P1のU4と運用前Dは残る。

## 8. 文書整合性と今回の残差

|対象|確認結果|
|---|---|
|README / release-status / testing|[README.md:110][src21]<br>[docs/release-status.md:3][src33]<br>[docs/testing.md:1][src26]。5f0fae4/391093は明示snapshot。Boot4.1.1/Java17/2176/29/27/OS49/保護の数値は現在と一致。snapshotのimage IDと現在image IDは別buildとして扱う|
|Docker / TLS / deployment|[docs/docker-runtime.md:121][src62]<br>[docs/tls.md:93][src119]<br>[docs/deployment.md:1][src45]。同一image設定復帰、公的TLS/実EC2未検証を明記。古い旧root/JDK PoCや17/25件は履歴として保存|
|旧台帳 / dependency66|[docs/audit/2026-10-02-remediation-traceability.md:1][src1]<br>[docs/audit/2026-10-12-dependency-advisories.md:1][src199]。歴史値の範囲が明示され、現行件数に誤変換しない。本書§7で現コードへの適用性を追記|
|Epic #8|[#8](https://github.com/utl-flaxy/inventory-management-system/issues/8) updatedAt=2026-10-18T08:45:52Z。現在欄に5f/PR125「未マージ」が残り、実merge10:21:37Zより古い。折畳み内のprotected=false/Trivy17等は歴史で、現在主張ではない|
|リンク / デモJSON|対象10文書の相対file/anchorリンク110件を検査、欠落0。外部URL40種類を全件HTTP fetchしたとは主張しない。重要GitHub PR/Issue/runはAPI確認。JSON9段階/型/主張と現main artifactを照合|

以下は**今回修正しない**。既存ソースや文書を監査PRへ混ぜず、独立レビュー後に必要最小限の別PRで対応する。BLOCKERは正式なポートフォリオ完成宣言に対する残差であり、本書をマージして記録すること自体の禁止ではない。

|ID / severity|確認方法・再現条件・証拠|必要な受入条件 / 分割案|
|---|---|---|
|F01 / BLOCKER|S2:900〜903、1051。`git ls-files docs/design`は0件。指定2文書が不存在。代替は [docs/orders/inventory-concurrency.md:1][src60] 等に分散|設計文書2本を実装/テストと照合して追加する文書PR、または独立レビューで具体的な代替と同等性を明示承認。コード変更・新機能は不要|
|F02 / BLOCKER|S2:889〜894、1047。[README.md:19][src31] の5点はコード名のplain textで、各項目のsource/testリンクがない|F01と同じ文書PRで5点すべてに現行source+testリンクを付け、リンク検査。既存保証の過大表示を避ける|
|F03 / SHOULD|§8のEpic本文時刻とPR125/Issue123の実状態をAPI比較。README/runは [README.md:110][src21] の明示snapshot|運用者承認後にEpicの現在欄のPR状態を同期。本書最新runへの参照でsnapshot運用を明確にする。本監査ではIssue/Epicを編集しない|
|F04 / SHOULD|[docs/release-status.md:26][src200] は「実IT/デモはdigest固定」。`rg 'mariadb:12.2"' src/test`では4ファイルにtag宣言がある。例 [src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java:49][src74]。[src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java:67][src75] を含む他のITも主に12.2.2 tag指定であり、digestではない。digest固定は [scripts/docker/verify_image.py:25][src201] のDocker demo側|別の小さな文書PRで「Docker demoはdigest、Java ITはtag」と正確に分けるか、別責務で全fixture pinを評価。今回は固定化を先取りせず、ビット再現性を過大に主張しない|
|F05 / SHOULD|[docs/configuration.md:77][src202] にmulti-stage導入前のhost Maven package例が残る。[docs/configuration.md:108][src203] の「必須4項目」だけでは現在のprod proxy条件を説明しない。`.env.example`自体にはAPP_MEMORY_LIMIT/LOGIN_TRUSTED_PROXIES欄がある|F04と同じ文書整合PRで、capacity/proxyのfail-closed条件と現在のbuild経路・migration V1〜V6へ導線を同期。タイムアウト/制限を緩めない|
|F06 / SHOULD|[scripts/ci/quality_gates.py:385][src82] はadvisories0でも固定のFINDINGS UNREMEDIATED文言。[.gitignore:1][src81] はfence残存|コード整備は別Issue/PR候補。件数0/非0の文言テストを伴う小差分、gitignore実ルールを保持。監査report-only PRには含めない|
|F07 / DEFERRED|[scripts/docker/verify_image.py:1][src19] にState.Health.Statusの明示assertなし。§4のrollbackは同一image ID|AUD25と異なる旧binary互換は範囲別の後続。実施するなら隔離環境で失敗/成功とDB不変をassert、元の未保証を遡及PASSにしない|
|F08 / SHOULD（調査未完了）|今回Windows Java17 full verifyでDatabaseTimeoutIT反復3件が失敗。[src/test/java/com/example/demo/security/DatabaseTimeoutIT.java:181][src204] → [src/test/java/com/example/demo/security/DatabaseTimeoutIT.java:378][src205] のDB観測CLI exit1。DB待機4500〜6500ms判定に達しておらず、旧HTTP6500ms超過とは異なる|§10の成否・再実行・安全な診断を分けて維持。初回CLI stderrはテストが公開しないため根因未確定。必要なら別の環境診断Issue/PRで再現・秘密なし診断を検討。pool/timeout/SSL検査の緩和、skip、初回失敗の抹消は禁止|

## 9. 残余リスク・運用前条件

|項目|現在の証拠 / 分類|次の判断|
|---|---|---|
|OSV0 / Trivy OS49|現main SBOM169/advisory0。Trivy0.75.0、OS49 instance（MEDIUM27/LOW22）、library0、Critical/High0、33 unique CVE ID。scan JSON内FixedVersionは49件とも空。[docs/ci-quality-gates.md:9][src4]|audit-only受容を明示。未来の修正版不存在/到達不能/安全を断定しない。base/OS更新とscanを継続。Trivyはapp image対象でDB/Caddy/host全体のscanではない|
|Flyway / MariaDB|現mainCIに「MariaDB12.2はFlyway検証済み範囲（11.2）より新しい」WARNあり。V1〜V6とvalidate/restore等は成功|WARNを隠さない。fixture成功とベンダー互換保証は別。実DB更新/移行は事前検証|
|証明書/時刻問題|過去にMariaDB起動時証明書時刻エラー観測。今回のCIは成功、過去事象の根因・将来解消は未確定|Docker/host/WSLの時刻・image/cert生成と再現条件を残し、再発時はテスト失敗と環境起因を区別。成功で履歴を抹消しない|
|JWT個別失効|[docs/auth.md:42][src30]<br>[docs/auth.md:104][src206]。[#79](https://github.com/utl-flaxy/inventory-management-system/issues/79) OPEN。jti/uid/tokenVersion/台帳/失効APIなし。コピー済JWTは期限まで使用し得る|v1.0は明文化済み制約として受容。公開実利用前に漏洩/退職/パスワード変更/username再割当/鍵復旧要件を決定。tokenVersion+uidはv1.1候補、jti別段階|
|rollback|同一imageの故障設定復帰のみ。DB同一/データ不変は実証|旧binary/schema/鍵の前方後方互換を未検証。旧鍵rollbackで旧JWT再受理リスク。実本番のGOへ転用しない|
|DB待機/shutdown/slow client|[src/test/java/com/example/demo/security/DatabaseTimeoutIT.java:154][src58]<br>[src/main/java/com/example/demo/service/OrderService.java:385][src85]。DB lock5秒/Tx10秒/acquire5秒、HTTP観測遅延とは分離。正常/期限shutdown成功|本番pool10・SLA/負荷/CSV socket保持は未保証。timeout/pool緩和でPASSを作らない|
|容量/実インフラ|[docs/docker-runtime.md:96][src20]。t3.micro1GiBは過去値/参考。実EC2/RAM/負荷は未確認|AWS apply、実secret、DNS/TLS、backup offsite/復元、監視、容量、state、host key、移行前backup/承認が運用前必須。1GiB運用可とは宣言しない|
|履歴/供給網/scan限界|Secret例外0は現tree。Action40桁pin、配布checksum確認、read-only権限はある|git全履歴、runner image、Action内部download、Maven plugin自身の依存、未収録advisoryまで安全認定しない|

優先順位:

1. **v1.0正式完成のBLOCKER:** F01/F02を文書専用の小PRで解消、または独立レビューの明示的な同等性承認。監査PRに実装を追加しない。
2. **v1.0前に推奨:** F03〜F05のsnapshot/fixture/runbook正確性、F08のWindows観測CLI失敗の切り分け。F06は誤解を減らす小さな後続。これらを本番コードの修正済みと誤記しない。
3. **v1.1以降 / 任意:** [#79](https://github.com/utl-flaxy/inventory-management-system/issues/79)、HEALTHCHECK明示assert、異binary rollback、coverage/性能証跡、開発効率改善。[#1](https://github.com/utl-flaxy/inventory-management-system/issues/1)/[#6](https://github.com/utl-flaxy/inventory-management-system/issues/6)/[#7](https://github.com/utl-flaxy/inventory-management-system/issues/7)のS3/RDS/Redisはv1.0完成の新機能にしない。
4. **実運用前必須:** AUD02〜04/17〜21/27〜28/34〜35および実TLS/secret/容量/backup/監視の確認。[#75](https://github.com/utl-flaxy/inventory-management-system/issues/75)・[#86](https://github.com/utl-flaxy/inventory-management-system/issues/86)・[#87](https://github.com/utl-flaxy/inventory-management-system/issues/87)・[#89](https://github.com/utl-flaxy/inventory-management-system/issues/89)は現行Boot4と本番適用の範囲を分け運用者判断で整理する。無断closeなし。

上記は後続Issue/PRの**案**であり、この監査で新Issue作成・本文変更・closeはしていない。[PR #103](https://github.com/utl-flaxy/inventory-management-system/pull/103) は統合検証用Draftのまま、完成判定の統合成果・merge候補に含めない。

## 10. 本監査自身の検証とレビュー手順

- 原本ハッシュ・固定SHAのコード/テストリンク・37/46/16行の件数を検証する。公開相対リンク/anchor検査とJSON整合検査の結果は§8。
- ローカルJava17 full verify結果はこの節の直下へ追記する。CIの2,176件と区別する。
- `git diff --check`、追加ファイルが本書1件だけであること、既存コード/構成/テストに差分がないことを検証する。
- 本書PRの新headで必須5jobを実行し、実run URL・head SHA・各結果をPR本文へ記録する。完了前のCIをPASSと書かない。online監査件数が変われば元snapshotとの差をPR本文に記録する。
- ChatGPTは残差F01/F02、DoD対応表、証拠の適用範囲、PR headとCI一致を独立レビューする。正式GO/mergeはユーザー側の判断。このPRは未マージ・auto-mergeなしで停止する。

### ローカル補助検証

対象: 本番・テストの実行入力が監査main f88bb880と同じWindows 11 / Java17.0.18 / Testcontainers MariaDB。ソースや設定を変更せずに実行した。

| 実行 | 結果 | 証拠と解釈 |
|---|---|---|
| `mvnw.cmd verify` | **BUILD FAILURE / exit1 / 18:04** | Surefire1,314 F/E/S=0/0/0、Failsafe862 F/E/S=3/0/0。計2,176件実行、成功2,173。既存テスト削除/skipなし |
| 失敗3件 | DatabaseTimeoutITの同一テスト3反復 | `productLockTimeoutReturns409RollsBackAndSameKeyCanRetry()[1..3]`。`Disposable DB observation failed ==> expected: <0> but was: <1>`。181→373→378行の観測CLI終了コードで失敗。4500〜6500msのDB計測assertに未到達 |
| 対象IT再実行 | **9件成功、F/E/S=0/0/0 / BUILD SUCCESS / 01:21** | `mvnw.cmd -Dit.test=DatabaseTimeoutIT failsafe:integration-test failsafe:verify`。full verifyの再成功を意味しない |
| 再実行のrow timeout3反復 | HTTP409、DB待機5,033 / 5,000 / 5,043ms | host elapsed5,233 / 4,580 / 4,618ms、観測wait4,612 / 4,512 / 4,552ms。DB時計とhost時計を混同しない。rollback/DB不変/解放/同key retry/残接続も既存assertで成功 |
| 安全な追加診断 | 新規の対象Testcontainersだけで同一read-only観測SQLを実行しexit0 | `information_schema.INNODB_LOCK_WAITS`の件数取得成功。stderrのTLS/証明書時刻/認証失敗分類は再実行時いずれもfalse。初回stderrは既存テストが出さないため根因判定不能 |
| Python / Secret | Python29成功。実Gitleaks正常・異常セルフテスト成功 | 本書をstageした329 tracked files、例外0/未解決0。合成tokenは実scanner/CLIともexit1、redaction確認 |

補助証拠: `local-full-results.json`、`main-full-verify.log`、`targeted-timeout-safe.log`、`targeted-failure-probe.json`（監査用ローカルevidenceディレクトリ）。値を含むDB資格情報・JWTや生IPを追加診断に出さず、既存volumeには触れていない。今回の観測CLI失敗を、過去のHTTP6500ms超過や証明書時刻事象と同一根因とは断定しない。設定/timeout/pool/SSL検査の緩和、sleep追加、テスト変更、成功までのfull verify反復は実施していない。

監査結論は**CONDITIONAL GO提案 / PRODUCTION NO GO**。失敗・未検証・残余リスクを削除してGOへ見せない。

## 固定SHA参照

[src1]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L1

[src2]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/.github/workflows/ci.yml#L1

[src3]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/scripts/ci/quality_gates.py#L1

[src4]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/ci-quality-gates.md#L9

[src5]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/JwtUtil.java#L48

[src6]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/JwtUtil.java#L64

[src7]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/JwtAuthenticationFilter.java#L55

[src8]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/SecurityConfig.java#L33

[src9]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java#L142

[src10]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtNegativeTokens.java#L12

[src11]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtUtilTest.java#L73

[src12]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java#L244

[src13]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtUtilTest.java#L42

[src14]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtUtilTest.java#L58

[src15]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtUtilTest.java#L101

[src16]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java#L285

[src17]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/evidence/issue-123-demo.json#L1

[src18]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/demo.md#L17

[src19]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/scripts/docker/verify_image.py#L1

[src20]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/docker-runtime.md#L96

[src21]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/README.md#L110

[src22]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/.github/security/exceptions.json#L1

[src23]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/ProductNameValidationIT.java#L46

[src24]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/OrderItemLimitIT.java#L55

[src25]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java#L237

[src26]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/testing.md#L1

[src27]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java#L82

[src28]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/exception/ServerFailureResponse.java#L18

[src29]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/security/jwt-configuration.md#L1

[src30]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/auth.md#L42

[src31]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/README.md#L19

[src32]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/README.md#L46

[src33]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/release-status.md#L3

[src34]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/README.md#L114

[src35]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/release-status.md#L39

[src36]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/demo.md#L1

[src37]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/README.md#L6

[src38]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/release-status.md#L41

[src39]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/ci-quality-gates.md#L55

[src40]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/terraform/main.tf#L125

[src41]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/db/backup-restore.md#L49

[src42]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/scripts/db/backup.sh#L1

[src43]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/repository/BackupRestoreIT.java#L37

[src44]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/.github/workflows/deploy.yml#L1

[src45]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/deployment.md#L1

[src46]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/dto/ProductRequest.java#L14

[src47]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/dto/ProductUpdateRequest.java#L19

[src48]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/repository/ProductRepository.java#L17

[src49]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/RegistrationRateLimiter.java#L14

[src50]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/RegistrationSecurityIT.java#L56

[src51]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/auth.md#L175

[src52]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/dto/OrderRequest.java#L16

[src53]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/OrderService.java#L197

[src54]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/ProductPageableNormalizer.java#L10

[src55]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/ProductPagingIT.java#L48

[src56]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtDatabaseFailureTest.java#L32

[src57]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/resources/application-prod.properties#L1

[src58]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/DatabaseTimeoutIT.java#L154

[src59]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/db-lifecycle.md#L1

[src60]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/orders/inventory-concurrency.md#L1

[src61]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/release-status.md#L1

[src62]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/docker-runtime.md#L121

[src63]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/Dockerfile#L1

[src64]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/docker-runtime.md#L17

[src65]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docker-compose.yml#L1

[src66]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/terraform/variables.tf#L67

[src67]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/scripts/deploy.sh#L1

[src68]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/logging/ProductionLoggingIT.java#L53

[src69]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/terraform/provider.tf#L1

[src70]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/terraform/README.md#L1

[src71]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/exception/GlobalExceptionHandler.java#L36

[src72]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/api-errors.md#L1

[src73]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/pom.xml#L1

[src74]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/AuthDatabaseFailureIT.java#L49

[src75]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java#L67

[src76]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/terraform/variables.tf#L62

[src77]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/terraform/tests/host.tftest.hcl#L1

[src78]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/terraform/userdata.sh#L12

[src79]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/scripts/ci/quality_gates.py#L202

[src80]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/ci-quality-gates.md#L96

[src81]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/.gitignore#L1

[src82]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/scripts/ci/quality_gates.py#L385

[src83]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/service/ProductConcurrencyServiceTest.java#L23

[src84]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/repository/OrderCancellationTransactionTest.java#L39

[src85]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/OrderService.java#L385

[src86]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/OrderCsvExportIT.java#L76

[src87]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/scripts/db/restore.sh#L80

[src88]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/.github/workflows/ci.yml#L40

[src89]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/release-status.md#L66

[src90]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/JwtUtil.java#L23

[src91]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtConfigurationTest.java#L17

[src92]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/SecurityConfig.java#L24

[src93]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/service/OrderOwnershipServiceTest.java#L31

[src94]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/AuthorizationMatrixIT.java#L57

[src95]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/resources/db/migration/V1__baseline_schema.sql#L1

[src96]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/service/OrderServiceTest.java#L37

[src97]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/OrderService.java#L332

[src98]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/entity/OrderStateMachineTest.java#L13

[src99]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/OrderStateControllerTest.java#L33

[src100]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/ProblemDetailContractIT.java#L43

[src101]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/exception/SafeExceptionLoggingTest.java#L24

[src102]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/AuthService.java#L30

[src103]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/AuthApiIT.java#L57

[src104]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/repository/OrderRepository.java#L62

[src105]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/service/SalesServiceTest.java#L20

[src106]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/entity/OrderItem.java#L61

[src107]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/entity/Order.java#L98

[src108]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/MoneyApiIT.java#L44

[src109]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/OrderService.java#L120

[src110]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/resources/db/migration/V4__add_order_idempotency.sql#L1

[src111]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/IdempotentOrderIT.java#L48

[src112]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/ProductService.java#L29

[src113]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/InventoryService.java#L39

[src114]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/entity/Order.java#L24

[src115]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/entity/OrderItem.java#L15

[src116]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/repository/OrderAggregatePersistenceTest.java#L41

[src117]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/resources/db/migration/V6__add_inventory_adjustment_idempotency.sql#L1

[src118]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/config/ProductionConfiguration.java#L17

[src119]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/tls.md#L93

[src120]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/deployment/CaddyProxyIT.java#L19

[src121]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/deployment/DeploymentScriptTest.java#L12

[src122]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/ProbeHealthIT.java#L50

[src123]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/terraform/userdata.sh#L1

[src124]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/logging/RequestCorrelationFilterTest.java#L19

[src125]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/repository/InventoryConcurrencyMariaDbIT.java#L69

[src126]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/README.md#L1

[src127]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/initial-admin-provisioning.md#L1

[src128]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/resources/application.properties#L1

[src129]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/OrderService.java#L247

[src130]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/OsivApiIT.java#L54

[src131]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/OrderPagingIT.java#L70

[src132]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/OrderCsvWriter.java#L10

[src133]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/service/OrderCsvWriterTest.java#L14

[src134]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/ProductDeletionIT.java#L44

[src135]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/controller/OrderControllerTest.java#L33

[src136]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/exception/ApiProblemFactory.java#L13

[src137]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/LoginRateLimiter.java#L16

[src138]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/LoginClientAddressFilter.java#L19

[src139]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/LoginSecurityIT.java#L53

[src140]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/ProductionProfilesIT.java#L37

[src141]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/deployment/DockerRuntimeContractTest.java#L18

[src142]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/docker-runtime.md#L70

[src143]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/provisioning/InitialAdminProvisioner.java#L42

[src144]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/provisioning/InitialAdminLifecycleIT.java#L25

[src145]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/security/JwtAuthenticationFilter.java#L27

[src146]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/JwtPublicEndpointMatcherTest.java#L16

[src147]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/DemoApplication.java#L7

[src148]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/entity/Product.java#L16

[src149]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/service/OrderService.java#L56

[src150]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/.github/workflows/ci.yml#L14

[src151]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/github-actions-pinning.md#L1

[src152]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/main/java/com/example/demo/dto/RegisterRequest.java#L13

[src153]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L49

[src154]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L50

[src155]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L51

[src156]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L52

[src157]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L53

[src158]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L59

[src159]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L60

[src160]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L61

[src161]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L62

[src162]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L63

[src163]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L64

[src164]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L65

[src165]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L66

[src166]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L67

[src167]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L68

[src168]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L69

[src169]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L70

[src170]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L71

[src171]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L72

[src172]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L73

[src173]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L74

[src174]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L80

[src175]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L81

[src176]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L82

[src177]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L83

[src178]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L84

[src179]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L85

[src180]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L86

[src181]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L87

[src182]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L88

[src183]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L89

[src184]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L90

[src185]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L91

[src186]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L92

[src187]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L93

[src188]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L94

[src189]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L100

[src190]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L101

[src191]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L102

[src192]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L103

[src193]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L104

[src194]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L105

[src195]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L106

[src196]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L107

[src197]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L108

[src198]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-02-remediation-traceability.md#L109

[src199]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/audit/2026-10-12-dependency-advisories.md#L1

[src200]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/release-status.md#L26

[src201]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/scripts/docker/verify_image.py#L25

[src202]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/configuration.md#L77

[src203]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/configuration.md#L108

[src204]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/DatabaseTimeoutIT.java#L181

[src205]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/src/test/java/com/example/demo/security/DatabaseTimeoutIT.java#L378

[src206]: https://github.com/utl-flaxy/inventory-management-system/blob/f88bb8805e4fae0b0c97977d867ee39daac6a73c/docs/auth.md#L104

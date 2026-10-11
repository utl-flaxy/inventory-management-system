# CI品質ゲートとmain保護（Issue #80）

## 強制する検査と監査のみの検査

|正確なjob/check名|モード・失敗条件|対象/方式|
|---|---|---|
|`test`|必須候補。Maven verify失敗でjob失敗|Java 17 / Surefire + Failsafe / Testcontainers MariaDB。既存1014 + 737 = 1751件を削除・skipしない|
|`terraform`|必須候補。fmt/init/validate/mock test失敗でjob失敗|backend無効、lockfile readonly、AWS credential不要。mock 27件|
|`secret-scan`|必須候補。未承認findingでexit 1、実行/解析/対象不備でexit 2|Gitleaks 8.30.1のupstream default rules。Git管理ファイルを専用snapshotへコピーして検査|
|`java-static-analysis`|必須候補。SECURITY findingでexit 1、コンパイル/解析不備でexit 2|SpotBugs Maven Plugin 4.10.4.1 `check`、effort Max / threshold Low / SECURITY全priority / 許容違反0|
|`dependency-audit`|監査のみ。findingは明示してexit 0、scanner/SBOM/解析/coverage不備はexit 2|CycloneDX Maven Plugin 2.9.3の解決済み依存SBOMをOSV Scanner 2.6.0で検査。test/provided/systemも含む|

すべてのjobはPRとmain pushで実行し、path filterや`continue-on-error`で検査を省略しない。
検査の失敗を非ゼロexitへ伝播する。workflow自体のGREENは、依存脆弱性ゼロを意味しない。
main required checkへの実登録は**未実施**。jobの失敗と、GitHubがmergeを禁止する保護設定を区別する。

## 選定理由と権限

Gitleaksはprovider tokenに加えてgenericな認証情報候補もPR内で検査でき、外部API keyや有料機能を要しない。
GitHub secret scanning / push protectionは2026-10-12のread-only APIでenabledを確認した。
GitHub側は履歴/provider対応やpush時防御、GitleaksはPRのcurrent tree/default rulesと監査例外レビューに使う。
Git履歴全体・Git管理外のローカル`.env`/state/鍵はsnapshotの対象外。実secretは検査へ持ち込まない。
upstreamのbinary/text handlingを使うため、画像内/暗号化データ内のsecretを網羅したとは主張しない。

SpotBugsは既存MavenビルドとJava bytecodeで実行できる。SECURITY categoryに絞る理由はセキュリティ静的解析を独立gateとして採用するためであり、一般バグの大量警告をまとめて抑制するためではない。
全compiled application classを対象にし、解析class数0、入力classなし、reportなし、Errors/MissingClassありは失敗させる。
`check`を実行して違反を拒否するため、XML reportの生成だけをgate PASSと扱わない。
SECURITYにはSQLの非定数文字列などupstream built-inルールが含まれる。業務認可、依存脆弱性、全SASTルールを網羅するとは主張しない。
専用jobでcompileし、full verifyのテスト設定やapplication POMは変更しない。

workflow tokenは`contents: read`、すべてのcheckoutは`persist-credentials: false`。
`pull_request`を使用し、`pull_request_target`、AWS/OIDC権限、production secret取得、Deploy依存は追加しない。
fork PRもsecretなしで検査できる。Maven cacheはsetup-javaを利用し、`pom.xml`とwrapper propertiesをkey inputsにする。
Actionのcommit SHA固定は#82の責務で、今回は既存Actionの固定範囲を変更しない。

## スキャナー配布物と更新

Gitleaks [8.30.1公式release](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1)、OSV [2.6.0公式release](https://github.com/google/osv-scanner/releases/tag/v2.6.0)のx64配布物を使用する。
Linux/Windows用assetのSHA256を`quality_gates.py`へ固定し、ダウンロード後に照合してから実行可能化する。
2026-10-12にGitHub release asset APIのdigestと4配布物の固定値を再照合した。
Gitleaksは必要なarchive memberだけを読み、無制限なarchive展開はしない。改ざん、download失敗、未対応architectureはoperational failure。
これはreview済み配布物の改変防御であり、publisher signing/provenanceの検証とは別である。

version更新は専用PRで公式release・digest・変更ルールを再確認し、Python回帰、両実scannerの正常/違反fixture、full CIを実行する。
OSV DBはオンラインで更新されるので件数を137/66の固定成功条件にはしない。
Maven wrapper/依存resolutionとSpotBugs/CycloneDX plugin固定を維持する。Maven plugin自身の依存はこのSBOMの監査範囲に含まれない。

## Secret検出と限定例外

`.github/security/gitleaks.toml`はdefault rulesをextendし、global allowlistを追加しない。
inline `gitleaks:allow`とrepository `.gitleaksignore`は無効化し、黙って検出を消す経路を使わない。
Gitleaks stdout/stderrを通常のCIログへ出さず、redact=100のraw reportは一時directoryで処理して破棄する。
保存/表示するreportはpath、line、rule、承認済みfalse-positiveかどうか、件数だけで、Secret/Match/Entropy/Fingerprintを含めない。

今回再確認したfalse-positiveは次の4件。認証情報の値はここへ転載しない。

|対象|根拠|
|---|---|
|`docs/orders/idempotency.md:11`|公開HTTP例のIdempotency-Key。重複排除用識別子で、認証用secretではない|
|`README.md:617`|同じ公開Idempotency-Key例|
|`README.md:778`|末尾がellipsisの不完全なJWT例。署名済みの使用可能なtokenではない|
|`ProductionConfigurationTest.java:23`|application-context test専用の合成署名key。productionから取得していない|

例外は`.github/security/exceptions.json`の**exact path + line + rule + LF正規化したファイル全体SHA256**で限定する。
責任者はrepository maintainer、期限は2026-11-11（UTC日付で判定）。全例外は最大90日、期限当日から失敗する。
対象ファイルの変更、期限切れ、欠落、重複、symlink/外部pathは新レビューを要求する。
新しいcredentialを同じファイルへ加えるとhashが変わるため既存例外で通過しない。
実secretを発見した場合は値を公開せず、漏洩credentialの失効/rotationと露出範囲の調査を優先する。
例外の更新時は根拠・対象・所有者・期限をPRレビューし、検出fixtureが引き続き拒否されることを確認する。

## 依存監査と既知finding

[全66件の是正台帳](audit/2026-10-12-dependency-advisories.md)と[JSON証跡](audit/2026-10-12-dependency-advisories.json)を参照。
2026-10-12の再スキャンは137 packages、17 affected libraries、66 advisories（Critical 8 / High 22 / Moderate 28 / Low 8）、scanner exit=1。
wrapperはこの結果を`AUDIT ONLY / FINDINGS UNREMEDIATED`として報告してexit 0。**脆弱性がゼロというPASSではない。**
OSVにsuppression configやpackage overrideは渡さず、empty configと`--all-packages`を使用する。
scanner failure、不正JSON、空SBOM/空packages、exitとfindingの不一致、解決済みartifact/versionのcoverage不一致は失敗する。
Critical/Highの悪用条件をソースと照合し、productionでの成立を確認できないものは未確認を残す。
全66件はproduction JARに含まれるライブラリのfindingであり、test専用だから無視する扱いはしていない。
後続Issueは[Critical/High 30件 #86](https://github.com/utl-flaxy/inventory-management-system/issues/86)と[Moderate/Low 36件 #87](https://github.com/utl-flaxy/inventory-management-system/issues/87)。
更新は後続Issue/PRで行い、本PRはfindingの一括修正・承認済みrisk acceptanceを含まない。

各PRのartifactはhead SHAをnameに付け30日保存する。secretはvalue-free JSONのみ、staticはsummary/XML、dependencyはSBOM/raw OSV/summary。
`if: always()`のartifact保存がゲートの失敗を成功へ変更することはない。reportが存在しない場合も元stepのFAILを保持する。
長期保存が必要ならmaintainerが期限内にartifactを取得して組織の保存先へ保管する。

## 未導入の検査

- Docker image/OS package scanning: image構成の再現性・非root化は#76で検証する。対象image digestとbuild provenanceを確定してからscanner/failure policyを選ぶ。本PRでimageの安全性は検査していない。
- JaCoCo coverage閾値:既存1751件の実行は維持するが、coverage baselineと重要な未検証経路をまだ測定していない。任意の割合をgateに設定せず、coverageをPASSと報告しない。
- 広範囲SAST/CodeQL/FindSecBugs: SECURITY rulesとの効果・警告重複・運用費用を未評価。追加ツールを多数導入しない。
- Maven plugin/build tool依存、コンテナOS、本番の動的到達性は137件のapplication/test SBOMで網羅しない。

## main保護の管理者手順（未適用）

2026-10-12のread-only確認: `GET /repos/utl-flaxy/inventory-management-system/branches/main` は`protected=false`、`GET /rules/branches/main` は`[]`。
Deploy workflowは`disabled_manually`。本PRはこれらの設定を変更しない。

管理者はPR headの実CIが完了し、check名を確認した後、Settings → Rules → Rulesetsでbranch rulesetを作成する（利用可能なプランによってはSettings → Branchesのclassic protection）。

1. 対象branchを`main`、enforcementをActiveとし、PR経由の変更を必須化する。
2. author以外の承認を最低1件要求し、新commitで古い承認をdismiss、最終pushの承認、会話解決を必須化する。
3. required status checksにGitHub Actionsの正確な`test`、`terraform`、`secret-scan`、`java-static-analysis`を追加する。可能ならappをGitHub Actionsへ固定し、名前を他workflowで重複させない。
4. `dependency-audit`も実行/解析成功を強制したい場合はrequired checkに追加する。これは脆弱性ゼロ条件への変更ではない。
5. merge前に最新mainへup-to-dateであることを要求する。merge queueを使うなら別PRで`merge_group` event対応を整備してから有効化する。
6. force pushとbranch削除を禁止する。直接pushでPR/check要件を迂回できないようbypass listを空にし、管理者にも適用する。classicでは管理者bypassを禁止するoptionを有効化する。
7. 通常の管理者/automation例外は作らない。緊急例外は別管理者の承認、期限、理由、監査記録、復旧後のPR/CI検証を要し、管理者が実運用手順として決める。本PRは例外を登録しない。
8. 設定後read-only APIとUIを再確認し、失敗check/承認なしでmerge不可、直接push/force push/delete不可の運用確認を管理者が行う。実mainへ破壊的な試行はしない。

[GitHub公式branch protection](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)と[branch rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)を参照。
適用した管理者・日時・ruleset ID・check名・bypass方針を記録するまで、mainを保護済みと報告しない。

## ローカル/CIでの再検証

Java 17をJAVA_HOME/PATHで選択し、WindowsはMaven wrapperの`.cmd`を使う。Python 3.10以上が必要。

```powershell
python -m py_compile scripts/ci/quality_gates.py scripts/ci/tests/test_quality_gates.py
python -m unittest discover -s scripts/ci/tests -v
python scripts/ci/quality_gates.py download gitleaks --output target/quality-gates/gitleaks.exe
python scripts/ci/quality_gates.py secret-self-test --scanner target/quality-gates/gitleaks.exe
python scripts/ci/quality_gates.py secrets --scanner target/quality-gates/gitleaks.exe
python scripts/ci/quality_gates.py static-self-test
python scripts/ci/quality_gates.py static
python scripts/ci/quality_gates.py download osv --output target/quality-gates/osv-scanner.exe
.\mvnw.cmd --batch-mode --no-transfer-progress org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom '-DincludeTestScope=true' '-DincludeProvidedScope=true' '-DincludeSystemScope=true' '-DoutputFormat=json' '-DoutputName=bom' '-Dcyclonedx.skipAttach=true'
python scripts/ci/quality_gates.py dependencies --scanner target/quality-gates/osv-scanner.exe
.\mvnw.cmd verify
terraform fmt -check -recursive terraform
terraform -chdir=terraform init -backend=false -input=false -lockfile=readonly
terraform -chdir=terraform validate
terraform -chdir=terraform test -no-color
git diff --check
```

`secrets`はGit管理対象のworking treeを検査する。未追跡の新しい作業ファイルは提出前に明示的にstageしてから検査する（`.env`等をstageしない）。
secret-self-testは一時repositoryの合成tokenでscanner/同一workflow CLIがexit 1、redactionを確認する。
static-self-testはJava 17の正常SQL/非定数SQLを実SpotBugsで解析し、違反時Maven checkと同一workflow CLIがexit 1になることを確認する。
Python回帰はtool起動/timeout、checksum、期限/hash不備、report不備、空解析、違反、coverageとaudit policyを検証する。
scannerのoperational failureを意図的な違反として誤認しない。

実PR head SHA、GitHub CI URL/conclusion、全件数、job時間、Flyway warning実測値はPR本文に記録する。
ローカル結果だけでGitHub CI成功とは報告しない。Flyway 11.7.2 / MariaDB 12.2の既存warningは#69判断を維持し、更新/ログ抑止/volume操作は行わない。

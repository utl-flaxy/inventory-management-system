# CI品質ゲートとmain保護（Issue #80）

現行確認: main `5f0fae4ebd89754e23538cb09f97a1ff037255ed` / 2026-10-18。
[main CI 39109302086](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39109302086)で以下5ジョブSUCCESS。
Java2,176（1,314+862、F/E/S=0）、Python29、Terraform mock27、Secret例外0/未解決0、SpotBugs違反0。
OSV169 components/0 advisory、Trivyは別のOS49（MEDIUM27/LOW22）/library0。
[現行構成・保証限界](release-status.md)を参照。Issue80当時の1751件・137/66監査は歴史として後段に保持する。

## 強制する検査と監査のみの検査

|正確なjob/check名|モード・失敗条件|対象/方式|
|---|---|---|
|`test`|必須。Maven verify/Docker検証失敗でjob失敗|Java17 / Surefire1,314 + Failsafe862 = 2,176 / Testcontainers MariaDB。削除・skipしない|
|`terraform`|必須。fmt/init/validate/mock test失敗でjob失敗|backend無効、lockfile readonly、AWS credential不要。mock27|
|`secret-scan`|必須。未承認findingでexit1、実行/解析/対象不備でexit2|Gitleaks8.30.1のdefault rules。Git管理ファイルの専用snapshotを検査|
|`java-static-analysis`|必須。SECURITY findingでexit1、コンパイル/解析不備でexit2|SpotBugs Maven Plugin4.10.4.1 check、effort Max / threshold Low / SECURITY全priority / 許容違反0|
|`dependency-audit`|監査実行の成功が必須。findingは明示してexit0、scanner/SBOM/解析/coverage不備はexit2|CycloneDX2.9.3 / OSV2.6.0。test/provided/systemも含む。脆弱性ゼロ条件ではない|

すべてのjobはPRとmain pushで実行し、path filterや`continue-on-error`で検査を省略しない。
検査の失敗を非ゼロexitへ伝播する。workflow自体のGREENは、依存脆弱性ゼロを意味しない。
上記5件はmain required checksへ登録済みで、strict=true、管理者適用、linear history、force push/delete禁止を確認。
required approving reviewsは0。独立レビューは人手の運用であり、必須1承認が実設定されているとは扱わない。

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
Actionの40桁SHA固定は#82で導入済み。[更新方針](github-actions-pinning.md)に従い、本PRではworkflowを変更しない。

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

AUD-01 / Issue #104 / PR #105で、2026-11-11に失効する4例外を期限延長せず撤廃済み。
公開Idempotency-Key/JWT例は表示用プレースホルダーへ置換済みで、ProductionConfigurationTestの秘密値もテスト専用の実行時生成値へ変更済み。
APIのkey制約、productionのUTF-8最低32bytes検証、ログ非露出のテスト、scannerの検出ルールは変更しない。

原則は**例外0件**。`.github/security/exceptions.json`は `{"secret_false_positives": []}` とし、実スキャンのfindings/未解決ともに0件を要求する。
Python回帰でもこのrepositoryの空ポリシーと、元の失効日2026-11-11および以後の日付で正常に読めることを検証する。
README編集ごとの既存例外hash更新や定期的な期限延長は不要になる。

将来やむを得ず真の誤検知を申請する場合、原因を除去できない根拠とポリシー・回帰テストの変更を同じPRで明示してレビューする。
例外は**exact path + line + rule + LF正規化したファイル全体SHA256**、reason・owner・expiresを必須として限定する。
全例外は最大90日、期限当日から失敗する（UTC日付で判定）。
対象ファイルの変更、期限切れ、欠落、重複、symlink/外部pathは新レビューを要求する。
新しいcredentialを同じファイルへ加えるとhashが変わるため既存例外で通過しない。
実secretを発見した場合は値を公開せず、漏洩credentialの失効/rotationと露出範囲の調査を優先する。
例外の追加・更新を検討する際も根拠・対象・所有者・期限をPRレビューし、実secret-self-testの異常fixtureが引き続き拒否されることを確認する。

## 依存監査と既知finding（2026-10-12当時の記録）

以下の137/66は導入時の記録。現行mainの169/0、image OS49とは対象・時点が異なる。

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

- JaCoCo coverage閾値:実行件数はcoverageではない。baselineと重要な未検証経路の比率を測定しておらず、任意の割合をgateに設定しない。
- 広範囲SAST/CodeQL/FindSecBugs: SECURITY rulesとの効果・警告重複・運用費用を未評価。追加ツールを多数導入しない。
- Maven plugin/build tool依存、本番の動的到達性はapplication/test SBOMで網羅しない。コンテナOSは#76導入済みのTrivy auditで別に検査し、現行49件が残る。

## main保護の確認と管理者手順

2026-10-18のread-only APIでは冒頭の5 checks / strict / 管理者適用 / linear history / force push/delete禁止を確認済み。
Deployはdisabled_manually、必須review数は0。本PRは設定を変更しない。
以下はIssue80時点の未適用記録と、運用者が再設定・強化を判断するための手順であり、すべて実設定済みという意味ではない。

2026-10-12のread-only確認: `GET /repos/utl-flaxy/inventory-management-system/branches/main` は`protected=false`、`GET /rules/branches/main` は`[]`。
Deploy workflowは`disabled_manually`。本PRはこれらの設定を変更しない。

管理者はPR headの実CIが完了し、check名を確認した後、Settings → Rules → Rulesetsでbranch rulesetを作成する（利用可能なプランによってはSettings → Branchesのclassic protection）。

1. 対象branchを`main`、enforcementをActiveとし、PR経由の変更を必須化する。
2. author以外の承認を最低1件要求し、新commitで古い承認をdismiss、最終pushの承認、会話解決を必須化する。
3. required status checksはGitHub Actionsの正確な`test`、`terraform`、`secret-scan`、`java-static-analysis`を維持する。名前を他workflowで重複させない。
4. `dependency-audit`も現行required checkとして維持する。これは脆弱性ゼロ条件への変更ではない。
5. merge前に最新mainへup-to-dateであることを要求する。merge queueを使うなら別PRで`merge_group` event対応を整備してから有効化する。
6. force pushとbranch削除を禁止する。直接pushでPR/check要件を迂回できないようbypass listを空にし、管理者にも適用する。classicでは管理者bypassを禁止するoptionを有効化する。
7. 通常の管理者/automation例外は作らない。緊急例外は別管理者の承認、期限、理由、監査記録、復旧後のPR/CI検証を要し、管理者が実運用手順として決める。本PRは例外を登録しない。
8. 設定後read-only APIとUIを再確認し、失敗check/承認なしでmerge不可、直接push/force push/delete不可の運用確認を管理者が行う。実mainへ破壊的な試行はしない。

[GitHub公式branch protection](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)と[branch rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)を参照。
現行値はread-only APIの確認時点の証拠。管理者の適用日時・全bypass主体・実際の拒否動作までこのPRで新たに監査したとは主張しない。

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
ローカル結果だけでGitHub CI成功とは報告しない。#69のFlyway11.7.2検討は履歴として保持し、現行Flyway12.4.0 / MariaDB12.2のWARNも隠さない。更新/ログ抑止/volume操作は行わない。

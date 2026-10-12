# GitHub ActionsのSHA固定と更新手順 — Issue #82

対象main: `306a3c42d118e13e1cfebb7048284428e84f57c5`。確認日: 2026-10-12。
2つのworkflowにある全14参照を、公式repositoryの正式releaseの40桁commitへ固定した。
確認時点の旧tagが解決するcommitと新SHAは全5種類で一致するため、Actionのバージョン、
入力、権限、ジョブ、トリガー、タイムアウト、キャッシュ設定は変更していない。
Spring Boot依存関係、アプリケーション、DB migration、Terraform、deployment scriptも変更していない。

## 全参照の棚卸し

| 公式repository / 発行元 | 旧tag | 正式release / versionコメント | 固定commit SHA | 参照数 (CI / Deploy) |
| --- | --- | --- | --- | --- |
| [actions/checkout](https://github.com/actions/checkout) / GitHub | v7 | [v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) | `3d3c42e5aac5ba805825da76410c181273ba90b1` | 5 / 1 |
| [actions/setup-java](https://github.com/actions/setup-java) / GitHub | v6 | [v6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1) | `de7274f081f381c8f8158605e0321c36c376e2e6` | 3 / 0 |
| [actions/upload-artifact](https://github.com/actions/upload-artifact) / GitHub | v7 | [v7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` | 3 / 0 |
| [hashicorp/setup-terraform](https://github.com/hashicorp/setup-terraform) / HashiCorp, Inc. | v4.0.1 | [v4.0.1](https://github.com/hashicorp/setup-terraform/releases/tag/v4.0.1) | `dfe3c3f87815947d99a8997f908cb6525fc44e9e` | 1 / 0 |
| [appleboy/ssh-action](https://github.com/appleboy/ssh-action) / Bo-Yi Wu (appleboy) | v1.2.0 | [v1.2.0](https://github.com/appleboy/ssh-action/releases/tag/v1.2.0) | `7eaf76671a0d7eec5d98ee897acda4f968735a17` | 0 / 1 |

機械可読の一次確認記録は [issue-82-actions.json](audits/issue-82-actions.json)。
正式releaseの公開日時・author、publisher、全workflowの行番号、API URL、commit URL、
Action定義URL、tag object type、署名状態、取得した証跡ファイルのSHA-256を記録する。
発行元とreleaseを作成したbot/ユーザーは別々に記録している。
すべて正式公開済みで、draft / prereleaseではない。

## SHAの取得・確認

各公式repositoryについて、GitHub REST APIのrelease、旧tag、新しい完全なrelease tag、
commit objectを照合した。別途 `git ls-remote https://github.com/OWNER/REPO.git refs/tags/OLD refs/tags/RELEASE`
でも同じcommitを確認した。`target_commitish` のbranch名だけではSHAを採用しない。
今回はすべてtag refのobject typeが `commit`。annotated tagの場合は `git/tags/{sha}`
を辿り、最終commitまでdereferenceする必要がある。

公式commitの `action.yml` とruntimeを確認した。GitHub 3種類とHashiCorpはNode 24、
ssh-actionはcompositeで、追加の `uses:` はない。署名はGitHub 3種類とssh-actionが
GitHub API上でverified、HashiCorpはunsigned。unsignedを署名検証成功と記載せず、
公式repository・正式release・tag/commit一致を根拠として既存バージョンを固定した。
これは発行元のコード全体や配布バイナリに悪意がないことの証明ではない。

GitHub公式の [Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)
に沿って、可変tagやbranchへ戻さず、各行に正確な `# vX.Y.Z` を付ける。
SHA固定後にupstream tagが動いても、このworkflowが取得するAction commitは変わらない。

## 維持するCI・Deploy契約

- workflow / job権限は `contents: read`、checkoutはすべて `persist-credentials: false`。
- PR CIにAWS/EC2/DB secretを渡さず、`pull_request_target` を導入しない。
- 必須5ジョブ: `test`, `terraform`, `secret-scan`, `java-static-analysis`, `dependency-audit`。
- Terraformはbackend無効のinit / validate / mock testのみ。実AWSのplan / applyは行わない。
- DeployはGitHub側の `disabled_manually` を維持。YAML編集による再有効化は行わない。
- `workflow_run` のsuccess + push + main条件、production concurrencyと取消禁止を維持。
- runnerのcheckout refと `DEPLOY_SHA` は検証済みCI runの `head_sha`。
  deployment scriptの同一SHA確認・限定的app更新・health check・rollbackを維持。
- scannerの実行エラーや異常系を成功扱いにせず、skip / suppressionを追加しない。
  OSV auditのジョブ成功は脆弱性ゼロを意味しない。

## 更新運用

repository maintainerは毎月、およびupstreamのsecurity release告知時に公式releaseを確認する。
各更新を独立したレビュー用PRとして提示し、確認前にmainへ反映しない。auto-mergeは使用しない。
更新PRは `uses:` とversionコメント、監査記録、必要な互換性対応だけを変更する。
テストのreviewed inventoryとworkflowが一致する必要がある。

Dependabotの `github-actions` ecosystemはSHA固定と併用できる
([公式説明](https://docs.github.com/en/actions/reference/security/secure-use#using-dependabot-version-updates-to-keep-actions-up-to-date))。
今回は既存設定がなく、最小変更として新規bot設定は追加しない。
採用する場合もPR提案だけを行い、以下の人手確認・全CI合格を必須にする。
Dependabotが提案したSHAやversionコメントを、正式release確認なしに採用してはならない。
Deployのdisabled状態や必要なsecretのないPR CIを、自動更新のために変更しない。
SHA固定ActionはDependabot alertsの対象にならないため、bot設定の有無にかかわらず
公式security advisoryを手動で確認する（同じGitHub公式説明の制限事項）。

更新PRの本文に次の項目を揃える:

1. Actionの公式repository / publisher、旧SHA・version、新SHA・version。
2. 正式release URL / release note、変更理由、公開日時、draft/prereleaseでないこと。
3. tag dereference結果、commit URLと署名状態、Action定義・runtime・配布物の変更点。
4. 影響を受ける全workflow / job / step、新しいpermissionや入力の要否。
5. YAML/契約テスト、Java full verify件数、Python、Terraform mock、全品質ゲート、
   対象head SHAに対する実GitHub CI URL / job結果。
6. Deployへの影響、disabledの維持、実Deploy未実行の範囲と再有効化前に必要な検証。
7. rollback: このPRのworkflow / inventory更新をrevertし、前のreviewed SHAを復元する。
   アプリ・DB・migrationの変更は伴わない。緊急時も可変tagへ戻さない。
8. 残余リスクと未解決の互換性問題、担当者 / 期限。

検証時は `./mvnw verify`（Windowsでは `.\\mvnw.cmd verify`）を実行する。
`ReviewedActionsTest` が全workflowをYAMLとして読み、全参照のpublisher・40桁SHA・
正式versionコメントを監査記録と照合し、可変tag・短縮SHA・未確認SHA・未確認publisherを拒否する。
既存Deployment/Terraform契約テストで、認可境界・SHA伝播・rollbackを引き続き検証する。
GitHub側のDeploy状態は `gh api repos/utl-flaxy/inventory-management-system/actions/workflows`
で確認する。Deployを実行して状態を確認してはならない。

## 残余リスク

SHA固定が保護するのはAction repositoryのcommitであり、runner image、toolchain配布物、
ネットワーク先やAction内部のダウンロードを一括固定するものではない。
ssh-action v1.2.0の [entrypoint.sh](https://github.com/appleboy/ssh-action/blob/7eaf76671a0d7eec5d98ee897acda4f968735a17/entrypoint.sh)
は既定でdrone-ssh v1.8.0のrelease assetをHTTPS取得し、checksum検証を行わない。
今回は元の動作を維持した。Deploy再有効化前にはasset検証・SSH host fingerprint等を別途評価する。
Deployの実行互換性は静的契約までの検証で、EC2への実行実績を主張しない。
HashiCorp commitはunsigned。main保護・Actions許可ポリシーの管理者設定は変更していない。
現mainの依存脆弱性8件（Critical 2 / High 0 / Moderate 5 / Low 1）は別Issue #89/#87の対象で、
このSHA固定PRで解消したことにはしない。

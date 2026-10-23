# TF-C準備: AWS無接続のsynthetic root

[Issue #150](https://github.com/utl-flaxy/inventory-management-system/issues/150) /
[親Issue #75](https://github.com/utl-flaxy/inventory-management-system/issues/75)。
基準main `1e97eaed2012f0c7e040d35140624639fca17ef4`、2026-10-22。
[TF-A ADR](../docs/design/terraform-remote-state.md)、[TF-B](../terraform-bootstrap/README.md)、
[TF-C0審査](../docs/operations/terraform-remote-state-preflight-2026-10-21.md)を引き継ぐ。
履歴のB01〜B14 / T01〜T17を遡及変更しない。

**TF-C実行BLOCKED / 実AWS受入0件 / Issue #75 OPEN / PRODUCTION NO GO。**
このrootのCI成功はAWSの利用許可・IAM認可・排他・復旧の実証ではない。

## 実行境界

- Terraform **1.15.6**、組込`terraform_data.fixture` 1個だけ。AWS resource / module / data source / provisionerは0。
- [HashiCorpの仕様](https://developer.hashicorp.com/terraform/language/v1.15.x/resources/terraform-data)に従い外部providerは不要。provider lockfileを持ち込まない。host/bootstrapは既存の同一review済みAWS provider lockを維持する。
- [backend.tf.json](backend.tf.json)はS3部分設定の`use_lockfile=true`と`encrypt=true`のみ。実bucket/key/region/role/credentialを埋めない。これだけではremote initできない。
- mainはJSON HCL。入力は公開dummy labelと0〜2の整数だけ。認証情報、既存stateや業務データを入力しない。
- [8テスト](tests/contracts.tftest.json)は全て明示した`command=plan`。[Terraform testの既定はapply](https://developer.hashicorp.com/terraform/language/tests)なので省略も禁止する。3正常系＋5入力拒否のrun名・入力・期待失敗変数を固定し、総数8件を保った置換も拒否する。S3接続もresource stateの保存も行わない。
- [静的capability検査](../scripts/ci/terraform_synthetic.py)は限定JSON構文のallowlistであり一般HCL parserではない。file関数、外部source、provider、provisioner、別test fileの追加を拒否する。拡張にはコードレビューが必要。

repository rootから次だけを使う。先に変更をGit indexへstageする（exportはworking treeでなくindexが対象）。

```bash
python3 -m unittest discover -s scripts/ci/tests -v
python3 scripts/ci/terraform_offline.py
python3 scripts/ci/terraform_preflight.py
```

Windowsは`python3`を使用するPython 3実行ファイルへ読み替える。
最後のcommandの**正常な拒否はexit 1 / decision BLOCKED**。入力不正・読取失敗はexit 2で、exit 0 / READY経路はない。
`-h` / `--help`も未対応引数としてexit 2で拒否する。実CLI subprocessで正規/不正fixtureの終了コードを検証する。
`|| true`等で実行許可として扱わない。CIは公開拒否記録の静的妥当性と拒否経路を検査して成功する。

offline runnerはGit indexの公開sourceだけを新規tempへexportし、private/generated pathは本文を読む前に拒否する。
新rootの追加fileもexport前に拒否する。既存state/tfvars/cacheを探索・コピーしない。
AWS/TF/TFC/HCP環境入力を除去し、空AWS config/credentials・空Terraform CLI config・metadata無効・新規provider cacheを指定する。
CLI操作はfmt-check、**`init -backend=false -input=false -lockfile=readonly`**、validate、限定testとversion照合だけ。
remote init、standalone plan/apply/state/destroy、自由なCLI引数を受け付けない。
host27＋bootstrap14＋synthetic8を正確に確認し、既存の3変異がassertionで失敗することも要求する。0件・provider異常を否定系成功に数えない。
このsource制約と環境隔離はネットワークsandbox/DLP全般の代替ではなく、任意のTerraform commandを安全にするものではない。

## 公開fail-closedゲート

[preflight.public.json](preflight.public.json)は**拒否の公開fixture**で、実承認票ではない。
全B01〜B14がBLOCKED、全T01〜T17がNOT_RUN、C01/C02/C05がBLOCKEDの完全な集合だけを受理する。
空集合、欠落、重複JSON key、部分/全部READY、架空の承認番号、未実行PASS、未知field、state本文、credential fieldを拒否する。
実account、bucket、ARN、個人名、内部承認票を収集するfieldもCLI optionもない。入力値をエラーへechoしない。

ゲートはcommandを呼ばず、AWS実行経路を一切持たない。将来の実承認の真正性を検証したとは主張しない。
B10のsource準備が進んでも、実barrier/abort/watchdog、所有者、環境、費用、cleanup、独立承認は未確認なので公開B10もBLOCKEDのまま。
実承認は別の私有審査と明示的な実行許可が必要。fixtureを書き換えることで解除する仕組みにはしない。

## 未解決の開始前条件と安全な提案

以下は実装権限の追加ではない。既存bootstrap HCL / IAM / KMS principalを変更していない。
いずれも公開ゲートでRESOLVEDへ変更すると否定系テストが拒否する。

|停止点|開始前に別承認する提案|停止/否定条件と復旧の限界|
|---|---|---|
|C01 cleanup|作成前に私有の新規sandbox allowlist、保護解除の一時差分、専用session、期限、費用/残置所有者を二者承認する。writer停止→復旧copy/証拠保全→独立監査継続→対象を限定した保護解除とversion整理→bucket等の終了→鍵の利用終了確認→鍵削除猶予後まで追跡、の順序案をレビューする|通常destroyは`prevent_destroy`、削除deny、権限不足で完結しない。全体Delete許可や`force_destroy=true`で回避しない。暗号化backup/監査の保持要件と削除が矛盾する間は資源作成を始めない。猶予中のKMSは復号不能となるので先にscheduleしない|
|C02 KMS復旧|management roleの存在/trust/SSO/MFA/session/SCPと独立したbreak-glass経路を所有者が確認する。既存policyの安全な再設定・復号継続を将来のT02/T11で測定する。役割追加が必要なら別の最小構成PRを先行する|単一management role喪失後にrootで必ず復旧できると仮定しない。DisableKey、管理権限の剥奪、lockout検査bypassを故障注入に使わない。承認済み一時sessionの限定denyと元に戻る期限で試験し、失敗時はwriter停止・独立復旧者へ引継ぐ|
|C05 CloudTrail digest|下記のtool/source API要件、prefix、region、KMS復号、観測時間窓、gap検出と独立監査の権限を私有審査する。最小権限に合う別tool/改修も比較する|TF-Bの設定flagや配送だけでdigest PASSにしない。Location/PublicKeys権限とListのprefix整合が未解決。権限を広げて通さずT14はNOT_RUNのまま|

### C05の資料上の追加確認（実APIは未実行）

2026-10-23の[固定CLI source / IAM対応表・A/B案の設計追補](../docs/audit/cloudtrail-digest-c05-design-2026-10-23.md)で、下記の確認を詳述する。C05とTF-Cは引き続きBLOCKED。

比較対象を**AWS CLI 2.31.18**に固定してsourceを読む。最新版・本番推奨版の認定ではなく、将来の採用版では再評価する。
公式tagはcommit **`f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc`**（GitHub公開tag objectも照合）。CLIのinstall/実行、AWS接続はしない。

- [公式手順](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-log-file-validation-cli.html)はオンライン接続とS3 List/Get/Locationを要求し、digestが参照するlogだけが検証対象。
- [固定source validation.py](https://github.com/aws/aws-cli/blob/f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc/awscli/customizations/cloudtrail/validation.py)の`S3ClientProvider`はGetBucketLocation、`DigestTraverser`等はGetObject、`DigestProvider.load_digest_keys_in_range`はListObjects（Marker付き、**Prefixなし**）を呼ぶ。
- [utils.py](https://github.com/aws/aws-cli/blob/f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc/awscli/customizations/cloudtrail/utils.py)はregion別ListPublicKeysを呼ぶ。bucket未指定時はDescribeTrails、organization trailの自動解決ではDescribeOrganizationもあり、採用する引数/用途別に追加審査が必要。
- [TF-B audit policy](../terraform-bootstrap/iam.tf)のListAuditは`s3:prefix`条件付き。上記版のPrefixなしListはそのallow条件と整合しない。GetBucketLocation / CloudTrail APIのgrantもTF-B内にはない。**これはsource上の不足/不整合であり、外部roleの実効権限を断定した実験ではない**。
- 広いList権限の即時追加は採用しない。prefix付き列挙を保つ検証toolの別レビュー、対象audit専用bucketのmetadata列挙だけを許す場合のリスク審査、CMK復号の実測を残す。今回policyは無変更。

## 保証しないこと

実S3の同時lock/unlock、条件付きwrite、IAM allow/deny、KMS復旧、CloudTrail配送/digest、version復旧、費用とcleanupは未実行。
mock/plan/公開ゲートでは承認後のTF-Cを代替できない。既存local/remote stateの読取・移行TF-Dも対象外。
Issue #145のWindows初回起動/証明書/CLI不安定の根因未確定、Issue #134のjti保留、OS31件、Flyway WARN、異版binary rollback未検証を維持する。
Deploy disabled、PR #103対象外、実利用者向け本番運用NO GOを変えない。

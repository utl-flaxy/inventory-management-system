# Remote state bootstrap（Issue #75 / TF-B）

このrootは[採択済みADR](../docs/design/terraform-remote-state.md)の**静的構成**。
Terraform1.15.6 / AWS provider6.68.0、hostの`terraform/`とは独立している。
AWS resource作成・実plan/apply・remote init・既存stateの確認/移行は実施していない。
Issue #75はOPEN、実利用者向け本番運用は**NO GO**。

## 構成と権限

|対象|構成 / 境界|
|---|---|
|state bucket|環境・account・region別の名前。Versioning、4種のPublic Access Block、BucketOwnerEnforced、SSE-KMS、TLS必須|
|state object|`inventory/<environment>/<region>/host/terraform.tfstate`。通常roleはGet/Put。bucket policyとidentity policyでDelete/DeleteVersionを拒否|
|native lock|state keyに`.tflock`を付加。通常roleはGet/Put/Delete、永久version削除は拒否。lockの年齢による自動削除なし|
|workspace|defaultのみ。ListBucketはstate keyと予約workspace prefix末尾`/`の**完全一致**。非default object操作は明示的に拒否|
|KMS|stateとauditで別のCMK、rotation有効、削除猶予30日、policy lockout検査有効。Bucket Keysは無効にしてobject暗号contextを限定|
|audit|別S3/CMK。CloudTrailはmulti-region、management/KMS eventとstate bucketの全read/write data events、log file validationを有効化する定義。audit bucketのdata eventsは再帰記録しない|
|削除保護|両bucketの`force_destroy=false`、bucket/CMK/trailに`prevent_destroy`。state/logの削除をbucket policyで拒否。expiry/Lifecycle/Object Lockは追加しない|

通常operatorのKMS権限は、state CMKのEncrypt/Decrypt/GenerateDataKeyだけ。
`kms:ViaService`は同region S3、encryption contextはstate/lockの正確なobject ARNに限定する。
CloudTrailによるaudit暗号化はSourceArnをhome regionの正確なtrail ARNへ固定する。
公式policyに合わせ、multi-regionのencryption contextはregionだけをwildcardにし、accountとtrail名は固定する。audit復号も同じcontextへ限定する。
key policy内の`Resource="*"`は**そのCMK自身**を表す。identity policyのKMS ResourceはCMKの正確なARN。

通常unlockとforce-unlockは同じDeleteObject権限を使う。IAMだけで承認の有無を判定できない。
state Get権限は機密snapshot全体の閲覧権限になる。PR reviewerやアプリへ配布しない。

## 既存roleを用いる理由と前提

このrootはroleや信頼policyを新設せず、**事前承認済みの4 role**へ限定したinline backend policyを付与する。
SSO/MFA・短期session・誰がAssumeRoleできるかは組織側の設定と別レビューが必要。
account全体のrootを信頼する新role、長期access key、CI用OIDC/credentialは追加しない。

|role名（承認済みaccount内）|権限と責務|
|---|---|
|`inventory-<environment>-state-management`|bootstrap実行・S3/IAM/trail管理。providerはこのroleをAssumeRoleする。CMK policyの管理権限を持つが直接Decryptは付与しない|
|`inventory-<environment>-state-operator`|host backendの通常操作。host resource作成用の権限はこのrootに含めない|
|`inventory-<environment>-state-recovery`|通常操作に加え、state keyのversion一覧/取得。常時利用させず、承認後の短期sessionで運用|
|`inventory-<environment>-state-audit`|audit prefixの一覧・version取得とaudit CMKの復号。state操作権限なし|

`roles`入力はaccount・environment・用途別role名・相互分離を検証する。IAM role pathは許可する。
providerにも`allowed_account_ids`を設定する。別account/環境のrole、共有role、異なるpartitionをmock否定系で拒否する。
これは入力の整合検査であり、運用者が**正しい環境自体を選択したこと**の証明にはならない。承認票との二者照合が必要。

management roleの**初回provisioning用identity policyとtrust policyは外部前提**。
対象S3名、二つのCMK、trail、3 roleのinline policyだけを管理できる権限をTF-C前に別レビューする。
CMK作成時の実行主体はこのmanagement roleで、key policyのPutKeyPolicy権限を維持する。
role不在/AssumeRole不能/鍵のpolicy-lockout検査失敗は停止条件であり、検査を無効化しない。
managementはpolicy変更権限を持つ特権主体であり、将来の権限昇格をこの構成だけで防げるとは主張しない。
他の既存identity policy、permissions boundary、SCP、account全体のPublic Access BlockもTF-Cで確認する。
account全体の設定変更は、他用途へ影響するためこのrootに含めない。

## state循環依存とprivate情報

bootstrap自身にremote backend宣言はない。初回は承認済みの暗号化私有directory・backup・責任者が必要。
作成予定bucketを初回backendに指定しない。bootstrap stateを後でremote化する際も独立した承認・移行が必要。
host rootからmoduleとして呼ばず、hostのstateへ混ぜない。

`backend_coordinates`出力は**非credentialの座標**だけ。自動でbackend initへ渡さない。
実値入りtfvars/backend設定はcheckout外で保管し、ログ/plan/cache/artifactへ公開しない。
hostの部分設定で`use_lockfile=true`・`encrypt=true`を固定し、外部設定やCLIで上書きしない。
外部設定はbucket/key/region/kms_key_id/allowed_account_ids/workspace_key_prefixのみを二者確認する。
backend設定は`var`/locals/resourceを参照できず、このrootのoutputsをHCL参照で直結できない。

## AWSを使わない検証

repository rootで、追加/変更した**公開ソースだけをindexに登録**して実行する。
既存state/tfvars/`.terraform`を読んだりコピーしたりしない。

```bash
python3 -m unittest discover -s scripts/ci/tests -v
python3 scripts/ci/terraform_offline.py
```

runnerはGit index内の両rootと`scripts/tls/install-caddy.sh`だけを新規directoryへexportする。
AWS認証・TF変数/CLI override/traceを継承せず、AWS config/credentials/CLI configは空の専用file、metadata認証は無効。
各rootでfmt、**`init -backend=false -input=false -lockfile=readonly`**、validate、plan-only mock testを行う。
host27件は変更しない。bootstrap14件は権限・storage・KMS・audit・座標と不正入力を検証する。
さらに使い捨てコピーへstate Delete許可/KMS権限欠落/management監査無効化を一つずつ入れ、**テストassertionで失敗**することを必須にする。
provider失敗やinit失敗は否定系の成功に数えない。共有ソースへmutationを残さない。
Git強制追加されたstate/private入力は、Secretゲートとexportの両方が**本文を読む前に**拒否する。
Gitleaksの実scanner正常/異常検証と例外0件は別途維持する。

## 実AWSで未検証・次の承認境界

- 実roleのtrust/最小provisioning権限・key policy更新可能性、SCP、KMS context、wrong-key/non-TLS拒否、account確認。
- 実S3のIf-None-Matchによる同時lock、unlock、異常時fail-closed、予約workspace Listと非default書込み拒否。
- CloudTrailの配送・digest検証・監査欠落検知、KMS障害・version復旧、bootstrap state喪失からの復旧。
- S3名の取得可否、CloudTrail重複/費用、保持・backup・RPO/RTO、担当/独立承認者。30日はKMS削除猶予であり保持/RPOの合意ではない。

以上はTF-Cの**別承認された隔離AWS/synthetic state**で確認する。実state移行はさらにTF-Dで別承認する。
config削除や特権policy変更で`prevent_destroy`等は迂回できる。versioningとlog validationもimmutable backupの代替ではない。
本構成をmergeしても運用準備完了とはしない。[freeze/backup/復旧手順](../docs/operations/terraform-remote-state.md)を維持する。

根拠（2026-10-21確認）:
[Terraform1.15 S3 backend](https://developer.hashicorp.com/terraform/language/v1.15.x/backend/s3)、
[AWS provider6.68 CloudTrail](https://github.com/hashicorp/terraform-provider-aws/blob/v6.68.0/website/docs/r/cloudtrail.html.markdown)、
[CloudTrail S3 policy](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/create-s3-bucket-policy-for-cloudtrail.html)、
[CloudTrail KMS policy](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/create-kms-key-policy-for-cloudtrail.html)。

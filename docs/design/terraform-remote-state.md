# ADR: Terraform remote state・排他・復旧（Issue #75 / TF-A）

状態: **設計提案・独立レビュー待ち**。2026-10-21。
[TF-A指示](https://github.com/utl-flaxy/inventory-management-system/issues/75#issuecomment-6315668832)に対応する。
基準mainは`ecf10462b145bbb8fd2d7a5c4a7ed51d323c71f6`。
本変更は文書のみ。backend/resource/IAM/CI設定を追加せず、AWS・既存state・secretにはアクセスしない。
**Issue #75はOPEN、実利用者向け本番運用NO GOを維持する。** 本ADRのmergeは実装・移行・実AWS操作の承認ではない。

## 1. 現状と判断の根拠

|現mainの正本|確認したこと / 未保証事項|
|---|---|
|[provider.tf](../../terraform/provider.tf)|CLIは`= 1.15.6`、AWS provider制約`~> 6.0`。backend宣言なし。既定local backend|
|[provider lockfile](../../terraform/.terraform.lock.hcl)|解決版6.68.0と配布checksum。これは**依存の固定でありstate排他ではない**|
|[variables](../../terraform/variables.tf)・[outputs](../../terraform/outputs.tf)・[main](../../terraform/main.tf)|host/network/Caddyの入力・定義。backend用入力なし。出力はinstance ID/EIPで、secret input/outputを新設していない。ただしstate全体を公開可能とは扱わない|
|[Terraform README](../../terraform/README.md)|local stateと共有lock未実装を明示。既存state/tfvarsを検証へ持ち込まない方針|
|[mock tests](../../terraform/tests/host.tftest.hcl)・[CI](../../.github/workflows/ci.yml)|mock provider、全27 runはplanのみ。`init -backend=false -input=false -lockfile=readonly`、AWS credentials不要|
|[root ignore](../../.gitignore)・[Terraform ignore](../../terraform/.gitignore)|state/backup、tfvars、`.terraform/`、秘密鍵を除外。後続でprivate backend設定・保存plan・新bootstrap rootの除外範囲も検証する|

共有backend不在はソースから確認した事実。実local stateの有無、既存AWS資産、複数運用者の事故発生を調べた結果ではない。
EC2 userdataのbootstrapと、今回提案する**state基盤のbootstrap**は別責務。
商品画像S3（#1）、RDS（#6）、Deployの有効化も対象外。

## 2. 推奨: S3 + versioning + SSE-KMS + native lockfile

**新規構成はS3ネイティブ`use_lockfile = true`を提案する。DynamoDB tableは新設しない。**
1.15系列の[公式S3仕様](https://developer.hashicorp.com/terraform/language/v1.15.x/backend/s3)と、
Terraform **v1.15.6 tagのcommit `699fc4df3a2e8f2205ec7b02698ccd76dfdc095d`**を2026-10-21に照合した。
[backend schema](https://github.com/hashicorp/terraform/blob/699fc4df3a2e8f2205ec7b02698ccd76dfdc095d/internal/backend/remote-state/s3/backend.go)に`use_lockfile`があり、`dynamodb_table`はdeprecated。
[client実装](https://github.com/hashicorp/terraform/blob/699fc4df3a2e8f2205ec7b02698ccd76dfdc095d/internal/backend/remote-state/s3/client.go)は`.tflock`を条件付き作成（If-None-Match）し、解放時はlock ID一致を確認して削除する。
これは**当該版の仕様・ソース確認**であって、実S3での競合・復旧試験の成功ではない。

|案|利点|負担・判断|
|---|---|---|
|S3 native lockfile|1.15.6内蔵。共有stateと同じS3境界で排他|全writerのopt-inが必要。未対応/無効化client、直接S3書込みは防げない。推奨|
|S3 + DynamoDB|既存DynamoDB運用との互換経路が残る|1.15.6ではdeprecated。新規table/権限/移行負担を増やすため新設案に採用しない|
|local継続|AWS state基盤を用意しなくてよい|共有管理・喪失復旧の不足が残る。複数operatorの運用開始条件を満たさない|
|HCP Terraform等|実行の集中管理も選べる|契約・費用・信頼境界の別評価が必要。今回追加しない|

既存DynamoDB backendが別環境で判明した場合は作業停止し、移行を再設計する。
v1.15.6はS3/DynamoDBの**両方を取得する併用**を実装しているが、DynamoDB-onlyとS3-onlyを混在させても共通lockはない。
移行案は「旧DynamoDB-only＋新dual → 全writerをdualへ → 全員停止・旧client廃止確認 → S3-only」。途中のS3-only先行を禁止し、後続実験で排他を証明する。
現在のrepoにDynamoDB設定がないことを、全AWS accountにも存在しない証拠とはしない。

## 3. 保管・暗号化・名前空間

|項目|提案する契約（未実装）|
|---|---|
|格納先|state専用bucket。region/account/envと管理責任者を承認後に決める。アプリ画像/DB backup bucketと共有しない|
|キー|`inventory/<environment>/<region>/host/terraform.tfstate` とその`.tflock`。bootstrapは別root・別state/key。別環境・別rootで同じ管理対象を二重管理しない|
|workspace|当初はdefaultのみ。環境分離は別key・roleで行う。将来の`workspace_key_prefix`も環境内の予約領域へ限定し、workspace名を認可境界にしない|
|暗号化|stateとlockを`encrypt=true`、同regionのcustomer-managed KMS keyの承認済みARNでSSE-KMS。bucket既定暗号化と異鍵/非KMS書込み拒否も整合させる|
|代替|SSE-S3はKMSの費用/鍵可用性負担を減らすが、鍵の分離・権限・監査要件を別途承認する必要がある。障害時の自動fallbackにはしない。SSE-Cは秘密鍵取扱を増やすため提案しない|
|外部公開防止|account/bucketのBlock Public Access全4項目、Bucket owner enforced、ACL不使用。bucket policyは承認roleと対象keyだけを許可|
|通信|TLS必須。非TLSを拒否し、AWS service principalへの影響も公式policy条件と実験で確認。証明書検証を無効化しない|
|versioning|有効化し、current stateを自動expireしない。noncurrent retention/世代数は復旧目標・費用の合意後に確定。active `.tflock`を年齢で削除するLifecycleを設定しない|
|削除保護|通常roleにstate削除/version削除、bucket破棄、versioning停止、KMS無効化/削除の権限を与えない。将来resourceはforce_destroy=falseとprevent_destroyを提案するが、設定自体の削除やIAM管理者の操作を完全には防げない|
|backup|versioningに加え、別の障害・管理権限境界への暗号化backupと復元演習を本番前に定義。KMS旧鍵の復号能力も保存期間中維持。RPO/RTOは未合意・未実測|
|監査|state/lock objectのCloudTrail data eventsとKMS/設定変更の監査を設計。ログは別の保護先へ、operatorに監査削除権限を与えない。state本文・lock本文・credentialsを診断ログへ転記しない|

[S3 Versioning](https://docs.aws.amazon.com/AmazonS3/latest/userguide/Versioning.html)は旧版保持であり同時更新の排他ではない。
[SSE-KMS](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingKMSEncryption.html)は保存時暗号化で、正規Get権限者には復号される。ローカルcopyやplanも秘密情報として扱う。
[Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock-managing.html)のWORM保持もTerraform lockとは別物。retained versionがあってもdelete marker/新versionは作成でき、論理的な上書き事故を排他しない。
live bucketへ一律のWORM設定は今回提案せず、必要なら別backup保管で保持期間・不可逆性・費用を審査する。
[CloudTrail data events](https://docs.aws.amazon.com/AmazonS3/latest/userguide/enable-cloudtrail-logging-for-s3.html)は明示的な設計が必要で、記録済みとは扱わない。

## 4. 最小権限と責務

以下はIAM policyの設計表であり実policyではない。既存app infraのprovider権限と**backendのAWS認証・権限は別**。
backendはTerraform CLI内蔵で、providerのmock/region/assume_roleを自動継承しない。

|主体 / 対象|必要な範囲|与えない権限・注意|
|---|---|---|
|通常state operator / bucket|`s3:ListBucket`を当該envのstate/予約workspace prefix条件に限定|bucket全域の無条件一覧、他環境のprefixを許可しない|
|同 / stateの正確なobject ARN|`s3:GetObject`, `s3:PutObject`|stateの`DeleteObject`、`DeleteObjectVersion`は不要。`s3:*`は禁止|
|同 / 対応する`.tflock` ARN|`s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`|stateを保護する一律Delete拒否で**unlockまで禁止しない**。version削除は通常不要|
|同 / 1つのKMS key ARN|公式backend要件の`kms:Encrypt`, `kms:Decrypt`, `kms:GenerateDataKey`|key管理/削除は分離。ViaService/暗号context条件とS3 Bucket Key使用時のcontext差はTF-Cで確認|
|移行/復旧担当|期限付きのversion一覧/取得（`ListBucketVersions`のprefix条件、`GetObjectVersion`）と承認対象stateへの復旧書込み|他環境・version永久削除・無制限bucket操作は与えない。通常operatorへの恒久付与をしない|
|基盤管理担当|bootstrap、bucket/KMS policy・監査・保持管理のレビュー済み操作|日常のhost操作から分離。鍵管理者が復号も自由にできる設計にしない|
|PR CI / app・EC2|**backendアクセス権なし**|AWS credentials/OIDC権限を今回追加しない。CI mockはこのIAM表の実効性を保証しない|

最小S3権限の根拠は上記v1.15公式仕様。prefixの詳細は
[v1.15.6 Workspaces実装](https://github.com/hashicorp/terraform/blob/699fc4df3a2e8f2205ec7b02698ccd76dfdc095d/internal/backend/remote-state/s3/backend_state.go)にも照合した。
CLIはworkspace列挙用prefixも要求する。state objectのpathだけをList許可して常に十分とは断定せず、予約prefixの列挙を許可し、非default workspaceへのobject操作は拒否する案を実機で検証する。
backendのList/Get失敗を「state不存在」と解釈しない。

operatorは個人識別可能な短期SSO/STS roleとMFAを使用する案。実credential、account/role/key/bucketの値は未確認。
secretをHCL・`-backend-config`・tfvars・planへ埋め込まず、承認済みの認証経路で供給する。
state Getだけでも機密snapshot全体へアクセスできるため、単なるPR reviewerへ付与しない。
通常unlockとforce-unlockは同じS3 Delete権限を使うため、**IAMだけでforce-unlockの承認を強制できるとは主張しない**。

## 5. 排他・運用者間競合・障害境界

- 排他単位はbucket/key。全writerが同じCLI1.15.6・backend・key・`use_lockfile=true`を使用し、`-lock=false`は禁止。lock取得不能時は停止しlocalへ逃がさない。
- 保存plan作成から適用まで常にlockを保持するわけではない。運用変更窓/受付queueも使い、別applyでstateが進んだplanは破棄して再plan・再レビューする。
- lockの時刻だけでprocess死亡を判定しない。自分の失敗したlockの所有者・ID・対象・実行停止を確認し、別運用者の承認後に限って復旧する。[公式locking](https://developer.hashicorp.com/terraform/language/v1.15.x/state/locking)に従う。
- force-unlockはstate破損を修復せず、誤実行は二重writerを作る。所有者不明なら自動unlockせずincidentとして全writerを止める。
- S3/KMS/認証障害はfail-closed。AWS側変更後にstate保存が失敗した場合は私有の`errored.tfstate`等も保護し、applyを反復しない。
- versioned restoreはTerraformの記録の復元であり、AWS resource、DBデータ、app binaryを以前へ戻す操作ではない。[手順案](../operations/terraform-remote-state.md)で分岐を定義する。

## 6. backendの変数制約とbootstrap循環依存

[backendの公式制約](https://developer.hashicorp.com/terraform/language/v1.15.x/backend)により、backend内で`var.region`、locals、data/resource outputを参照できない。
bucket/KMSを同じhost rootで作成してそのoutputをbackendへ渡すことはできず、init前から格納先が必要になる。
後続TF-Bでは、host rootのpartial `terraform/backend.tf`と、checkout外の非credential設定ファイル（bucket/key/region/KMS ARN/許可account）に分ける案。
認証値はそのファイルへも記載しない。`.terraform/`と保存planにもbackend設定が残るため、これらをartifact/cacheで共有しない。

state基盤は**別承認・別rootのbootstrap**で先に作成する案。
最初のbootstrap stateは、承認された私有暗号化保管とbackup責任者を持たせる。既設の独立管理backendが承認済みならそれを使う。
未作成bucketをbootstrap自身の初期backendに指定せず、host stateへbootstrap資産を混ぜない。
bootstrap stateを後日remote化する場合も独立した移行・復旧計画を要し、bootstrap自身のbucket/keyを通常destroy対象にしない。

## 7. 段階・費用・受入条件

|段階|成果 / 承認境界|
|---|---|
|TF-A（今回）|ADR・運用手順案・README導線だけ。既存CI/Java/Docker回帰の実結果をPRへ記録。実state/実AWSの受入完了とはしない|
|TF-B（別PR）|backend/別bootstrap rootの構成案、privateファイル除外、静的契約テスト。AWS無認証で既存27 mockとbackend=falseを維持できることを隔離checkoutで実証。apply/移行は禁止のまま|
|TF-C（別承認）|承認された隔離AWS account・架空の使い捨てstateのみでIAM拒否、並列lock、KMS障害、version復旧、bootstrap喪失を実験。費用・資源範囲・cleanup計画を事前承認|
|TF-D（別承認）|実local stateの権威確認・backup・freeze・移行・全operator切替。実資産のplanレビューと復旧演習、RPO/RTO・監視・担当合意が必要。本設計だけで実施しない|

費用対象はS3の各version/backup/リクエスト/転送、KMS key/リクエスト、CloudTrail data events/ログ保持。
現在のstate容量・更新頻度・region・料金見積は未確認。具体額・保持日数・予算上限を勝手に決めない。
repository maintainerを調整責任者候補、bootstrap/key管理・移行実行・独立承認・監査/復元担当を別責務として定義するが、**実担当者の引受は未合意**。
単独運用でも別reviewerの承認記録を必要とし、担当・予算・backup保持・RPO/RTOが未合意ならTF-C/Dへ進めない。

mock27はhost定義の静的/模擬契約であり、S3 backendの条件付き書込み、IAM実効権限、復旧性能を証明しない。
Java2,370件・CI5ジョブ・Secret/SpotBugs/OSV/Docker/TrivyはPR本文の実測で確認する。AWS受入とは別。
Trivy OS31、Flyway WARN、#145 Windows不安定、#134 jti保留、異版rollback/本番復旧未検証を保持する。
旧Java security #86/#87/#89のOPEN、PR #103のDraft/対象外、Deploy disabledと本番運用NO GOも変更しない。

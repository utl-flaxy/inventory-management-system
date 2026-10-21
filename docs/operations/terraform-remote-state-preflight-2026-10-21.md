# Terraform remote state TF-C0 — 実AWS検証前の資料審査

日付: **2026-10-21**。基準main: **`4fe39478389336e506cca6a6b6383097d6f7de4a`**。
[Issue #75のTF-C0指示](https://github.com/utl-flaxy/inventory-management-system/issues/75#issuecomment-6318168690)、
[採択済みTF-A ADR](../design/terraform-remote-state.md)、[運用手順](terraform-remote-state.md)、
[TF-B / PR #148](https://github.com/utl-flaxy/inventory-management-system/pull/148)を正本とする。
PR #148のHEAD `6c20306555117f3bd7634ebd8afd9e7c099b9d4b`と基準mainのtreeは一致する。

**判定: TF-C実行はBLOCKED。実AWS受入0件。Issue #75はOPEN、PRODUCTION NO GO。**
本書は文書審査と将来の試験計画であり、AWSアクセス・bootstrap・移行・cleanupの承認ではない。
実環境の所有者、account/region、5 role、費用上限、実施者・独立承認者等は未提示/未確認で、推定値を補わない。

## 1. 今回の境界と判定語

- 今回参照するのはGit管理されたコード/文書、公開公式仕様、GitHubのPR/CI証跡だけ。
- **AWS CLI/SDK/APIはread-onlyも含め実行しない**。STSでの本人確認、IAM simulation API、SSO login、AWS設定/credentialの読取りも行わない。
- remote init、real plan/apply/destroy、Terraform state操作、既存local/remote state・tfvars・secretの閲覧/移行は禁止。既存volume、Deploy、本番DBに触れない。
- repositoryの通常CIはAWS無認証の`init -backend=false -input=false -lockfile=readonly`とmockを維持する。Java/Dockerの回帰は使い捨てローカル/CI環境であり、TF-C実験とは別。
- `SOURCE CONFIRMED`は**設定の存在をソースで確認**した意味。実環境の安全性や有効権限のPASSではない。
- `BLOCKED / NOT RUN`は承認/前提が揃わず未実行。未実行をPASSや「該当なし」に置換しない。以下の実試験は**全件BLOCKED / NOT RUN**。

後続TF-Cの許可を得ても、実資産state移行TF-Dや本番配備まで許可されたとは扱わない。
本書には誤実行を避けるため実クラウド用の実行可能なcommand列を掲載しない。

## 2. TF-AとTF-Bの照合（ソース審査）

|TF-Aの契約|TF-Bの正本 / SOURCE CONFIRMED|mock/資料だけでは未確認の条件|
|---|---|---|
|CLI/依存固定・root分離|[bootstrap provider](../../terraform-bootstrap/provider.tf)、両rootのprovider lock。CLI1.15.6/provider6.68.0。bootstrapにremote backend/moduleなし|実roleの存在、初回bootstrap stateの暗号化私有保管とbackup、運用者のCLI/認証経路|
|S3 native lock opt-in|[host backend](../../terraform/backend.tf)は`use_lockfile=true`/`encrypt=true`だけの部分設定。DynamoDBを新設しない|外部設定による上書き防止、実bucket/key/accountの一致、S3条件付きwriteの排他。providerのmockはbackendをmockしない|
|専用private/versioned保管|[main.tf](../../terraform-bootstrap/main.tf)の`protected`でstate/auditの2 bucket、versioning、Public Access Block全4、owner-enforced ACL|AWS側の作成可否、account設定/SCP/endpoint policyとの合成、実際の非公開性とversion保持|
|SSE-KMS・鍵分離|同fileの`state`/`audit` CMK、rotation、削除猶予30日、policy-lockout検査を有効。Bucket Keysは無効。state contextは正確なstate/lock ARNとS3 ViaService|実Put/Get/復号、SSE headerとcontextの一致、暗号鍵への実効権限。30日はRPO/証拠保持期間の合意ではない|
|通常/移行/復旧/管理/監査の分離|[variables](../../terraform-bootstrap/variables.tf)の5 role、[iam.tf](../../terraform-bootstrap/iam.tf)の4 inline policy。既存role/trustは作らない|SSO/MFA、session/role chaining、他のidentity policy/boundary/SCP/RCP。別ARNでも同じ人が全roleを取得できる可能性は未確認|
|stateとlockを別認可|`StateReadWrite`はstate Get/Put、`LockReadWriteDelete`はlock Get/Put/Delete。state/version削除は拒否、lock versionの永久削除も拒否|実Get/Put/Deleteのallow/deny、異なるsessionでの排他・解放。通常unlockとforce-unlockの承認はIAMでは区別できない|
|default workspaceのみ|`ListNamespace`はstate keyと予約workspace prefix末尾`/`の完全一致。非default workspaceのobject操作は明示deny|CLI Workspaces列挙の実リクエストとIAM条件、別環境/別key/実sessionの拒否。他の既存権限がないとは断定しない|
|復旧は特別権限|recoveryだけに対象prefixのListBucketVersions/GetObjectVersionを追加。migrationは通常operatorと同じ権限、別role/session|role取得の承認/期限、旧versionの正しい選択・復号、lineage/serial照合、全writer停止の実効性|
|監査と独立保管|`aws_cloudtrail.state`はmulti-region、global/managementとstate bucketのread/write data events、log validation。auditは別bucket/CMKで閲覧roleも別|配信/欠落、KMS/SourceArn/context、実監査イベント・digestの検証、証拠保管/監視担当。audit bucket自身のdata eventsは対象外|
|削除保護と復旧可能性|bucketは`force_destroy=false`。bucket/CMK/trailは`prevent_destroy`。state/log削除をbucket policyでdeny、expiryやactive lock TTLなし|特権管理者やconfig削除への絶対防御ではない。cleanupの一時例外・権限・順序は未承認（4節）|
|private入力と保管|[offline runner](../../scripts/ci/terraform_offline.py)、[安全性テスト](../../scripts/ci/tests/test_terraform_safety.py)、ignore/Secretゲート。indexの公開sourceだけをexport|実運用端末・私有保管・backup・保存plan/cache/証拠の漏洩防止。任意名へ改名した機密fileまで完全に検出するDLPではない|

`allowed_account_ids`とmanagementへのAssumeRoleは**bootstrap provider**の設定。
S3 backendはCLI内蔵の別認証経路であり、providerの制約を自動継承しない。
[outputs](../../terraform-bootstrap/outputs.tf)の座標と、checkout外の非credential backend設定を二者で照合する必要がある。
TF-Bの`environment` validationはproduction/stagingも構文上受け付ける。TF-Cの許可対象は**承認済みsandboxのみ**で、validation成功を利用許可へ読み替えない。

### 14 mockの保証範囲

[contracts.tftest.hcl](../../terraform-bootstrap/tests/contracts.tftest.hcl)は次の設定/入力契約だけを検証する。
TF-Bの[CI39610293736](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39610293736)の成功は履歴証拠であり、実AWS受入ではない。

|mock run|実AWSで残る確認|
|---|---|
|protected_storage|private/versioning/ACL/KMSの実状態と書込み時の挙動|
|operator_permissions|外部policyを含むstate/lock/List/KMSの実効権限|
|state_resource_policy|実際のstate削除拒否、lock解放、TLS/暗号/主体の拒否|
|recovery_separation|migration/通常roleからのversion取得拒否、recoveryの取得/復号|
|key_separation|実key administrationと復号の分離、PutKeyPolicy後の管理継続性|
|audit_delivery|SourceArn/context/ACL/配信、復号とdigest、event欠落・再帰記録の範囲|
|backend_coordinates|実account/region/keyと独立backend認証、default workspaceの一致|
|invalid_account / wrong_account_roles|文字列の拒否だけ。実account所有・認証主体の照合は未実施|
|wrong_environment_roles / invalid_environment|文字列の拒否だけ。承認対象環境との二者照合は未実施|
|invalid_region / unsupported_partition|形式の拒否だけ。実regionの利用可否/制限は未実施|
|shared_roles|入力ARNの分離だけ。実人間・SSO・trustでの責務分離は未実施|

## 3. 実行承認前のBLOCKED台帳

以下は全て**BLOCKED（未提示/未確認/未承認）**。実値はGitHubで収集せず、権限を持つ所有者が承認済み私有経路で確認する。
公開文書には承認票/証跡のopaque参照と確認状態だけを残す。credentialは承認票にも記載しない。

|ID|解除に必要な情報・証拠|責務（実担当者は未定）|
|---|---|---|
|B01|sandboxの所有/利用許可。会社/本番accountとの分離、実account/region/partition、既存資産に触れない境界|account所有者・独立承認者|
|B02|bucket/CMK/trail/role/key/workspaceのprivate allowlist、名前の所有と新規性。既存bucket/stateが判明したら利用せず停止|基盤所有者・実施者|
|B03|許可API/資源数/操作（作成・読取・拒否試験・fault・復旧・cleanup）と期間。別namespace canaryも含む。host VPC/EC2作成は対象外|maintainer・独立承認者|
|B04|5 roleの存在、identity/trust、SSO/MFA、session期限/role chaining、誰が何をAssumeRoleできるか。CLIとbackendの認証経路|認証管理者|
|B05|identity/boundary/session policy、SCP/RCP、S3 endpoint policy、account public blockと既存trailの合成レビュー|組織/セキュリティ管理者|
|B06|bootstrap実行者の最小provisioning権限、KMS PutKeyPolicy/管理継続性と独立復旧経路。単一管理roleの喪失対策（4節）|鍵/基盤管理者・独立承認者|
|B07|費用上限の具体額/通貨/期間/請求責任者、リクエスト/資源上限、通知先・対応者、停止判断と監視間隔。multi-region/重複trail/S3 versions/KMS/保管を含む|費用所有者|
|B08|実施者、独立承認者、事故対応/cleanup/証拠保管の担当者と連絡手段。同一人物による自己承認を避ける|maintainer|
|B09|日時・最大試験時間、各待機の上限、CloudTrail/digest観測窓、復旧RPO/RTO、期限超過時の中止|実施者・独立承認者|
|B10|レビュー済みsynthetic harnessのsource SHA、無AWS resourceのtest root、barrier/abort/watchdog、既存state非混入の検査|試験設計者|
|B11|削除保護の一時例外、cleanup role/権限・順序・復元可能性・残置時の責任/費用。鍵削除猶予後までの追跡承認|基盤/鍵/費用所有者・独立承認者|
|B12|暗号化私有保管、アクセス/保持/廃棄期限、bootstrap state/証拠backup、暗号鍵を消した後も必要な証拠を読める保管方式|証拠/復旧担当|
|B13|CloudTrailの期待event一覧、配送/復号/digestの測定と監視、cleanup後を覆う独立監査の経路|監査担当|
|B14|上記を参照したmaintainerの**対象・費用・操作を明示する別途実行承認**。有効期間と変更時の再承認条件|maintainer・独立承認者|

SSOの認証保証を`aws:MultiFactorAuthPresent`だけで一般化しない。IdP/Identity Centerと実際のrole取得経路を所有者が照合する。
AWS Budgetsの通知をリアルタイムの強制課金上限と扱わない。費用情報/通知に遅延がある前提で、実行時間・資源数・試行回数にも承認済みの上限を置く。

## 4. 開始前に特に解消する設計上の停止点

### C01: cleanupは通常destroyで完了しない

state/logのDeleteObject/DeleteObjectVersionは全主体へ明示denyされ、bucket/鍵/trailにも削除保護がある。
通常operator/recoveryに監査破棄やKMS ScheduleKeyDeletionは付与されていない。
**「試験後にdestroyすればよい」というcleanup計画は不可**。保護はTF-Aに整合するが、試験用の終了手順は別承認が必要。
通常権限へ恒久的なDelete/鍵管理を足さず、専用allowlist・承認された管理session・一時policy/config差分・保全/確認/順序/期限を開始前に審査する。
今回その差分や実権限を追加しない。安全な終了経路を準備できない間は最初のresourceを作らない。

### C02: key policy管理とbreak-glassは未実証

TF-BのKeyAdministrationはmanagement roleだけをPrincipalにする。直接Decrypt/DisableKey/ScheduleKeyDeletionは付与しないが、PutKeyPolicy権限を持つ特権主体である。
別のbreak-glass principalやそのtrustはソースにない。account rootなら必ず復旧できると推定しない。
roleの削除/再作成、trust喪失、SCP変更でも管理を継続できる承認済み復旧経路が必要。
必要なら実験前の**別レビューによる構成修正**を先行させる。policy-lockout検査をbypassしたり、試験で管理権限を意図的に失わせたりしない。

### C03: lock ID・所有者・承認は別

Terraform1.15.6はlock fileを条件付き作成し、unlock時にlock IDを照合する。[実装根拠](https://github.com/hashicorp/terraform/blob/699fc4df3a2e8f2205ec7b02698ccd76dfdc095d/internal/backend/remote-state/s3/client.go)。
誤ったIDのforce-unlock拒否と、所有者が稼働中なので操作を開始しない手続を別々に試す。
正しいIDを知る別人のDeleteをIAMが通常unlockと区別して必ず拒否するとは主張しない。
稼働中writerに対する正しいIDのforce-unlockは試験しない。所有者不明時は自動解放せず全writerを止める。

### C04: host rootを実験用にapplyしない

現host rootにはVPC/EC2等がある。backend検証のためにそこへ実plan/applyしてはいけない。
後続の承認対象は、新規sandbox bucketのTF-Bが許可する正確なkeyと、AWS resourceを持たない**別のsynthetic test root**。
例えば組込`terraform_data`だけのfixtureを後続でレビューし、backend部分設定は本契約と一致させる。今回そのharnessは作成/実行しない。
任意の別keyへ逃げるためにIAMを広げない。初回bootstrap stateは新設sandbox資産のprivate inventoryであり、公開可能なfixtureではない。
既存stateを流用せず、故障注入/旧version比較はsynthetic test stateだけに限定する。

## 5. IAM許可・拒否の実証表（全てBLOCKED / NOT RUN）

これは試験の期待値であり、新規権限の付与表ではない。実効policyはB04〜B06で別審査する。

|主体|許可を実証する操作|拒否/分離を実証する操作|
|---|---|---|
|operator / migration（別session）|限定ListBucket、正確なstate Get/Put、lock Get/Put/Delete、state CMKのEncrypt/Decrypt/GenerateDataKey（指定context/ViaService）|state Delete/永久version削除、lock version永久削除、非default workspace、対象外namespace、過去state version取得、audit閲覧/鍵管理|
|recovery|通常操作＋対象prefix ListBucketVersions/GetObjectVersion、同じstate CMKで復号/復旧write|他環境/他key/永久削除/通常の監査破棄。実stateへの権限を試しに使わない|
|audit|audit prefixのList/versions/Get、audit CMK復号とdigest検証|state/lockアクセス、audit書込み/削除、state CMK利用|
|management|承認されたsandbox資源の作成/設定/読取と対象roleへのinline policy、KMS Describe/Get/PutKeyPolicy等|変更前policyのままでstate/audit本文を直接取得できないこと。自分でpolicyを変更できる特権は残るため絶対分離とはしない|
|CloudTrail service|正確なtrail SourceArnでaudit bucket GetBucketAcl、限定prefix PutObject、audit CMK GenerateDataKey/Describe|誤SourceArn/context/宛先の拒否。serviceを偽装した呼出しで代用せず、承認されたfixture/設定の範囲で確認|
|CI/app/未承認主体|backendアクセス権を新設しない|credentialを持たせて試さず、許可されたsandbox検査主体だけで権限境界を検証|

bootstrap管理に必要なS3/IAM/KMS/CloudTrail APIの詳細、タグ条件、初期CreateKey等のResource制約は、provider6.68.0と許可資源のprivate manifestに対して別審査する。
`iam:*`やaccount-wideのデータ読取、恒久的なAdministratorAccessを便宜上使わない。
identity policyだけでなくresource/key policy・session・boundary・SCP/RCP/endpointも評価する。単純な「Allowが見つかったから成功」で確定しない。

## 6. 後続TF-Cの試験順序と合否（全てBLOCKED / NOT RUN）

各行はB01〜B14の解除と独立承認後だけ実施する。試験ごとに事前条件・期待status・write件数・state不変・lock/監査の結果を記録し、予期しない成功/拒否は両方FAILとして停止する。
AccessDeniedの理由が別policyだった場合、所期のpolicyの証明に数えない。404/空一覧/timeoutだけを拒否・不存在・配信成功の証拠にしない。

|ID / 順序|試験と判定条件|私有計測 → 公開する要約|
|---|---|---|
|T01 開始前|承認票のaccount/region/role/session・期限・MFA/SSO・SCP/public blockを照合。providerとbackendを別に確認。誤accountや未承認scopeではwriteを開始しない|所有者の照合記録 → 一致/不一致、API未開始|
|T02 新規基盤|承認されたbootstrapだけを新規作成。既存bucket/CMK/trail/stateの流用はしない。KMS policy-lockout検査を保持し、作成主体のDescribe/GetKeyPolicyと同じ安全なpolicyの再設定・独立復旧経路を確認|対象manifestとpolicy/状態 → 新規性確認、管理継続可否|
|T03 保管/暗号|versioning/public block/owner-enforced/CMK rotationを観測。state/lockのSSE header、正確なkey/context、Bucket Keys無効を確認|metadataと比較 → 契約一致、本文非公開|
|T04 default/List|新規synthetic rootのdefault workspaceで限定List/Get/Putが成功。予約workspace prefixの列挙が成功し、非default object操作は拒否|APIとobject version比較 → allow/deny、想定write数|
|T05 誤参照|対象外key/env/workspace/accountを承認されたsynthetic canaryで拒否。既知のcanaryを使えないGet試験はBLOCKEDのまま。実環境へprobeしない|canary参照とdeny根拠 → 拒否、state不変|
|T06 暗号/TLS拒否|stateへのmissing SSE header、AES256、誤KMS ARN/contextを拒否。HTTPS requestやpolicy simulationで実TLS拒否を証明したとはしない。非TLS実証の安全な方式は未定でBLOCKED。credential/署名をHTTP平文で送らず、証明書検証も無効化しない|承認済みsynthetic writeと条件/結果 → 拒否、version不変。TLS実証は未実施|
|T07 主体/削除|5節のallow/deny、state/lock/auditのversion/削除保護を実証。直接S3操作は承認済みの新規fixtureだけ|private object/version baseline → 主体別可否、永久削除0|
|T08 同時lock|別directory・別operator sessionのA/Bをbarrierで開始。AがIf-None-Matchによる実lock保持を確認後、Bは待機なしのlock取得で失敗しstate write0。Aが正常解放後、Bは古いplanを捨て再planし成功。保存planとapplyの間を連続lockと扱わない|private lock所有/ID・条件付きwrite結果・順序 → 同時writer最大1、B拒否/解放後成功|
|T09 誤unlock|Aが保持中に誤IDのforce-unlockを拒否し、Aのlock/stateが不変。稼働owner/承認なしの場合はharness/手順が操作を開始しない。正しいIDで他人の稼働lockを外さない|private ID照合とowner稼働確認 → mismatch拒否、残存lock不変|
|T10 孤立lock|自分のsynthetic writerだけを制御停止し、全writer/再試行停止を確認後、独立承認した所有lockだけを復旧。解放後は新しいplanで再開|停止/承認/lock IDのprivate照合 → 対象一致、正常解放、二重write0|
|T11 KMS/S3 deny|承認された短期sessionの対象keyだけのexplicit deny等でlock Get/Put/Delete、state Get/Put、KMS復号/暗号化を個別に故障注入。provider障害とbackend障害を区別。lock取得前/保持後を分ける。base key policyの管理権限剥奪や鍵無効化/削除はしない|注入範囲とAPI結果 → 非zero、禁止write0、残存lock有無|
|T12 fail-closed|T11後にlocal fallback・lock無効化・古いplan再applyをしない。保存失敗/解放不能はwriter停止・private errored state保護・管理担当への移管。独立した復旧認証が通ることを確認|回復経路とstate差分 → fail-closed、再開前照合|
|T13 監査配送|意図したstate/lockのread/write/deny、KMS/管理イベントをprivate時刻/IDで突合。home SourceArn、multi-region context、S3 ACL/prefix、audit roleの実復号を確認。audit bucketのobject data eventsを含めない範囲も明示|期待event表と配送済みevent → 一致/欠落/遅延、未達は成功にしない|
|T14 digest|対象region/期間のlogとdigestの実integrity検証を行う。flag=trueや配送objectの存在だけではPASSにしない。digest配送を含む承認済み観測窓に間に合わなければ停止/延長再承認|private検証結果 → verified/欠落、対象期間のopaque参照|
|T15 version復旧|synthetic stateの2世代を作り、全writer停止/権限fence後に旧versionをrecoveryで取得・復号・比較。別key/lineage候補は**書込前**に拒否。承認済み旧内容を新current versionとして復旧し再plan/照合。壊れた現versionも保全|private lineage/serial/content比較 → 同一性、復元値一致、予期しないwrite0|
|T16 bootstrap保護|新規sandbox bootstrap stateの暗号化backupと復元可能性を私有コピーで確認。権威ある唯一のcopyを削除しない。host stateへimportしない。担当不在/鍵喪失なら受入失敗|private backup/復元照合 → 検証可否、測定RPO/RTOと限界|
|T17 終了|7節の承認済みcleanupを実施し、資源/権限/費用/証拠保持の残件を列挙。pending deletionや保持中bucketを消えたと報告しない|allowlist前後比較 → 完了/残置/BLOCKED、担当/期限のprivate参照|

T08のbarrierは後続harnessのレビュー対象。lock保持を実測し、解放signalと上限付きwatchdogを使う。
固定sleepの経過だけで競合成立/解放成功を判断せず、稼働writerを自動force-unlockするcleanupも作らない。
T11の注入は元から持つ権限を減らす範囲で設計し、別の復旧sessionを先に確保する。実効性を確認できない注入方法はBLOCKEDに戻す。
T15のraw version copyはTerraformのlineage/serial guardを自動適用しない。旧serialへの後退も記録し、他のwriter/保存planをfenceしてから再照合する。
復旧が成功しても実AWS資産/DB/appのrollbackを証明したことにはならない。

## 7. 停止・失敗対応・cleanup

|発生条件|直ちに行う判断 / 許されない回避|
|---|---|
|trust/AssumeRole/MFA/SSO・account不一致、SCP/public blockの競合|当該phaseを開始しない。広域credentialへの切替や本番accountでの代替は禁止|
|KMS policy更新不能/lockout/復号不可|writer停止、承認済み鍵管理/独立復旧担当へ移管。policy-lockout検査を無効化しない|
|SSE header/SourceArn/context/prefix/ACLの不一致、監査欠落|FAILとして止め、失敗原因と残件を記録。暗号化/監査/認可条件を緩めてPASSにしない|
|lock取得後のエラー、所有者不明、unlock不可|全writerと自動再試行を停止。lockを時刻で消さない。ID/所有者/承認を私有照合するまで解放しない|
|費用/時間/試行回数の上限到達、通知不能|新規試験を停止。承認済みの安全な停止/cleanupだけへ移る。追加費用・待機延長は再承認|
|実state/secret/他者資産混入の疑い|読取/再試行/共有を止め、事故担当へ私有通知。調査目的で内容を表示/転載/削除しない|
|削除保護でcleanup不可・鍵削除が未承認|通常権限へDeleteを追加しない。所有者/費用担当に残置と期限を移管し、完了と報告しない|

### cleanupの開始条件と順序（未承認・未実行）

1. 最初のresource作成前にB11の終了計画を承認する。実施後の場当たり的な保護解除を前提にしない。
2. 全writer停止とlock所有者を確認し、新規試験を凍結。最後の必要な監査event/digestと復旧証拠を取得・検証し、承認済みの暗号化保管へ移す。
3. 保持期限と復号必要期間を確認する。監査停止/最後の削除操作を覆う独立監査経路がなければ、終了まで監査済みとは言わない。
4. 二者照合したsandbox allowlistだけに、承認された管理sessionで一時policy/config差分を適用する計画を使う。state/audit versionやdelete markerの削除、各保護解除も個別の承認対象。広域/既存bucketの一括削除は禁止。
5. 復元/保持が必要な暗号文の唯一の鍵を消さない。KMSの削除予定状態では復号できなくなる点も含め、保存証拠の別鍵/私有保管での可読性を先に確認する。
6. 新規fixtureのstate/lock/version、trail、bucket、作成したinline policy等を依存順で確認する。**先行作成済み5 roleや元のtrust/他policyを削除しない**。鍵の削除予定化/取消は別承認された操作だけ。
7. 30日のKMS削除猶予など、承認期間後も残る資源/保管/権限/費用の担当・期限・最終確認を引き継ぐ。削除予定化を資源消滅と報告しない。
8. cleanup例外で触れた既存管理設定は承認済みbaselineへ戻し、対象外資産へ変更がないことを所有者が確認する。削除済みresourceのpolicyを「復元した」とは記載しない。

## 8. 秘密を出さない証跡契約

公開する項目は、**本書/実行harnessのsource SHA、CLI/provider版、試験ID、期待/実status分類、件数、state不変のboolean、相対所要時間、結果/未実施理由、opaque承認/証跡参照**だけ。
account ID、実bucket/role/KMS ARN、object key/version ID、request/event/lock ID、session/host名、絶対時刻と主体の対応、金額/担当者の実詳細は私有台帳へ分離する。
secret/state本文、JWT/DB情報、tfvars、値入りbackend設定、保存plan/plan JSON、`.terraform` cache、生のSDK/CloudTrail outputはGit/PR/CI artifactへ載せない。
synthetic stateにも新規AWS資源metadataやbackend座標が混ざり得るため、公開用fixtureとは扱わない。

- debug/trace、shell tracing、credentialを含むCLI引数は使用しない。credential値はログ採取の対象外とし、短期sessionの失効/一時情報破棄を担当が確認する。
- 生出力を全量公開してからredactする運用にしない。承認された私有環境で必要最小限を取得し、allowlist方式の要約を二者検査して公開する。
- stateの比較は私有環境で行い、本文/単純な公開hashではなく一致/不一致だけを出す。lineage/serialやlockの所有情報もprivate参照で追跡する。
- HTTP error/exit1だけでは期待する拒否の証明にならない。取得可能なprivate診断で原因を分類し、未知ならFAIL/BLOCKEDとして止める。
- 保持期間/暗号化/閲覧者/廃棄/漏洩時対応はB12で承認する。CI artifactの30日保持を実AWS監査の保存方針へ流用しない。

## 9. TF-C0提出と次の判断

このPRの受入は、ソース照合・リンク・文書差分・既存回帰/CIの確認と、上記BLOCKEDの独立レビュー。
**文書PRのmerge、CI成功、TF-B採用はTF-C開始の承認にならない。**
B01〜B14のprivate証拠と試験/cleanup manifestを揃え、maintainerが対象環境・API/資源・費用・実施者/承認者・期間を明示して別途許可したときだけ、次のTF-C実行作業を検討する。
構成修正が必要なら別PRで審査し、今回Java/POM/Terraform定義/CI/runtime/migrationを変更しない。
TF-Dはさらに別のowner/freeze/backup/移行承認を要する。Issue #75はOPENのまま。

Windows #145の根因未確定、#134 jti保留、#86/#87/#89の本番運用条件OPEN、Trivy OS31（MEDIUM27/LOW4）、Flyway WARN、異binary rollback/本番復旧/容量/SLA未検証を保持する。
PR #103はDraft・対象外。Deploy disabled、**PRODUCTION NO GO**を継続する。

## 10. 公開一次資料（2026-10-21照合、実API未使用）

- [Terraform1.15 S3 backend](https://developer.hashicorp.com/terraform/language/v1.15.x/backend/s3)と[locking/force-unlock](https://developer.hashicorp.com/terraform/language/state/locking)。正確な1.15.6実装は2節/4節の固定commitに基づく。
- [IAM評価](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_evaluation-logic.html)と[Principal/sessionの条件](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_principal.html)。外部policyを読んだ証拠ではない。
- [CloudTrail S3 policy](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/create-s3-bucket-policy-for-cloudtrail.html)、[KMS policy](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/create-kms-key-policy-for-cloudtrail.html)、[digest検証](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-log-file-validation-intro.html)。TF-Bはstateの暗号header拒否をaudit bucketへ一律流用していない。実配送/検証が必要。
- [KMS削除](https://docs.aws.amazon.com/kms/latest/developerguide/deleting-keys.html)、[AWS Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html)。削除/費用を含む実環境の運用承認は未取得。

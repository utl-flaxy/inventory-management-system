# Terraform remote state移行・復旧手順案（TF-A）

**移行・復旧は未実行の運用案**（TF-Bで静的定義のみ追加）。2026-10-21、[Issue #75 / TF-A指示](https://github.com/utl-flaxy/inventory-management-system/issues/75#issuecomment-6315668832)。
[設計ADR](../design/terraform-remote-state.md)を先に確認する。
この文書やPRのmergeはAWSアクセス・backend作成・既存stateの読み出し/移行を許可しない。
今回のCodex作業では、以下の移行/復旧command、AWS確認、既存state/secretへの操作を**実行しない**。
後続TF-C/Dの権限を持つ運用者が、対象・日時・費用・backup・承認者を記録してから使う。

## 1. 今回実行してよい検証の境界

Git管理下の`terraform/`（設定・lockfile・tests・公開鍵fixture・userdata/Caddy template）と、main.tfが参照する`scripts/tls/install-caddy.sh`を新規の私有directoryへ相対配置を維持してexportし、既存state/tfvars/`.terraform`をコピーしない。
その使い捨てdirectoryに対してのみ、既存CIと同じ手順を使う。

```bash
terraform fmt -check -recursive
terraform init -backend=false -input=false -lockfile=readonly
terraform validate
terraform test -no-color
```

mock providerの27 runは全てplan。実AWSのplan/apply、backend init、state一覧/表示/コピーではない。
backendはprovider mockの対象外なので、将来backendを追加しても`-backend=false`と無認証CIを維持して別途検証する。
既存stateがあるか調べる目的で通常checkoutからinit/plan/stateコマンドを実行しない。

## 2. 実作業前の停止条件・承認記録（後続TF-C/D）

|確認|満たせなければ|
|---|---|
|対象account/region/bucket/key、default workspace、CLI1.15.6/provider lockfile、構成commitが二者で一致|操作停止。別環境のstateを調べて推測しない|
|全operator/automationの停止と変更受付freeze、唯一の移行実行者、連絡/事故対応責任者の合意|操作停止。古いlocal writerや旧backend clientを残さない|
|S3/KMS/IAM/監査・versioning・backup、短期認証、費用/保持期間、復元先と承認者が準備済み|bootstrapの別承認へ戻る。アプリinfra applyで代用しない|
|権威のあるstateと実資産の対応が運用者により確認済み|local fileがないだけでgreenfieldと判定しない。複数local copyならlineage/serialだけで優劣を決めず事故調査へ|
|backend作成/移行/確認plan/復旧それぞれの承認範囲が明確|「docs承認」「CI成功」を実AWS操作の承認へ拡張しない|

記録はticket ID・担当/承認・対象の私有参照ID・開始終了・成功/失敗分類・backup参照・比較合否とする。
state本文、tfvars、backend認証情報、lock本文（端末名/ユーザー名等）、plan JSONはGitHub comment/PR/artifactへ載せない。
debug/traceやshell tracingを有効にせず、AWS/CLIの生出力をそのまま公開しない。

## 3. bootstrap（実AWSは別承認）

1. hostとは別root・別state管理者でstate基盤を作成する。初回stateは承認された暗号化私有保管とbackupを使い、管理者不在なら着手しない。
2. bucket名の所有/region、KMS key ARNと復号・保持、public block/ACL無効、versioning、TLS/role policy、監査保管を確認する。
3. 対象state objectと`.tflock`を分けたIAMで試験し、state削除拒否と正常unlockを両立する。account全体のadmin credentialを通常operatorへ配布しない。
4. state基盤自体の復旧も演習する。bucket/KMS喪失や権限変更でhost Terraformを使えない場合の連絡先・復旧経路を残す。
5. bootstrap資産の所有state/backup参照を引き継ぐ。host stateへ資産を二重importせず、自身の格納先を初回init前に必要とする循環を作らない。

## 4. local → S3移行（後続TF-Dのみ）

1. **freeze**: 全writerの停止を確認。古い保存planを適用禁止とし、移行窓の変更を受け付けない。移行担当だけを対象keyへ書ける状態にする。
2. **保全**: 権威のあるlocal state/backupを所有者が暗号化私有領域へ保全し、最小ACL・保存期限・復元確認を記録する。raw bytesを保持し、PowerShellの文字code/BOM変換やJSON再整形をしない。
3. **照合**: 私有の検証でlineage、serial、resource address/IDの対応、outputs、構成versionを照合。公開するのは一致/不一致のみ。state/保存planの内容やhashからsecretを判断しない。
4. **宛先確認**: 承認先に想定外のcurrent/過去version/delete markerがないことを確認。404/403や空の一覧だけで未使用と決めず、権限・key・versioningを確認する。既存remote stateがあれば上書きせず停止する。
5. **移行**: 後続TF-Bでレビュー済みのpartial backendと非credential設定を使い、唯一の移行担当が対話確認付きで行う。次はplaceholderを含む**将来のcommand案**で、今回は実行しない。

```text
terraform -chdir="<APPROVED_MIGRATION_DIR>" init -migrate-state -lockfile=readonly -backend-config="<PRIVATE_NON_CREDENTIAL_BACKEND_FILE>"
```

`-force-copy`は対話を自動承認するので使用しない。`-reconfigure`は既存stateを移行しないため、この手順の代替にしない。
仕様は[Terraform1.15 init](https://developer.hashicorp.com/terraform/cli/v1.15.x/commands/init)を参照する。
移行はinfra applyを必要とする手順ではなく、ここでplan/applyを連続自動実行しない。

6. **移行後確認**: remoteのversion/暗号化・論理的state内容、lineage、serialの意味とresource対応を私有検証で比較。単なるbytes差（CLI再保存によるformat等）を資産差と同一視しない。不一致ならfreezeを維持する。
7. **第二operator確認**: 新規私有checkoutが既知の正しいremote stateへ接続することを確認。この時点の`init -reconfigure`は接続用でありlocal移行用ではない。明示承認されたread/refresh/planで想定外のcreate/destroy/replacementがないことをレビューし、applyは別承認までしない。
8. **排他と復旧の受入**: 次節の2 operator試験、停止・認証/lock障害、version復旧を使い捨て環境で先に合格させる。実資産への移行成功だけで障害復旧済みとしない。
9. **切替**: 全operatorへ同一backend/key/CLIを配布し、old local state/planを読取り専用の退避物にする。残存copyからのapplyを禁止し、remoteを唯一の正本と宣言してからfreezeを解除する。

local state/実資産の両方が存在しない新規環境は、運用者がその事実と空の移行先を確認して別のgreenfield手順を承認する。空stateで既存remoteを初期化しない。

## 5. 通常運用と複数operatorの受入試験

|試験/運用|期待する結果（実AWSで未検証）|
|---|---|
|Aが対象keyのlockを取得した状態でBが同じkeyを操作|Bは既定の取得待ち0sで競合失敗。待機を導入する場合は有限予算を別途合意する。B起因のstate/infra変更なし、Aの正規更新を阻害しない。固定sleepで成立を仮定せず、lock取得を観測して開始|
|A終了・正常unlock後にBが新しいstateを読んで再plan|Bが取得でき、serial/資産対応が正しい。同じ古いplanの再送で通さない|
|別env/key、同env予約workspace prefix、未信頼role|許可された独立keyだけ動作。他envと非default workspaceへのobject操作は拒否。bucket rootの無条件一覧を要求しない|
|state書込み拒否、`.tflock`作成/読取り/削除拒否、KMS拒否/認証期限切れ|安全に失敗。権限を広げたり`-lock=false`/local fallbackで通さない。失敗後stateとlockの整合を検査|
|A異常終了・unlock失敗|自動TTL削除なし。所有者・ID・実行停止確認後の復旧手順だけで再開|
|古い保存planと他operatorの新apply|stale planを拒否し再plan・再承認。plan→apply間をS3 lockが一括保護すると仮定しない|

人の変更窓・受付queueはS3 lockを補完する。IAMによるkey分離でも、正規writerによる直接S3書込みやlocking無効化を完全には防げない。
通常のplan/適用は同じCLIと承認された構成で行い、保存planは私有領域で限定保持する。
planのreviewは破壊/再作成・環境・資産対応・費用・secret露出を含む。CI mockのplan成功を実資産の変更計画へ置換しない。

## 6. 事故・復旧・停止判断

|症状|対応案と再開条件|
|---|---|
|lock競合|所有者へ連絡。時間の経過だけではstaleとしない。正常な相手のlockを解除しない|
|孤立lock / 解放失敗|所有者・key・lock IDと実行停止/再試行停止を私有経路で確認し、独立承認を記録。自分の失敗したlockに対する`terraform -chdir="<APPROVED_RECOVERY_DIR>" force-unlock <LOCK_ID>`を後続承認範囲でのみ使用。`-force`で対話を省略しない。所有者不明ならincidentとして全writerを止める|
|S3/KMS/認証の障害|fail-closedで停止。期限付き認証と鍵/サービスを正規手順で復旧。TLS検証/暗号化を無効化せず、別local stateで継続しない|
|AWS変更済み・state保存失敗|自動再applyを禁止。private `errored.tfstate`等を保護し、remoteとのlineage/serial/実資産対応を比較。CLIの[state pushの安全検査](https://developer.hashicorp.com/terraform/cli/commands/state/push)を尊重し、`-force`やserial手編集で突破しない|
|state破損/誤上書き/誤削除|全writerを停止・書込み権限をfenceし、現versionも保全。承認された旧S3 version/backupを私有領域で復号・検証。実資産との対応を確認して復旧候補を決める。keyが違う/lineage不明/古いserialなら通常操作を止める|
|CLIで読めないstateのversion復旧が必要|隔離keyで先に演習し、別承認のRecovery roleで選定versionを**新current versionとして**復旧する案。S3 copyはTerraform lock/lineage検査を自動で実施しないため、全writer fenceと二者確認なしでは使わない。旧/破損versionを永久削除しない|
|KMS key喪失・復号不能/backup不完全|復旧成功と判定しない。versioningがあっても復号できなければ戻せない。鍵管理・独立backupの担当へincidentを移管|
|復旧後|正しいkey/lock取得・解放、機密情報非露出、state/実資産の対応、レビュー済みplanを確認してから再開。古いstateにない資産のimport等は追加承認し、重複作成/破壊を自動実行しない|

lock IDは私有の復旧判断に使い、lock本文を公開ticketへ貼らない。IAMだけでは通常unlockとforce-unlockを区別できないため、承認と監査は運用で補う。
versioningの復元試験は「変更前と同じ資産対応へ戻る」「Bが新stateを読める」「旧版も保全」「操作監査あり」を確認し、経過時間/失われ得る更新範囲からRPO/RTOを測定する。
stateの復元はAWS resource・MariaDB・appのrollbackではない。本番backup/復旧や異binary rollbackを実証したとは報告しない。

## 7. 移行の中断・戻し方

- remoteが正本になる前に失敗した場合も、どちらへ書けたかを確認してfreezeを維持する。空/部分的なremoteを自動削除せず、二重writerがないことと権威のあるstateを確認して再計画する。
- remoteへ新更新が入った後は、元local backupへの復帰やbackend設定のgit revertだけで戻さない。最新remoteの保全・照合と、別承認された逆移行が必要。
- `init -reconfigure`は移行、`-backend=false`は復旧、`force-unlock`はstate修復の代わりにならない。古いCLI/backend形式へのrollback互換性も未実証。
- 移行/復旧の成功条件を満たさなければoperationを停止し、incidentと未確認事項を記録する。timeout延長、検査skip、失敗の握りつぶしを受入に使わない。

## 8. 完了記録と残余

TF-Aの受入は文書リンク・設計契約のレビューと、変更HEADのJava2,370件/F/E/S0・mock27・必須CI5ジョブ・既存セキュリティ/Docker検証を確認すること。
TF-C/Dの記録には承認対象、lock競合/拒否の実測、復元対象/比較合否、暗号化・権限・監査、RPO/RTO、担当者引継ぎを私有証跡として残し、公開報告は秘密を含まない要約だけにする。
本PRだけで#75をcloseしない。実backend・実state移行・IAM/S3/KMS・復旧性能は未検証。
Trivy OS31、Flyway WARN、#145 Windows不安定、#134 jti保留、異版rollback/本番復旧未確認を保持し、Deploy disabled・**本番運用NO GO**を継続する。

## 9. TF-B静的実装後の確認（2026-10-21）

[bootstrap README](../../terraform-bootstrap/README.md)にrole分離・既存role/trust/management権限の前提を記載した。
新規rootの初回stateは私有暗号化保管で別管理し、作成予定のbucketを自身の初回backendに使わない。
hostはS3部分設定のみ。実値入りbackend設定をこのrepositoryへ追加しない。
外部非credential設定の許可項目はbucket/key/region/kms_key_id/allowed_account_ids/workspace_key_prefix。
`use_lockfile=true`・`encrypt=true`をファイル/CLIで上書きしない。二者で承認対象と出力座標を照合する。

公開ソースをindexへ登録して `python3 scripts/ci/terraform_offline.py` を実行すると、新規directoryに両rootと参照scriptだけをexportする。
state/tfvars/private backend/saved plan/credentialのGit混入は、ignoreに加えてSecretゲート/exportのpath検査で拒否する。
provider lockfileはGitへ保持する。通常init/real plan/apply、backend接続、既存stateの有無確認はこの検証に含まれない。

TF-Cでは既存5 role/trust/MFA・管理用identity policy・SCP・account public blockを承認し、隔離AWS/synthetic stateだけで実効権限・unlock・監査配送・復旧を確認する。
management roleがkey policyを更新できることと、通常operatorがstateを削除できずlockを解放できることを両方確認する。
mock成功で本書2〜8節の停止条件を緩めない。実環境値/credentialを含むCLI出力、state/plan/backend cacheはartifactへ保存しない。
費用・保持・責任者・RPO/RTOが未合意ならTF-C/Dを開始しない。Issue #75はOPEN、本番運用NO GOを継続する。

## 10. TF-C0事前審査（2026-10-21）

[資料審査・BLOCKED台帳・sandbox試験計画](terraform-remote-state-preflight-2026-10-21.md)を追加した。
TF-A/TF-B採用や文書PRのmergeは実AWS実験の承認ではない。対象・権限・費用・担当・cleanup等のB01〜B14が未確認のため、TF-CはBLOCKEDのまま。
実AWS/remote init/実plan/apply/既存state・secretには今回アクセスせず、maintainerの別途明示承認を待つ。

# 検証済みmain SHAのEC2配備

**現在Deploy workflowはdisabled_manually、現行構成はAWS未配備として扱う。**
確認mainは`5f0fae4ebd89754e23538cb09f97a1ff037255ed`、Boot4.1.1/Java17。
以下は運用者向けの設計・手順であり、本番適用済みの記録ではない。[現行監査証跡](release-status.md)を参照。
現CIと[隔離デモ](demo.md)は同一image IDの故障設定からのapp-only復帰・DB不再作成を検証する。
異なる旧バイナリ/新schema/旧鍵へのrollback、実EC2上のdeploy.sh全経路は未検証。

Issue #17のCDは、既存の単一EC2/Compose構成でappだけを更新する。DB containerとvolumeは配備スクリプトで停止・再作成・削除しない。app切替時の短い停止はあり得る。

## 起動条件とSHA

```text
PR CI success / CI failure ────────────────> deploy jobを実行しない
main push → Spring Boot CI success
                    ↓ workflow_run.head_sha = DEPLOY_SHA
              production-deploy（同時実行1件、実行中はcancelしない）
                    ↓ runnerでsecrets/SHA検証
              同じSHAからscripts/deploy.shを読みSSHへ送信
                    ↓ EC2側flock → fetch → commit存在確認
              旧appのimage ID/Compose設定を保存
                    ↓ 順序ガード（Issue #157）: origin/main上か、稼働revisionからの前進か
                    ├─同一SHA/古いSHA → DEPLOY_RESULT=SKIPPED（何も変更せずexit 0）
                    ├─別枝/履歴分岐/状態不明 → DEPLOY_RESULT=FAILED（変更前に停止）
                    ↓ checkout --detach DEPLOY_SHA、HEAD一致確認
              Compose config → Docker内Java 17 Maven package → SHA tag image build
                    ↓ 再びHEAD一致確認
              appだけ切替 → HTTP確認
                    ├─成功 → .deploy/currentを確定
                    └─失敗 → 旧image ID/Compose設定でappだけ復元 → HTTP確認
```

deploy jobはconclusion=success、event=push、head_branch=mainの3条件をすべて要求する。現在のmain先頭へ追随して配備することはない。runner上のスクリプトとEC2上のソースはともにCI検証済みSHAへ固定する。GitHub側のconcurrencyに加え、EC2側の非待機flockが手動実行との競合も防ぐ。待機中のGitHub jobが新しい待機jobへ置き換えられる場合はあるため、全commitの配備は保証しない。

### 配備順序の契約（Issue #157）

`concurrency` と `flock` は**同時実行を防ぐだけで、新旧の順序は保証しない**。mainへA→Bの順にpushしても、CI(B)がCI(A)より先に成功すると、旧スクリプトはBの後にAを配備し、古いbinaryが「healthy」のまま稼働した（使い捨てGit履歴で再現済み）。
現在はflockの内側・`git fetch origin` の後に、次の判定を**build・切替より前**に行う。

| 状態 | 判定 | 結果 |
|---|---|---|
| `DEPLOY_SHA` が `refs/remotes/origin/main` の祖先でない（別枝、mainの履歴書換えで外れたSHA） | 拒否 | `DEPLOY_RESULT=FAILED` |
| app containerなし・`.deploy/current` なし（初回） | 配備 | `DEPLOY_RESULT=DEPLOYED target=…` |
| 稼働revision = `DEPLOY_SHA` | skip | `DEPLOY_RESULT=SKIPPED reason=already-running`（exit 0、変更なし） |
| 稼働revisionから `DEPLOY_SHA` へfast-forward（中間SHAを飛ばす場合を含む） | 配備 | `DEPLOY_RESULT=DEPLOYED` |
| `DEPLOY_SHA` が稼働revisionの祖先（後着の古いCI） | skip | `DEPLOY_RESULT=SKIPPED reason=stale`（exit 0、変更なし） |
| どちらの祖先でもない（mainの履歴書換え後など） | 拒否 | `DEPLOY_RESULT=FAILED` |
| 稼働image labelなし/不正、`.deploy/current` 欠落/不正形式/label不一致、containerなしでmarkerあり、稼働revisionが未知commit | 拒否（人手診断） | `DEPLOY_RESULT=FAILED` |
| 古さ判定の `git merge-base --is-ancestor` が1（祖先でない）以外で異常終了（128等） | 拒否（人手診断）。逆方向判定へ進まない | `DEPLOY_RESULT=FAILED` |

稼働revisionは**稼働imageの `org.opencontainers.image.revision` labelと `.deploy/current` の一致**で確定する。どちらか一方だけを信用しない。
切替直前にも、稼働image IDと `.deploy/current` が判定時から変わっていないことを再確認する（build中の手動変更への対策）。
許可するのは「最新mainだけ」ではなく、**origin/main上にあり、稼働revisionより新しいSHA**。後続CIが届けばさらに前進する。

skipはexit 0だが、`DEPLOY_RESULT=SKIPPED` 行と「No deployment was performed」を出力し、`.deploy/*` とappを変更しない。
**配備成功と誤認しないこと。** 現在Deploy workflowはdisabledのため、この行をGitHub job summaryへ転記する処理は未実装（ssh-actionの出力取得方式の検証が必要で、workflow再有効化時の作業）。
SKIPPEDは「配備を実施しなかった」ことだけを意味し、サービスの稼働やreadinessを保証しない。稼働revisionはapp containerのimage labelとmarkerで判定し、containerの `.State.Running` やHTTP readinessは見ないため、**停止中のcontainerでも同一SHAならSKIPPED（already-running）になる**。自動restart・自動復旧は行わない。稼働状態は運用者が `docker ps -a` と `/readyz` で別途確認する（本節の保証は配備順序であり、可用性ではない）。

**過去binaryへの意図的rollbackはこの自動経路では実行できない。** 管理者承認、DB migration互換性、対象imageの確認、停止計画が未設計のため、本PRでは実装しない。
配備失敗時に同一スクリプト内で直前imageへ戻す既存の復元は、このガードとは別で、従来どおり動作する（異版binary rollbackの受入とは別物）。

## 初回導入とGitHub設定

運用開始前にRepository Actions secrets/variablesを設定する（値をリポジトリ・PR・ログへ貼らない）。
Issue #37では参照するコード・契約だけを追加し、実際の設定・DB移行・EC2配備・Deploy再有効化は行っていない。

|Secret / Variable|用途|
|---|---|
|EC2_HOST|配備先ホスト|
|EC2_USER|配備先SSHユーザー|
|EC2_SSH_KEY|SSH秘密鍵|
|JWT_SECRET|固定fallbackのないJWT署名鍵。32 UTF-8 bytes以上、十分にランダムな値|
|DB_NAME（Variable）|専用userへ権限を付与するDB名。例asset_management|
|DB_USERNAME（Variable）|root以外の専用app user。例inventory_app|
|DB_PASSWORD（Secret）|app userのpassword。固定fallbackなし|
|DB_ROOT_PASSWORD（Secret）|MariaDB管理用。app containerには渡さない|
|APP_MEMORY_LIMIT（Variable）|実host/DB容量確認後のapp memory予算。m/g形式で明示。未設定なら切替前に失敗|

Issue #74のlogin制限は[接続元の信頼境界](auth.md#接続元とプロキシの信頼境界)を参照。
公開前に運用者がCaddy→appの実際のsocket peerを確認し、hostローカル `.env` の
`LOGIN_TRUSTED_PROXIES` に完全一致IPだけを設定する（環境/secretのdumpは不要）。
Docker bridge gatewayを含む可能性があり、loopbackやprivate CIDR全体を自動許可しない。
未設定・空はCompose解決とprod起動で拒否する（A-01/#97）。誤った非空peerや不正な転送情報はloginを503にする。
readinessがUPでも信頼設定の正しさは証明しないため、公開前に異なる実clientのloginを確認する。
旧global-limit overrideは起動時エラーとなるので除去し、max-concurrentとsource/pair/windowを実測に基づき調整する。
Deploy workflowの有効化や本番設定変更はこのPRでは行わない。

runnerはSSH前に9項目の非空とSHA形式、JWT鍵長、DB名/userの英字開始の英数字・underscore、root禁止、memory予算形式を検証する。
EC2側もDEPLOY_SHA/JWT_SECRET/DB設定を再検証し、SPRING_PROFILES_ACTIVE=prodを保証する。
DB URLはComposeがDB_NAMEからjdbc:mariadb://db:3306/DB_NAMEへ導出するため、別のDB_URL secretは不要。
DB_ROOT_PASSWORDは全serviceのCompose解決のためrunner/remoteで必要だが、app環境・datasourceには渡さない。
DB停止・再作成やroot password変更は行わない。既存DBのuser/資格情報は[DB移行手順](db/README.md#専用app-db-userへの切替issue-37)を先に実施する。
SSH actionのdebugやshellのset -xを有効にしない。EC2のログインshellがshでも配備処理は明示的にBashで実行する。

EC2にはBash、Git、BuildKit対応Docker Engine/Compose v2、curl、flock、公式image/Maven依存取得に必要な通信を用意する。
Issue #76からhost Maven/JDKによる事前jar生成は不要。Docker内のdigest固定JDKで1回だけpackageする。
既存Terraformが導入するJava 17は削除せず、旧配備手順の復旧時に利用できる。
配備ユーザーにはDocker実行権限とcheckoutの書き込み権限が必要。
[Docker runtime・メモリ設計](docker-runtime.md)で実容量とビルド時の余裕を確認してAPP_MEMORY_LIMITを選ぶ。

Issue #59の [Terraform host定義](../terraform/README.md) はAL2023 x86_64の新規hostへこれらを準備する。
Composeはv2.40.3/checksum固定、checkoutはec2-user所有の `/home/ec2-user/inventory-management-system`。
userdataはsecretを持たずapp/DB/volumeを起動・作成しない。bootstrap後のDB/secret準備は引き続き運用者が行う。
SSH CIDRを必須入力とし、SGの8080 ingressは削除した。localhostのreadinessには影響せず、
Issue #61はhost Caddy/EIP/public80・443とapp loopback bindを定義する。
初回DNS反映後のCaddy起動は [HTTPS手順](tls.md) の独立した運用者作業で、
通常app deploy/rollbackからCaddyは起動・reload・再作成しない。
localhost HTTP readiness・image ID/SHA・lock・redaction・DB非操作の契約を維持する。
Terraform apply・AWS操作・Deploy再有効化は今回未実施。

既定checkoutは `~/inventory-management-system`。事前に正しいoriginを持つcheckoutを用意し、配備前のソース・Composeが現在の稼働appと一致していることを確認する。スクリプトは未追跡の本番ファイルを削除せず、checkoutが変更に阻まれた場合は切替前に失敗する。`DEPLOY_DIR` は手動実行/テスト用に変更できるが、本番workflowは既定値を使用する。

初回はDBを運用者が別途準備・起動する。既存DBのFlyway導入は [DB手順](db/README.md) に従う。CDは起動済みinventory-dbとCompose project labelを要求し、そのprojectを引き継ぐ。appを初めて作る際にもDBを自動で作成しない。DBが停止中/存在しない場合は切替前に失敗する。

Issue #67ではDBのhost port publishを削除した。app/Flywayは内部`db:3306`、backup/restoreは
container内socketを使い、app-only deploy/rollbackはDBを再作成しない。
**mergeやapp配備だけでは既存DB containerの3306 bindingは消えない。**
運用者が既存のhost DB client依存を確認し、backup/復元検証と停止枠を準備して、
同じCompose project・volume・資格情報でDBだけを再作成する必要がある。
反映・実binding・DB health・readinessの確認は[DB接続手順](db/README.md#db接続とhost公開issue-67)に従う。
通常のdeploy scriptへのDB操作追加、Deployの有効化、SG/Caddy/TLS変更は行わない。

Issue #17からDeploy workflowはGitHub上で無効化されている。Issue #37でも再有効化しない。
workflow_runはdefault branch上の定義を使用する。運用者が修正版のmerge/main CI、全必須secret/variables、
既存DB専用user・権限・資格情報、EC2前提、旧app rollback互換性を確認した後に再有効化を判断する。
本Issueで運用開始済みとは扱わない。

参照: [GitHub workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)、[Composeで単一serviceを再配備](https://docs.docker.com/compose/how-tos/production/)。

GitHub environmentは現在未設定のため必須にしない。運用時はproduction environmentを作り、mainだけのdeployment branch制限と、プランで利用できる場合はrequired reviewersを設定したうえでdeploy jobへ `environment: production` を追加することを推奨する。

## Build、imageと状態

target SHA checkout/HEAD検証後、既存app稼働中に次を完了する。

1. 必要ファイル・APP_MEMORY_LIMIT・commit timestampを確認し、Docker内の固定Java 17でjar生成
2. `docker compose config`（出力にはsecretがあるため私有ファイルへ保存）
3. `inventory-management-system-app:${DEPLOY_SHA}` のbuildとrevision/health-path label検証
4. 切替直前のHEAD一致確認

Composeのimageは `inventory-management-system-app:${APP_IMAGE_TAG:-local}`。localでは従来どおりbuildを使用できる。配備時はSHA tagに加えて実際にbuildしたimage IDで固定し、`up -d --no-deps --no-build --pull never --force-recreate app` でappだけを更新する。切替中の再build/pullやDB再作成は行わない。

`.deploy/` はGit管理外・Docker build context外で、umask 077により配備ユーザーだけが読める状態を作る。current/previousはrevision、current-image/previous-imageは不変のimage ID、current-compose.yml/previous-compose.ymlは解決済みCompose設定。**設定ファイルにはsecretsが含まれるため共有しない。** source checkoutのHEADは失敗したtargetに残る場合があるので、稼働revision判定にHEADだけを使わない。

直前imageは実際のinventory-appから取得し、成功後にだけ新currentを記録する。旧CDからの初回移行では、image labelがなければ旧revisionはunknownとなるがimage IDで復元できる。旧appのJWT環境を使い、切替前checkoutのComposeを保存する。以降は前回成功時の保存済みComposeを再利用する。

自動image pruneは行わない。current/previousと稼働containerが参照するimageは必ず残す。運用者は容量を監視し、それらのimage IDに該当しない古いSHA tagだけを個別に削除する。無差別なsystem pruneは使用しない。

## HTTP確認と失敗処理

新targetは`http://127.0.0.1:8080/readyz`のHTTP 200かつ
`{"status":"UP"}`だけを受け付ける（JSON内の空白は許可）。DOWNや追加detailsがあれば成功扱いにしない。
response bodyは私有ファイルへ保存し、ログへ出さない。healthは匿名可でdetails/componentsは認証済みでも非公開。
readinessはSpringのreadinessStateとDB状態を確認し、REFUSING_TRAFFIC/DB利用不能を拒否する。
`/livez`はlivenessStateのみで、DB障害を再起動理由にしない。全業務機能の検証は別途必要。
prodのSwagger/OpenAPIはendpointを生成せず404となる。

切替前の実image IDからinventory-management-system.health-path labelをsnapshotする。
Issue #55以降のimageは/readyz、それ以前の/actuator/health label付きimageもrollback時は宣言した経路を使う。
labelのない旧imageへのrollbackに限り、旧/v3/api-docsの200確認を使用する。
新targetのlabelはbuild後に検証し、新imageから旧OpenAPIへfallbackしない。未知のlabelは切替前に拒否する。

curlはconnect timeout 2秒、全体timeout 5秒、最大30試行、失敗ごとに2秒間隔。1回の遅延で失敗扱いせず、無限に待たない。timeout最大約210秒でtargetを判定し、rollback後も同じ上限で確認する。

|失敗地点|動作|
|---|---|
|secrets/SHA/memory形式/commit取得/DB前提/checkout/Compose/Docker内Maven/build|app切替なし、既存app継続|
|target app起動/HTTP確認/image ID検証|直前image IDと保存Composeでappだけ復元、復元後もHTTP確認|
|rollback app起動/HTTP確認|CDは失敗、明確なoperator recovery errorとログを出す|
|初回appの起動/HTTP確認失敗|復元先がない旨を明示、DBと失敗appを保存して調査|

target失敗・rollback失敗時はappログ末尾100行を出す。現在のJWT/DB_PASSWORD/DB_ROOT_PASSWORDと旧/新container環境のPASSWORD/SECRET/TOKEN値を伏せる。Composeの解決済み設定や秘密鍵はログへ出さない。任意に加工されたsecretをアプリがログへ出すケースまで完全な検知はできないため、アプリ側でもsecretのloggingを禁止する。

環境値はNUL区切りで取得し、改行を含む値も保持してログ全体から伏せる。旧JWT値も同じ方法で保存するため、改行を含む鍵を復元時に切り詰めない。

rollback成功でも配備jobは失敗終了する。target成功と扱わず、新currentへ確定しない。ホスト強制終了・電源断・通信切断などで復元処理を最後まで実行できない場合は、運用者が下記の手順で状態を調べる。

切替後に失敗した場合は、旧image/revision/Composeを含む私有recovery snapshotを `.deploy/pending.*` に残し、場所だけをログへ出す。旧CDからの初回移行で復元に失敗しても設定を失わない。復旧完了後、運用者が不要なsnapshotを個別に削除する。成功/切替前失敗時は一時snapshotを削除する。

## Flywayとrollbackの限界

Issue #69の[当時の互換性判断](db/flyway-mariadb-compatibility.md)はFlyway11.7.2/MariaDB12.2の記録として保持する。
現行Boot4のSBOMではFlyway12.4.0で、MariaDB12.2互換性WARNは残る。旧検討版へ戻す根拠にしない。
全回帰成功を上流の12.2認証とみなさず、互換性WARNを非表示にしない。
12.11.0の単独更新はJacksonの不整合で起動に失敗したため採用していない。
本Issueの反映でFlyway/DB tagを上書きしたり、既存volumeのDBをupgrade/downgradeしたりしない。
Deploy再有効化・本番操作は本Issueに含めない。

**app image rollbackはDB rollbackではない。** versioned migrationを自動undo/downしない。Flyway migrationはtarget app起動時に実行される。失敗時はHTTP確認が通らず旧appへの復元を試すが、MariaDB DDLは完全にtransactionalではなく、一部DDLが適用済みの可能性がある。

旧appと新schemaの互換性をmigration reviewで確認する。expand/contractで追加→両version対応→旧appを使わなくなってから削除、など後方互換を維持する。互換性のないschema変更は配備前にrollback可否、停止計画、DB backup/restore計画を判断する。

旧appも起動不能なら自動復元成功とみなさない。DB運用者が状態・Flyway history・適用済みDDLを確認し、forward-fixまたは検証済みbackupからの復元を選ぶ。本スクリプトはデータ削除、baseline、repair、DB復元を自動で行わない。

## 調査と手動復旧

1. ActionsのSHA/失敗段階/伏せ字ログと、EC2上のcurrent/current-image/previous/previous-imageを確認する。Compose設定ファイル全体は共有しない。
2. `docker ps -a` と伏せ字appログ、新imageは`curl --fail http://127.0.0.1:8080/readyz`で200/UPを確認する。
   旧/actuator/health label付きimageは宣言した経路を使う。
   Issue #37以前のlabelのないimageへの復元確認だけ旧/v3/api-docsを使う。DB container/volumeは保持する。
3. rollback不能なら [DB手順](db/README.md) を参照し、先にschema互換性を調べる。旧imageを試すだけでDBが戻ったと判断しない。
4. 手動復元を行う際も `.deploy/lock` をflockで保持し、保存Composeにprevious-imageのimage IDをoverrideしてappだけを更新する。current/previousは復元後HTTP成功を確認して整合させる。
5. host lock競合、ディスク/メモリ不足、missing secret、DB停止、Docker build取得失敗、Git checkout変更を解消後、検証済みSHAの配備を再実行する。GitHub CI runをre-runして起動条件を再評価できるが、PR runは常に配備対象外。
   ただし**初回appの起動/HTTP確認失敗後は、そのまま再実行しても配備されない**。失敗appは調査用に残り `.deploy/current` は作られないため、次回は「Running app has no deployed revision marker」でFAILEDになる（自動削除・自動再試行はしない）。運用者が原因と状態を調査したうえで復旧方法を判断する。DB container/volume・secretは変更しない。

## 自動検証

`./mvnw verify` が既存JWT/Validation/状態遷移/並行制御/集約/認可/DB migration全テストとDeploymentWorkflowTest/DeploymentScriptTestを実行する。Deploymentテストは本番配備を行わない静的・mockテストのためSurefireに残し、`./mvnw test` でも実行する。YAMLはBoot依存のSnakeYAMLで構造を解析し、triggerの全8組み合わせ、SHA伝達、concurrency、権限、Compose tag、禁止操作を検証する。runnerの不足secretガードもBashで実行する。

shellのモックはGit/Docker/curl/flock/sleepを隔離したPATHで置き換え、host Java/Maven非実行、invalid/missing SHA、memory不足/不正、
DB/JWT不足・弱い鍵・root/不正identifier、host lock、取得失敗、HEAD不一致、DB停止、
Maven/config/Docker失敗、成功/初回成功、リトライ、DOWN/details拒否、起動失敗、HTTP失敗、
legacy/modern rollback、rollback失敗、初回失敗、DB/root/JWTのログ伏せ字を検証する。
EC2接続や本物のDocker変更は実行しない。

`DeploymentOrderTest`（Issue #157）は `scripts/tests/deploy-order-test.sh` で、**本物のGit**の使い捨てbare origin＋配備cloneにA→B→C・別枝X・履歴書換えを作り、
Docker/curl/flock/sleepだけをmockして実際の `deploy.sh` を複数回実行する。CI完了逆転（B配備後のA）、初回、同一SHA、前進、中間SHA後着、別枝、
履歴書換え、marker欠落/不一致/不正形式、label欠落、未知commit、containerなしmarker、build中の稼働変更、
古さ判定のGit異常終了（初回の祖先判定だけexit 128を注入し、逆方向判定は成功し得る条件）の15状態で、稼働revision・marker・build/切替有無・`DEPLOY_RESULT` を検証する。
資格情報・network・SSH・AWS・本物のDockerは使わない。

個別実行: `./mvnw -Dtest=DeploymentWorkflowTest,DeploymentScriptTest,DeploymentOrderTest test`。
WindowsではJava 17とGit Bash（既定C:/Program Files/Git/bin/bash.exe）が必要。追加のshell test frameworkは不要。

# 検証済みmain SHAのEC2配備

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
                    ↓ checkout --detach DEPLOY_SHA、HEAD一致確認
              Java 17 Maven package → Compose config → SHA tag image build
                    ↓ 再びHEAD一致確認
              appだけ切替 → HTTP確認
                    ├─成功 → .deploy/currentを確定
                    └─失敗 → 旧image ID/Compose設定でappだけ復元 → HTTP確認
```

deploy jobはconclusion=success、event=push、head_branch=mainの3条件をすべて要求する。現在のmain先頭へ追随して配備することはない。runner上のスクリプトとEC2上のソースはともにCI検証済みSHAへ固定する。GitHub側のconcurrencyに加え、EC2側の非待機flockが手動実行との競合も防ぐ。待機中のGitHub jobが新しい待機jobへ置き換えられる場合はあるため、全commitの配備は保証しない。

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

Issue #74のlogin制限は[接続元の信頼境界](auth.md#接続元とプロキシの信頼境界)を参照。
公開前に運用者がCaddy→appの実際のsocket peerを確認し、hostローカル `.env` の
`LOGIN_TRUSTED_PROXIES` に完全一致IPだけを設定する（環境/secretのdumpは不要）。
Docker bridge gatewayを含む可能性があり、loopbackやprivate CIDR全体を自動許可しない。
未設定でも制限は有効だがproxy背後でsource枠を共有する。閾値/windowはSpring外部設定で調整できる。
Deploy workflowの有効化や本番設定変更はこのPRでは行わない。

runnerはSSH前に8項目の非空とSHA形式、JWT鍵長、DB名/userの英字開始の英数字・underscore、root禁止を検証する。
EC2側もDEPLOY_SHA/JWT_SECRET/DB設定を再検証し、SPRING_PROFILES_ACTIVE=prodを保証する。
DB URLはComposeがDB_NAMEからjdbc:mariadb://db:3306/DB_NAMEへ導出するため、別のDB_URL secretは不要。
DB_ROOT_PASSWORDは全serviceのCompose解決のためrunner/remoteで必要だが、app環境・datasourceには渡さない。
DB停止・再作成やroot password変更は行わない。既存DBのuser/資格情報は[DB移行手順](db/README.md#専用app-db-userへの切替issue-37)を先に実施する。
SSH actionのdebugやshellのset -xを有効にしない。EC2のログインshellがshでも配備処理は明示的にBashで実行する。

EC2にはBash、Git、Java 17（既定JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto）、Docker Engine/Compose v2、curl、flock、Mavenの依存取得に必要な通信を用意する。配備ユーザーにはDocker実行権限とcheckoutの書き込み権限が必要。

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

1. 必要ファイルとJava 17検証、`./mvnw clean package -DskipTests`、jar存在確認
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
|secrets/SHA形式/commit取得/Java/DB前提/checkout/Maven/Compose/build|app切替なし、既存app継続|
|target app起動/HTTP確認/image ID検証|直前image IDと保存Composeでappだけ復元、復元後もHTTP確認|
|rollback app起動/HTTP確認|CDは失敗、明確なoperator recovery errorとログを出す|
|初回appの起動/HTTP確認失敗|復元先がない旨を明示、DBと失敗appを保存して調査|

target失敗・rollback失敗時はappログ末尾100行を出す。現在のJWT/DB_PASSWORD/DB_ROOT_PASSWORDと旧/新container環境のPASSWORD/SECRET/TOKEN値を伏せる。Composeの解決済み設定や秘密鍵はログへ出さない。任意に加工されたsecretをアプリがログへ出すケースまで完全な検知はできないため、アプリ側でもsecretのloggingを禁止する。

環境値はNUL区切りで取得し、改行を含む値も保持してログ全体から伏せる。旧JWT値も同じ方法で保存するため、改行を含む鍵を復元時に切り詰めない。

rollback成功でも配備jobは失敗終了する。target成功と扱わず、新currentへ確定しない。ホスト強制終了・電源断・通信切断などで復元処理を最後まで実行できない場合は、運用者が下記の手順で状態を調べる。

切替後に失敗した場合は、旧image/revision/Composeを含む私有recovery snapshotを `.deploy/pending.*` に残し、場所だけをログへ出す。旧CDからの初回移行で復元に失敗しても設定を失わない。復旧完了後、運用者が不要なsnapshotを個別に削除する。成功/切替前失敗時は一時snapshotを削除する。

## Flywayとrollbackの限界

Issue #69の[互換性判断](db/flyway-mariadb-compatibility.md)では、Boot管理Flyway 11.7.2とMariaDB 12.2を維持する。
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
5. host lock競合、ディスク不足、missing secret、DB停止、Java version、Git checkout変更を解消後、検証済みSHAの配備を再実行する。GitHub CI runをre-runして起動条件を再評価できるが、PR runは常に配備対象外。

## 自動検証

`./mvnw verify` が既存JWT/Validation/状態遷移/並行制御/集約/認可/DB migration全テストとDeploymentWorkflowTest/DeploymentScriptTestを実行する。Deploymentテストは本番配備を行わない静的・mockテストのためSurefireに残し、`./mvnw test` でも実行する。YAMLはBoot依存のSnakeYAMLで構造を解析し、triggerの全8組み合わせ、SHA伝達、concurrency、権限、Compose tag、禁止操作を検証する。runnerの不足secretガードもBashで実行する。

shellのモックはGit/Docker/curl/Java/Maven/flock/sleepを隔離したPATHで置き換え、invalid/missing SHA、
DB/JWT不足・弱い鍵・root/不正identifier、host lock、取得失敗、HEAD不一致、DB停止、
Maven/config/Docker失敗、成功/初回成功、リトライ、DOWN/details拒否、起動失敗、HTTP失敗、
legacy/modern rollback、rollback失敗、初回失敗、DB/root/JWTのログ伏せ字を検証する。
EC2接続や本物のDocker変更は実行しない。

個別実行: `./mvnw -Dtest=DeploymentWorkflowTest,DeploymentScriptTest test`。
WindowsではJava 17とGit Bash（既定C:/Program Files/Git/bin/bash.exe）が必要。追加のshell test frameworkは不要。

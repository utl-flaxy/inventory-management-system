# 初期ADMIN provisioning（Issue #77）

Spring Boot 4.1.1 / Java 17、MariaDBの新規環境用。公開registerはROLE_USER固定で、username `admin`も一般ユーザー。
HTTP endpoint、起動runner、seed/default credentialは追加しない。V1〜V6とDB role CHECKを維持し、B1の追加V7に対応する。
以下は承認済み新規環境で運用者が明示的に行う手順であり、Deploy workflowからは呼び出さない。

## 方式と実行権限

|方式|評価|
|---|---|
|既存USERの昇格|同名を先に公開登録した人を誤って昇格するリスク。既存hash/所有者の確認とUPDATE権限が必要。採用しない。|
|新規初期ADMINのINSERT|運用者が新しい専用usernameとpasswordを選ぶ。既存アカウントを上書きせず、DB権限をSELECT/INSERTに限定できる。採用。|

実行権限は、レビュー済みjar/imageを実行するOS/Docker権限と、対象DBのprovisioning用アカウントへのアクセス。
HTTP/JWTの一般ユーザーにはこの権限を与えない。DB管理者は新規環境で専用DB userを安全な認証手順で用意し、
対象databaseの`users`にSELECT(username,role) / INSERT(username,password,role,auth_uid,token_version)、
`flyway_schema_history`にSELECT(version,success,checksum)だけを付与する。
既存password hashやidのSELECT、UPDATE/DELETE/DDL/global権限/GRANT OPTIONは不要。rootはCLIで拒否する。
DB user作成のsecretをSQL履歴・引数・repoへ書く例は提供しない。認証情報は承認済み保管先から非表示で入力する。
完了後は専用DB userのアクセスを失効し、保管先の一時権限も閉じる。アプリ用DB userの権限・secretは変更しない。

## 排他と再実行契約

1. 入力・MariaDB接続設定を検証。接続URLにuser/passwordや任意のdriver設定を含めない。
2. 新しい非pool接続でusersのInnoDB・既存UNIQUE/CHECKを確認。未準備ならDDLやmigrationをせず失敗。
3. database名を正規化したhashから共通64文字lock名を作り、`GET_LOCK`を最大10秒待つ。
4. ロック取得後にREAD COMMITTED transactionを開始し、ADMIN存在→username衝突の順で検査する。
5. ADMINが1件以上ならpasswordを新たに要求せず`ADMIN_PRESENT`。同名USERがいれば`USERNAME_TAKEN`。
6. password確認・16〜72文字かつUTF-8で72 bytes以下の検証後、既存アプリと同じBCrypt cost10でhash化。
7. parameterized INSERTにROLE_ADMINを固定しcommit。例外時はrollback。owned接続を必ずcloseしてlockを解放する。

username UNIQUEは並行public registerとの最終防御。登録が入力待ち中に同名を取ればINSERTが失敗し、昇格や上書きはしない。
ADMINのpassword/role/idをUPDATEするSQLはない。BCrypt saltは毎回生成し、passwordをtrimしない。
ロックはtransactionのcommitでは解放されないため、接続をpoolへ返さない。
[MariaDB GET_LOCK公式仕様](https://mariadb.com/docs/server/reference/sql-functions/secondary-functions/miscellaneous-functions/get_lock)に従い、
正常/異常な接続終了で解放する。入力中はロックを保持するので、別実行は10秒でLOCK_BUSYとなる場合がある。

対象は全実行が同一の単一writable MariaDB endpointへ接続する構成。Galera `wsrep_on=1`は拒否する。
別server/multi-primary routingや、DB権限を持つ人の直接SQLはこの協調ロックで防げない。将来の追加ADMIN運用は別レビューとする。
全ADMINを別作業で削除した場合の再作成を恒久markerで禁止する機能はない。既存ADMINが存在する限り再実行は変更を行わない。

## 明示的な対話実行

先に通常の新規環境セットアップでV1〜V7を適用し、Hibernate validateとreadinessを確認する。
CLIはV7成功履歴/checksumと認証制約を確認し、新規uidをJava UUIDv4で生成、世代0を明示INSERTする。
既存ADMINのuid/世代/hash/roleを変更しない。旧CLIのuidなしINSERTは新schemaで拒否されるため、同じB1版を使用する。
provisioningはmigrationを実行しない。DB接続先、専用DB user、新しいADMIN usernameを運用者が照合する。
設定は以下の3つ。password/JWTを環境変数へ追加する必要はない。

|環境変数|値|
|---|---|
|PROVISION_JDBC_URL|`jdbc:mariadb://db:3306/inventory`等。remote接続では検証済みTLSの`?sslMode=verify-full`または承認済みsecure tunnelを使用。|
|PROVISION_DB_USER|専用の最小権限DB user。root不可。|
|PROVISION_ADMIN_USERNAME|新しく作る3〜50文字の半角英数字/`.`/`_`/`-`。デフォルトなし。|

承認済みjarをJava 17の対話terminalで実行する。`System.console()`がなければ拒否し、自動的にstdinへfallbackしない。
classpath/LOADER設定を変更した未検証jarやdebug/heap dump付きJVMを使わない。

```bash
java -Dloader.main=com.example.demo.provisioning.InitialAdminCommand \
  -cp /path/to/reviewed/demo-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

[Boot executable jar仕様](https://docs.spring.io/spring-boot/specification/executable-jar/launching.html)のnested dependenciesを使用する。
通常の`java -jar`のStart-ClassはDemoApplicationのままで、ADMINを作成しない。
DB password、新規ADMIN password、確認passwordを非表示入力する。既存ADMIN/username衝突では新規passwordを要求しない。
password managerで生成した十分に長い専用passwordを用い、端末録画・shell tracing・clipboard履歴に注意する。

Dockerではレビュー済み新imageと新規環境のprivate networkを明示する。これは既存DBを再作成するCompose操作ではない。
`provision-settings.env`は上記3設定だけを含むアクセス制限済みファイルで、password/JWTを含めない。
memoryは別のCLI一時コンテナの予算であり、稼働app/DBの上限を変更しない。ホストの余裕を事前に確認する。

```bash
docker run --rm -it --network VERIFIED_NEW_NETWORK \
  --read-only --user 10001:10001 --cap-drop ALL \
  --security-opt no-new-privileges:true --pids-limit 64 \
  --memory 256m --memory-swap 256m --ulimit core=0 \
  --tmpfs /tmp:rw,noexec,nosuid,size=16m,mode=1777 --no-healthcheck \
  --env-file /private/provision-settings.env --entrypoint java REVIEWED_IMAGE \
  -Dloader.main=com.example.demo.provisioning.InitialAdminCommand \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

offline CLIはHTTP serverではないため、この一時コンテナだけhealthcheckを付けない。appの/readyz・healthcheck・non-root・read-only・memory契約は変更しない。
本番Composeの`.env`を丸ごとCLIへ渡さない。JWTやroot passwordをprovisioning processへ持ち込まない。

## 管理された非対話入力と秘密情報の破棄

CIの使い捨て環境や承認済みsecret manager integrationだけで、専用entry pointに`--passwords-stdin`を明示できる。
UTF-8 newline区切りのDB password / 新規password / 確認passwordの3 recordsをstdin pipeに直接渡す。
対話consoleがある状態ではこのmodeを拒否する。shellのecho/printf/here-doc、平文CLI引数、password入りenv/fileで代用しない。
EOF・不正UTF-8・4096 bytes超record・不一致は非ゼロ終了。secret producerは値をstdoutログへ記録しない構成をレビューする。

CLIはDB passwordを接続直後に、password/確認char[]とUTF-8変換bufferをfinallyで上書きする。
[Java Consoleの指針](https://docs.oracle.com/javase/jp/17/docs/api/java.base/java/io/Console.html)に沿い、secretのString化を最小化する。
JDBC/BCrypt内部のimmutable String/一時copyとOS pipe bufferの完全消去までは保証できない。
短命な専用processの終了と一時コンテナ削除で寿命を限定し、heap/core dump/debug、他人からのprocess inspectionを避ける。
専用processの依存loggerはOFFにし、例外cause/stack/SQL/credentialを出力せず、下記の状態コードと非ゼロexitで失敗を通知する。
通常アプリのlogger・監査イベント・scannerログは変更しない。入力値・hash・JWT・DB credentialをCI artifactへ保存しない。

## 結果、既存ADMIN、失敗時の復旧

|状態|exit|対応|
|---|---:|---|
|CREATED|0|作成完了。通常/loginで認証し、ADMIN APIと一般USER境界を確認。専用DBアクセスを失効する。|
|ADMIN_PRESENT|0|変更なし。別username/別passwordでも追加・再設定しない。既存ADMINの承認済み運用を使う。|
|USERNAME_TAKEN|65|変更なし。同名USERを昇格しない。新しい専用usernameを選び、再度明示実行する。|
|LOCK_BUSY|75|別実行の終了を確認してから再実行。DB lockを無断killしない。|
|SCHEMA_NOT_READY|78|schema/既存constraint/engine/単一serverを確認。CLIでDDL/repairをしない。|
|CONSOLE_REQUIRED / INVALID_INVOCATION / INVALID_INPUT|64|入力方式・設定・password条件を直す。値をログに貼らない。|
|DATABASE_FAILED|74|接続/TLS/最小権限/DB稼働を確認。raw exceptionをsecretと一緒に公開しない。|

commit直後の通信切断などは作成有無が不確定になり得る。**同じコマンドを明示再実行**すると、作成済みならADMIN_PRESENTで上書きしない。
自動reset、ユーザー削除、volume初期化、migration repairを復旧策にしない。既存ADMINのcredential喪失/追加ADMIN/権限変更は本機能の対象外で、別の承認済み手順を要する。
CLIの対象DBを誤った場合はそこで止め、承認なしに削除・権限変更で取り消さない。

## 検証範囲

新規MariaDB Testcontainersで、fresh作成/BCrypt/列単位SELECT+INSERTのみ（既存hash/id SELECT拒否）/再実行/異なる名前の並行実行/入力abortとrollback/unique競合/DB CHECK/
public admin username・role無視/JWTの401・403・ADMIN成功/credential非露出/実app再起動を検証する。
Docker実検証では別の3 CLI processから競合実行し、1 ADMINのみ、再実行hash/id/role不変、誤DB passwordの非ゼロ・redactionを確認し、
そのADMINで既存JWT/注文/在庫の実HTTP回帰を行う。使い捨てDBはtmpfsで、既存volumeを使用しない。
本番のDB user作成、credential取得、権限変更、AWS/Deploy操作はこのPRの検証に含めない。

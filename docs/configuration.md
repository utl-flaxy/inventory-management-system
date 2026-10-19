# 設定・profile・secret（Issue #37）

## Profiles

| profile | Swagger / OpenAPI | H2 console | SQL stdout / format | 公開Actuator |
|---|---|---|---|---|
| 未指定/default | 無効・404 | 無効・404 | false / false | healthのみ |
| local | 明示的に有効 | 無効 | false / true | healthのみ |
| prod | 無効・404 | 無効・404 | false / false | healthのみ |

profile付け忘れでも開発endpointは公開しない。localとprodを併用せず、productionはprodを指定する。
Springdoc endpointはdefault/prodで生成しない。Securityにも無効URLの404処理を置き、
JWTの有無によって401へ変わらない。localでのみSwaggerをpermitする。
H2はtest scopeでproduction jarに含まない。全環境のframeOptions.disableを撤廃し、
Spring Security標準のX-Frame-Options: DENYを使う。業務APIのJWT/role/所有者認可は維持する。

## Persistence context / OSIV（Issue #73）

共通の `application.properties` で `spring.jpa.open-in-view=false` を指定し、
default/local/prodすべてが継承する。HTTP request全体を覆うEntityManagerは登録しない。
JSONを送信する前に、ServiceのTransaction内で必要な関連を読み、独立したDTOへ変換する。
Controllerは注文Entityを受け取らず、customer/itemsを含む完成済みのOrderResponseだけを扱う。

Productの一覧・検索・詳細はmethod単位で `@Transactional(readOnly=true)`。
Product更新・削除、注文作成・状態操作、Inventory調整のREAD_COMMITTEDは維持する。
注文一覧のREPEATABLE_READ、Inventory履歴とSalesのread-onlyも維持する。
注文のHTTP用DTO入口は既存のEntity用業務メソッドと同じTransaction属性を持つ。
同一Service内の呼出しは外側のTransactionを使い、追加のTransactionを開始しない。
書込み応答はDTOへのコピー前にflushし、`@Version` と `@PreUpdate` の値を確定する。
commit/rollbackは引き続きServiceのTransaction終了時に行う。

Auth登録とProduct登録は関連を持たないscalar fieldだけの応答であり、既存Repositoryの書込み
Transactionを維持する。AuthのBCrypt・JWT・試行制限や同名登録の409処理は変更しない。
CSVはJSON変換と異なり、同期HTTP出力を既存のread-only REPEATABLE_READ内で行う。
500行単位のprojection取得と逐次送信は意図した処理で、OSIVやEntityの遅延SELECTへ依存しない。
長時間exportのsnapshot/connection保持の制限は[CSV仕様](order-csv.md)を参照する。

実効設定・Web interceptor/filterの不在・JSON converter時のセッション解放は
[OsivApiIT](../src/test/java/com/example/demo/controller/OsivApiIT.java)で3 profileの実HTTP/MariaDBを検証する。
設定値だけではsession境界の検証済みとは扱わない。

## Required environment

Spring Boot標準bindingを使い、application.propertiesにDB接続情報を置かない。

| environment | 意味 |
|---|---|
| SPRING_DATASOURCE_URL | MariaDB JDBC URL。credentialをURLへ埋め込まない |
| SPRING_DATASOURCE_USERNAME | 専用app user。prodはroot禁止 |
| SPRING_DATASOURCE_PASSWORD | app userのpassword。固定fallbackなし |
| JWT_SECRET | 既存の必須署名鍵。32 UTF-8 bytes以上のランダムなsecret |
| SPRING_PROFILES_ACTIVE | localまたはprod。未指定はsafe default |
| APP_LOGIN_RATE_LIMIT_TRUSTED_PROXIES | prodでは実測したCaddy transport peerの正確なIP一覧。ComposeではLOGIN_TRUSTED_PROXIESから注入 |
| APP_MEMORY_LIMIT（Compose専用） | host/DB/buildの余裕を確認して選ぶapp containerのRAM/swap上限。jar単独起動のJVM設定ではない |

アプリへ直接渡す任意の`JWT_ISSUER`（既定`inventory-management-system`）と
`JWT_EXPIRATION_MS`（既定`3600000` ms）は、`app.jwt.issuer` / `app.jwt.expiration-ms`へ対応する。
実稼働値は未確認。署名・issuer・期限検証は維持し、既発行JWTの期限は設定変更では変わらない。
Composeでの注入範囲は[下記](#docker-compose-startup)、失効制約は[認証契約](auth.md#jwtライフサイクルと失効制約)を参照。
prodではBeanFactoryPostProcessorがDataSource/Flywayのbean生成前に不足/blank、root、
URL内credential、弱いJWT鍵を拒否する。例外には設定名だけを含め、値や原因のURLを含めない。
既存TestcontainersのDynamicPropertySourceはcanonical datasource propertyを直接渡せる。
production設定不足を無効化するtest profileは作らない。

## Local startup

Java 17とMariaDBを用意し、[DB専用user手順](db/README.md#専用app-db-userへの切替issue-37)に従う。
private shellで上記のdatasource/JWT environmentを設定し、次を実行する。

```bash
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

WindowsはPowerShellで同じenvironmentを設定し、`mvnw.cmd spring-boot:run`。
Swaggerは `http://localhost:8080/swagger-ui.html`、OpenAPIは `/v3/api-docs`。
show-sqlはlocalでもfalse。format_sqlだけtrueとし、不要なSQL出力を増やさない。

## Docker Compose startup

`.env.example`はsecretが空のテンプレートで、そのままでは起動しない。
新規環境用のprivateな`.env`へコピーして、独立したDB_PASSWORD、DB_ROOT_PASSWORD、JWT_SECRETを設定する。
DB_NAME/DB_USERNAMEは英字開始の英数字とunderscoreのみ、DB_USERNAMEはroot以外。
APP_MEMORY_LIMITとLOGIN_TRUSTED_PROXIESも必須。前者は[容量別予算](docker-runtime.md#jvmとcontainerの予算)、
後者は[アプリが観測する信頼proxyの確認手順](auth.md#運用者の設定移行復旧)に従って決める。
Docker gatewayやCaddyのlocalhostを推測で転記しない。両値とも空ならCompose解決を拒否する。
信頼peer設定の不足/形式不正はprod起動時にも拒否する。非空でも経路と合わないpeerではloginが503になり得る。
1GiB hostでappとDBを安全に同居できるとは扱わず、現在の実host容量は未確認のままにする。
POSIXでは`.env`をchmod 600とする。Windowsでも利用者だけが読めるACLを使う。
`.env`/`.env.*`はignoreし、安全な`.env.example`だけcommitする。

以下は運用者が**新規環境**を用意する場合の手順であり、既存container/volumeを使う検証コマンドではない。
手元の動作確認は[固有名の使い捨てDockerデモ](demo.md)を使う。この文書PRでは下記の通常Compose起動や本番操作は実行しない。

```bash
./mvnw verify
docker compose config --quiet
docker compose up -d --build
```

hostでのjar事前生成は不要。[Dockerfile](../Dockerfile)がJava17 JDK stage内でMaven依存をcacheし、
ソースからpackageしてJRE stageへjarを渡す。image生成内のskipTestsは直前のfull verifyを置き換えない。
`config --quiet`は必要な値の解決検証であり、容量や信頼peerが実環境で正しいことの証明ではない。
Composeはprodを明示する。datasource URLは
`jdbc:mariadb://db:3306/${DB_NAME}`として解決し、appには専用user/passwordだけを渡す。
DB root passwordはDB containerだけに渡す。新しい空volumeではMariaDB公式imageが
MARIADB_USER/MARIADB_PASSWORDによりuserを作った後、read-only mountした
scripts/db-init/01-app-privileges.shが対象DBの必要なDDL/DMLだけへ権限を絞る。
global権限・GRANT OPTIONなし。既存volumeではuser作成もこの初期化scriptも実行されないため、
[既存DBの手順](db/README.md#専用app-db-userへの切替issue-37)を先に実施する。
実値を含む`docker compose config`の標準出力を共有・CI artifact化しない。

JWT用環境変数は`JWT_SECRET: ${JWT_SECRET:?JWT_SECRET is required}`だけをappへ明示注入する。
未設定・空ならCompose解決時に拒否する。`JWT_ISSUER` / `JWT_EXPIRATION_MS`は現行Composeに
転送設定がなく、hostや`.env`へ置くだけではappコンテナに渡らない。
上書きが必要な場合の注入変更は別途レビューし、この文書化ではComposeの実行設定を変更しない。
詳細は[JWT設定](security/jwt-configuration.md)を参照。

## Production startup / health

jar単独起動ではprivate environmentでdatasource URL/user/password、JWT_SECRETに加え、
`APP_LOGIN_RATE_LIMIT_TRUSTED_PROXIES`へ実測した信頼peerを設定し、`SPRING_PROFILES_ACTIVE=prod`でJava17のjarを起動する。
prodの`require-trusted-proxy=true`、`server.forward-headers-strategy=framework`、登録/ログイン制限の検証を維持する。
不正/不足設定の起動拒否は[ProductionConfiguration](../src/main/java/com/example/demo/config/ProductionConfiguration.java)が正本。
Composeの場合はLOGIN_TRUSTED_PROXIESの転送とAPP_MEMORY_LIMITの明示指定も必要で、
その値をjar用の環境変数と無条件に同一視しない。jar単独起動にも別途host/JVMのメモリ予算が必要。
Flyway enabled、ddl-auto=validate、baseline-on-migrate=false、clean-disabled=trueを維持する。
schemaの正本は[Flyway V1〜V6/V8](../src/main/resources/db/migration)と[Java V7](../src/main/java/db/migration/V7__add_auth_identity_and_revocations.java)。
既存migrationは改変しない。B1の[停止移行・旧JWT拒否・復元制約](security/jwt-revocation-migration.md)を確認する。

`GET /actuator/health`は全profileでJWTなし、prodではmain portの`/livez`と`/readyz`も匿名可。
probeとdefault/local総合healthは正常時200 `{"status":"UP"}`のみ。
prod総合healthはBoot標準のstatusとgroups（liveness/readiness）を返し、details/componentsは引き続き非公開。
livenessはlivenessStateのみ、readinessはreadinessStateとdbを含む。
DB利用不能またはREFUSING_TRAFFICはreadiness503となり、DB障害だけではlivenessをDOWNにしない。
DBがDOWNなら配備成功扱いにしない。db/diskSpace/componentsなどのdetailsは認証済みでも非公開。
他のActuatorはaccess/exposureから除外し、管理URLへのアクセスも404とする。JMX exposureも無効。
datasource URL/user/password、root password、JWT secretはhealth/ProblemDetailへ返さない。

## Deploy / secret handling

[配備手順](deployment.md)ではDB_NAME/DB_USERNAME/APP_MEMORY_LIMITをRepository variables、
DB_PASSWORD/DB_ROOT_PASSWORD/JWT_SECRETをActions secretsで参照する。DB URLはComposeで導出する。
LOGIN_TRUSTED_PROXIESは現行workflowのSSH転送変数ではなく、承認済みhostローカル`.env`で準備する。
Actionsへ同名の値を置くだけで注入されるとは扱わず、実peer確認と設定反映は運用前の未実施事項に残す。
runnerとremote BashはSSH/切替前に不足を拒否し、remoteもprodを強制する。
DB_ROOT_PASSWORDは全serviceを含むCompose解決に必要なためrunner/remoteへ渡すが、
app datasource・app container environmentへは渡さない。DBを再作成・password変更する操作は行わない。
resolved Composeとrollback環境にはsecretがあるため、umask 077/.deploy 700と既存redactionを維持する。

本Issueはコード・契約・テストだけ。**本番secret/Repository variables設定、既存DBのuser作成・権限変更、
password rotation、EC2配備、Deploy workflow再有効化は未実施**。運用者がbackup/接続/Flywayを確認する。
HTTPS/TLS、AWS Secrets Manager/SSM、RDS、Terraform全面変更、Actions version更新は対象外。

参照: [probeとDB待機の実装契約](db-lifecycle.md)、
[MariaDB公式imageのenvironment](https://mariadb.com/docs/server/server-management/automated-mariadb-deployment-and-administration/docker-and-mariadb/mariadb-server-docker-official-image-environment-variables)。

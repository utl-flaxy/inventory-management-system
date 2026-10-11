# Auth APIの入力・エラー契約（Issue #31 / #74）

`POST /auth/register` / `POST /auth/login` はapplication/jsonのbodyを受け取り、成功200を維持する。
両RequestへBean Validation、Controllerへ `@Valid` を追加する。入力不正はService・Repository・BCrypt・JWT生成より前に400となる。
Issue #33でエラーbodyをSpring ProblemDetail、application/problem+jsonへ変更する。
status/code・入力・JWT仕様は維持する。[APIエラー契約](api-errors.md)を参照。

## Usernameとpassword

両APIでusernameは `[A-Za-z0-9._-]{3,50}`。null、空、空白、2文字以下、51文字以上、
space含有、前後space、日本語、control文字、`/`、`@`を400で拒否する。trim・case変換・正規化は行わない。
現行DBのusers.usernameはVARCHAR(255)、`utf8mb4_unicode_ci`のcase-insensitive UNIQUE。
`Alice`を登録後の`alice`登録は409、`ALICE`でのloginは同じuserに対応し、JWT subjectはDBの保存済み`Alice`となる。
API上限50とDB上限255の違いは意図的で、列縮小やcollation変更・migrationは追加しない。

passwordはNotBlank、8〜72文字（Bean Validation SizeのUTF-16長）、さらにUTF-8で72 bytes以下。
ASCII限定や大文字/数字/記号必須ルールは追加しない。passwordもtrimせず元の文字列をBCryptへ渡す。
Spring Security 6.5.9のBCrypt実装はencodeで72 bytes超を拒否するため、文字数だけではなく
小さいUtf8Size Constraintでbyte境界を入力時に検証する。日本語24文字（72 bytes）は許可し、
日本語24文字+ASCII1文字（73 bytes）は400となる。password hash用のDB VARCHAR(255)は維持する。
既存行のusername/password hashを補完・変更しない。既存アカウントにも新しいlogin入力契約を適用する。

## Register

```http
POST /auth/register
Content-Type: application/json

{"username":"alice","password":"example-password"}
```

```json
{"id":1,"username":"alice","role":"ROLE_USER"}
```

RegisterResponseにはid/username/roleだけを定義し、User.passwordのJsonIgnoreも維持する。
未知propertyは従来どおり無視し、`role: ROLE_ADMIN`を送ってもROLE_USERを強制する。
DB保存はBCrypt hashで、plaintext・hashをレスポンスへ返さない。

登録済みusernameは409。事前lookupは利便性のためで、競合の最終防御は既存の`uk_users_username` UNIQUE。
同時登録で両lookupが未登録でも、MariaDB1062かつHibernateが識別したこの制約名だけを専用Exceptionへ変換する。
他のDB制約（注文Idempotency UNIQUE等）をusername duplicateとして扱わない。
書き込みは単一saveAndFlushのRepository Transactionでcommit/rollbackするため、AuthServiceには外側のTransactionを追加しない。
flush/commitの失敗をrollback完了後に捕捉でき、rollback-only Transaction内の再照会を行わない。

```json
{"type":"https://inventory-management-system.example/problems/username-already-exists","title":"Username Already Exists","status":409,"detail":"ユーザー名は既に使用されています","instance":"/auth/register","code":"USERNAME_ALREADY_EXISTS"}
```

## Login

入力形式が有効で試行枠があればfindByUsernameを1回、PasswordEncoder.matchesを1回実行する。
ユーザー不在時は起動時に一度だけ同じPasswordEncoderで生成したランダムなdummy BCrypt hashへ照合する。
現在のBCrypt costは10。dummyの固定hash・別cost・リクエストごとのencodeは使わない。
ユーザーが存在し、照合に成功した場合だけJWTを生成する。dummy照合が成功しても認証はしない。
成功レスポンスはtokenだけ。署名、issuer、subject、iat、expは従来どおり。
JWT role claimを追加せず、protected endpointの認可は毎request取得するDB roleをsource of truthとする。

user不存在・password不一致はともに401、status/code/type/title/detail/instanceを同じにする。
レスポンス差とBCrypt照合を省略する分岐差を減らす。DB lookup、キャッシュ、過去hashのcost、
CPU負荷等による実時間差の完全な解消やconstant-time認証は保証しない。実時間の等値assertionも行わない。

```json
{"type":"https://inventory-management-system.example/problems/invalid-credentials","title":"Invalid Credentials","status":401,"detail":"ユーザー名またはパスワードが正しくありません","instance":"/auth/login","code":"INVALID_CREDENTIALS"}
```

## エラー・非露出・OpenAPI

| 状況 | HTTP / body |
|---|---|
| username/passwordのBean Validation違反 | 400 VALIDATION_ERROR、errorsにfield名→固定日本語message |
| malformed JSON、bodyなし、JSON null | 400 INVALID_REQUEST |
| 未対応Content-Type | 415 UNSUPPORTED_MEDIA_TYPE |
| duplicate username | 409 USERNAME_ALREADY_EXISTS |
| 形式は有効だが認証情報が一致しない | 401 INVALID_CREDENTIALS |
| 有効形式のloginが試行枠・状態保持容量を超過 | 429 LOGIN_RATE_LIMITED + Retry-After（秒） |
| 予期しない内部エラー | 500 SYSTEM_ERROR、全API共通の「予期しないエラーが発生しました」 |

Validation responseの`password`はfield名であり、実password値を返さない。
SQL/constraint名、rawPassword cannot be null等の内部例外、hash/token/署名鍵をAuthエラーへ露出しない。
Authの汎用RuntimeExceptionとSystem.out.printlnを削除し、同内容のloggingへの置換も行わない。
他の既存例外handlerとOrderのIDEMPOTENCY_KEY_CONFLICTを維持する。
OpenAPIのRegister/Login requestへminLength/maxLength/pattern、password writeOnlyとUTF-8 byte制限を表示し、
register200/400/409、login200/400/401/429とRetry-Afterを明示する。
4xx/5xxはapplication/problem+jsonと共通ApiProblem schemaを参照する。

## 検証

Java 17の `./mvnw test` はDocker不要。AuthControllerTestは両APIのusername/password境界とService未呼出、
DTO・専用error・内部例外非露出を検証する。AuthServiceTestはlookup/encode/matches/JWTの呼出、
ROLE_USER強制、対象UNIQUEだけの識別、失敗後の再照会なしを検証する。

`./mvnw verify` はMariaDB12.2.2のAuthApiITを必須実行する（Docker必須、未起動時skipなし）。
旧635テストを削除せず、旧Authの500/blank受理の期待値を新契約へ更新する。
DB BCrypt、401本文完全一致、case-insensitive照合と列長保持、UTF-8境界、実UNIQUE競合、OpenAPI、JWT・DB roleを確認する。
barrierで10件同時POSTを開始し、同username1成功/9競合、異なるpasswordの勝者hash、別username全件成功を確認する。
全Future回収・Executor終了・timeoutを明示し、勝者をschedulerに依存して固定しない。

OAuth2/OIDC、refresh/reset/verification、MFA、永続的account lockout、CAPTCHA、breach DB、
メール登録は対象外。ProblemDetail移行はIssue #33で実装済み。

## ログイン試行制限（Issue #74）

有効形式の `POST /auth/login` だけを対象に、DB lookupより前で次の3枠を同時に予約する。
成功も失敗も1回消費し、成功による枠リセットはしない。拒否された要求は枠を消費せず、
新規key・DB照会・BCrypt照合・JWT生成・DB書き込みも発生させない。
入力不正は既存の400が優先し、試行枠を消費しない。登録・業務API・JWT検証は対象外。

| Spring property (`app.login.rate-limit.` に続ける) | 既定値 | 用途 |
|---|---|---|
| `pair-limit` | 5 | 同一接続元＋username（case insensitive） |
| `source-limit` | 100 | 同一接続元から全usernameへの試行 |
| `global-limit` | 1000 | プロセス全体、接続元とusername両方の変更にも対応 |
| `window` | `PT1M` | 各枠の最初の受付からの固定時間窓 |
| `max-entries` | 4096 | 接続元counterと接続元＋username counterの合計上限 |
| `trusted-proxies` | 空 | 転送元IPを採用してよい実際の接続元の完全一致IP一覧 |

各上限は正の整数、容量は2〜100000、窓は1秒〜24時間で起動時検証する。
Spring外部設定（例 `APP_LOGIN_RATE_LIMIT_PAIR_LIMIT=5`、`APP_LOGIN_RATE_LIMIT_WINDOW=PT1M`）で変更可能。
Composeのthreshold調整は運用者管理のoverrideの `services.app.environment` に設定する。
最初の受付を基準とする固定窓であり、rolling windowではない。窓の前後では最大2窓分のburstが可能。
Clockを使用し、時計の後退は最後の受付時刻でclampしてquotaを再開しない。

1つの同期区間内で3枠・必要なkey容量を確認してから加算するので、並列要求で閾値を超えて受付しない。
mapは常に最大4096エントリ（既定）＋global counter1個。keyはcanonical IP（最大45文字）と
validation済みusername（最大50文字）だけで、password/body/HTTP requestは保存しない。
生IP/usernameをプロセスメモリ内のkeyとして使うが、ログ・レスポンス・DBへ出力しない。
新しい有効形式login時に期限切れを除去し、idle中も保持量は上限以内。
生きたcounterはLRU evictionで捨てず、容量不足時は429でfail closedするため、キー大量生成で枠をリセットできない。

同じusernameでも別の接続元は独立したpair枠を持つ。username単独のロックは行わず、
外部攻撃者が1つの接続元から他者のアカウントを全面ロックすることを避ける。
NAT配下のユーザーはsource枠を共有するため、正当な高負荷にも429が起こり得る。
global枠・容量上限も全利用者へ影響する。負荷実績に応じて閾値を運用調整する。

受付できて資格情報不一致なら401。資格情報を検証する前に枠/容量不足なら、正しい資格情報でも429。
429は共通ProblemDetailの6フィールド、固定code `LOGIN_RATE_LIMITED`、固定detail
「ログイン試行回数が上限に達しました。時間をおいて再試行してください」。
`Retry-After` は拒否に関係する窓の残り時間を切り上げた正の整数秒。
容量不足時は最初のcounter期限も考慮する。再試行時の空きは他の利用者の試行次第であり、成功予約ではない。

失敗は既存 `auth.login.failed`、制限は `auth.login.throttled`（WARN、安全な固定message/eventだけ）、
成功は既存userId/role、内部エラーは既存 `system.unhandled_error` のsafe metadataで診断する。
raw username/password/IP、任意proxy header、JWT/hashをログへ記録しない。

## 接続元とプロキシの信頼境界

`LoginClientAddressFilter` はlogin要求のsocket peerをSpringのForwardedHeaderFilterより前に捕捉する。
Boot 3.5.12標準も最優先順で登録するため、本構成では転送filterを明示的に1段後へ登録する。
転送によるHTTPS/HSTS等の処理自体とprodの `server.forward-headers-strategy=framework` は維持する。
捕捉が欠けた場合は書き換え済みremoteAddrを信用せず、単一unknown枠へfail closedする。

`trusted-proxies` の完全一致IPから来た要求だけ、単一の数値IPのX-Forwarded-Forを採用する。
未知peer、複数header/chain、hostname、port、zone、過長/不正IP、予期しないForwardedがあれば
実際のpeerの共有枠へ戻す。IPv6表記をcanonical化し、IPv4-mapped IPv6はIPv4と同じkeyにする。
全private CIDRやすべてのloopbackを暗黙に信頼しない。DNS lookupでheaderを解決しない。

既存Caddyは公開clientのX-Forwarded-For/Proto/Hostを上書きし、Forwarded/Port/Prefix/Sslを除去する。
`trusted_proxies` の追加やCDN/ALBの追加はこの前提を変えるため、別途設計・検証が必要。
appは既存 `127.0.0.1:8080:8080`、DBはhost公開なし。外部からappへの直結を許可しない。
host CaddyからDocker公開portへ入ると、appのsocket peerがloopbackではなくDocker bridge gatewayに
見えることがある。127.0.0.1と決めつけず、対象hostのnetwork/gatewayを確認する。

```bash
# 実行するのは配備環境の運用者。Config.Envや秘密値を出力しない。
docker inspect inventory-app --format '{{range .NetworkSettings.Networks}}{{println .Gateway}}{{end}}'
```

運用者がCaddy接続経路の実際のpeerであることを確認したIPだけを、ローカル `.env` の
`LOGIN_TRUSTED_PROXIES`（comma separated）に設定する。Composeはappの
`APP_LOGIN_RATE_LIMIT_TRUSTED_PROXIES` へ渡す。hostで直接Java起動する場合は検証済みloopbackを設定する。
空のままでも保護は働くがproxy背後の全ユーザーがsource枠を共有するので、公開前に確認する。
このPRではAWS/本番への確認・配備は行っていない。Caddy同一hostのプロセスはこの境界内で信頼される。

根拠: [Spring Boot 3.5.12の転送filter登録](https://github.com/spring-projects/spring-boot/blob/v3.5.12/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/web/servlet/ServletWebServerFactoryAutoConfiguration.java)、
[Springの転送ヘッダーの信頼境界](https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html#filters-forwarded-headers)、
[Caddy reverse_proxy headerの既定処理](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#defaults)。

## 制限と回帰検証

stateはsingle EC2の単一JVM内だけ。再起動・deployで枠はresetされ、複数JVMは枠を共有しない。
Redisは追加しない。複数インスタンス時はshared admission store等が必要で、cluster-wide制限は保証しない。
分散攻撃や大規模DoSを完全に防ぐ仕組みではなく、ネットワーク側の防御・監視も引き続き運用課題。
登録APIの既存username重複409契約は維持する。登録経由の存在確認を含めた全APIでの
user enumeration解消を保証する変更ではなく、loginの失敗本文・BCrypt分岐・試行制限を改善する。

LoginRateLimiterTestはClock境界、後退、3枠、並列受付、10000個の新規identityによる容量検証、
期限切れ削除を行う。LoginClientAddressFilterTestは偽装とIP正規化、LoginDummyPasswordTestは
encoder cost一致・1回だけencode、AuthServiceTestは失敗時1回matchとJWT未生成を検証する。
LoginSecurityITは実MariaDB、prod、実embedded HTTPとMockMvcで401/429/Retry-After、
並列制限、実際のfilter順、proxy利用者の分離、実console全loggerのsecret非露出を確認する。
AuthApiITの各独立テストは別client peerを使い、同じテスト内の要求は同一peer/default quotaで実行する。
既存のCaddyProxyIT、Auth/JWT/AuthorizationMatrixIT、注文・売上・CSV・ページング・在庫の全回帰も維持する。

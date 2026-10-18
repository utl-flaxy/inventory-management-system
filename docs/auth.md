# Auth APIの入力・エラー契約（Issue #31 / #74）

`POST /auth/register` / `POST /auth/login` はapplication/jsonのbodyを受け取り、成功200を維持する。
両RequestへBean Validation、Controllerへ `@Valid` を追加する。入力不正はService・Repository・BCrypt・JWT生成より前に400となる。
Issue #33でエラーbodyをSpring ProblemDetail、application/problem+jsonへ変更する。
status/code・入力・JWT仕様は維持する。[APIエラー契約](api-errors.md)を参照。

## JWTフィルタの実行経路と公開auth境界（Issue #81）

Spring Boot 4.1.1 / Java17で、JwtAuthenticationFilterはDI対象のbeanを維持し、
FilterRegistrationBeanのenabled=falseによりServlet自動登録を無効にする。
同じinstanceをSecurityFilterChainへ1か所だけ登録し、SecurityContextHolderFilterの後、
AnonymousAuthenticationFilter / AuthorizationFilterの前で認証する。
RequestCorrelationFilterはServlet側でSecurityより先にrequestIdを設定する。

JWT処理の除外とpermitAllは同一のRequestMatcherを共有する。対象はcanonicalな
POST /auth/login と POST /auth/registerだけ。context pathを含むrequest URIの完全一致で判定し、
query stringは判定に含めない。公開POSTはBearer headerがあってもJWT処理に依存しない。
署名・issuer・期限を検証するJwtUtil、UserDetails照会、role認可は変更しない。

/authority・/authentication・/authzなどのprefix類似パス、/auth配下の別ルート、
別HTTP method、末尾slash・case違い・encoded aliasはこの公開例外に含めない。
未認証の場合はSecurityで401となり得る。従来の/auth/** namespace全体の匿名許可を撤廃する変更であり、
未対応method/未定義pathへの匿名アクセスの互換性は保証しない。実在する公開APIのPOST 2件は維持する。
将来ルートを追加するときは認可とOpenAPIを個別にレビューする。

main 2e10f1698e40c068233413aa950c59a84a11c32fの実Tomcatでは、通常の認証成功要求で
Security内とServlet側のフィルタ入口が計2回、内部処理・JWT解析・user照会は各1回だった。
OncePerRequestFilterが内部処理の重複を防いでおり、二重認証や現在の保護APIの権限迂回を確認した事実とは区別する。
一方、prefix類似のテスト専用ルートは有効JWTでも解析0回/401、/auth配下のテスト専用ルートは匿名200だった。
これらのルートはtest sourcesにだけ存在し、現在の本番APIに同ルートが存在するという報告ではない。

修正後はServlet登録0、Security内1、成功要求の入口/内部処理/解析/照会が各1回。
公開POSTの内部JWT処理は0回。テスト専用の/auth配下ルートも匿名401・有効JWT200となる。
JWT不正401・認可403・login429/Retry-After・既知DB一時障害503・未知障害500、
ProblemDetail・安全なevent/requestId、AUD-11の待機・shutdown契約を維持する。
検証は使い捨てMariaDB/実HTTPとCIで行い、本番ネットワークを検証したものではない。

根拠: [Spring SecurityのFilter登録](https://docs.spring.io/spring-security/reference/servlet/architecture.html)、
[OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/7.0.0/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html)。


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

## 登録の負荷制限（AUD-07 / Issue #110）

公開入口は `POST /auth/register`（`/register`単独ではない）。有効な入力の受付前に、ログインと同じ
transport peer捕捉・信頼proxy検証を使う。信頼設定は既存の `app.login.rate-limit.trusted-proxies` と
`require-trusted-proxy` を共用する。登録専用の別allowlistを作らず、本番は両APIで同じ確認済み経路を要求する。
未信頼XFFを接続元に採用せず、prodで未確認peer・不正/欠落/chain/重複XFF・Forwarded競合なら503。
`unknown` やDocker peerの共通枠には戻さない。Caddy/host/Dockerの信頼境界・運用者のpeer実測手順は下記と同じ。

| Spring property (`app.registration.rate-limit.` に続ける) | 既定値 | 用途 |
|---|---|---|
| `source-limit` | 5 | 確認済み接続元ごとの受付回数。usernameを変えても同じ枠 |
| `window` | `PT1M` | 最初の受付から60秒の固定窓 |
| `max-concurrent` | 2 | DB lookup〜BCrypt encode〜保存〜DTO返却の同時処理上限。待ち行列なし |
| `max-entries` | 4096 | 保存する接続元窓の上限。username/password/body/JWTは保持しない |

source-limitは正整数、同時数1〜64、保存数1〜100000、窓1秒〜24時間。不正設定では起動失敗し、
prodではDB初期化前に拒否する。外部設定例: `APP_REGISTRATION_RATE_LIMIT_MAX_CONCURRENT=2`。
上限の引き上げを推奨する値ではない。CPU・DB・host余力を実測して決定する。

接続元を確認できれば、400 Validation/不正JSON・415は従来どおり受付枠を消費しない。
接続元を確認できない経路は入力不正より503を優先し、DB/BCryptへ進めない。
受付した登録は成功200・username重複409・内部エラー500も1回消費する。失敗/成功で回数をresetせず、
try-with-resourcesでpermitを必ず解放する。登録回数とpermitはログインから独立しており、登録が満杯でも
ログインの枠を消費しない。既定の最大認証処理数はログイン4＋登録2。CPU/DB自体は共用なので遅延の完全隔離は保証しない。

429は6フィールドProblemDetail、code `REGISTRATION_RATE_LIMITED`、title `Registration Rate Limited`、
固定detail「登録試行回数が上限に達しました。時間をおいて再試行してください」。
`Retry-After` は接続元窓の残り時間の切上げ秒（正数）、同時枠満杯なら1秒。待てば必ず成功する保証ではない。
source不明は503 `REGISTRATION_SOURCE_UNAVAILABLE`、title `Registration Source Unavailable`、
固定detail「登録接続元を確認できません。運用者にお問い合わせください」。設定是正が必要なためRetry-Afterは付けない。
200のROLE_USER/DTO、409、JWT/認可は維持する。IP・username・秘密値を新しいログや拒否レスポンスへ追加しない。
429は値なしイベント `auth.register.throttled`、503は既存HTTP完了statusで診断する。

窓・permit・容量の確認と加算は同じ同期区間で行う。時計後退は最後の観測時刻へclampする。
期限切れ窓は受付時に破棄し、容量に達すれば最古の接続元窓を退避して新規clientを受付する。
固定長の数値IPだけをkeyにするため、保存量はO(max-entries)、同時permitはO(max-concurrent)。バックグラウンドthreadや待ち行列は作らない。
拒否された要求は新しいstate/permitを作らない。送信元を変えない攻撃者はusername変更で自分の窓を退避できない。

**運用上の限界:** NAT共有の利用者は5回/分を共有する。固定窓境界で2窓分のburstがあり、IPv6アドレス変更・
多数の実接続元による履歴退避で旧sourceが再受付され得る。stateは単一JVM内だけで再起動時にresetし、複数JVMでは共有しない。
全体の1分枠を失敗履歴で使い切らせる設計は採用しないが、分散攻撃が同時2枠を継続占有すれば正規登録も一時429になり得る。
長期の不要アカウント増加、HTTP parsing/不正入力の大量送信、ネットワークDoSを完全には防がない。
edgeでの送信元別流量/接続/本文サイズ制御、監視・容量計画、必要なら登録verification等は別の運用/設計事項。
CAPTCHAや分散storeをこの変更で追加しない。公開前に実peerと複数clientの疎通を確認する。本番検証は未実施。
rollbackは旧app imageへ戻す既存手順で、DB/migrationの変更は不要。ただし旧imageでは登録の保護も失われる。

RegistrationRateLimiterTestは時間/容量/同時境界と解放、RegistrationClientAddressFilterTestは偽装/不正sourceを検証する。
RegistrationSecurityITは実MariaDB・prod・実HTTPで正常/409/429/503/500、JWT/role、ログ非露出、
20並列中2受付/18拒否とlogin独立性を検証する。既存の10顧客DB一意制約テストは実際の既定同時上限2を
テスト送信側で守り、別々の接続元で実施する（本体の設定・pool・timeoutを緩和しない）。

## Login（認証処理）

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
| 登録の接続元枠または同時処理数を超過 | 429 REGISTRATION_RATE_LIMITED + Retry-After（秒） |
| 登録の接続元を安全に確認できない | 503 REGISTRATION_SOURCE_UNAVAILABLE、Retry-Afterなし |
| 予期しない内部エラー | 500 SYSTEM_ERROR、全API共通の「予期しないエラーが発生しました」 |

Validation responseの`password`はfield名であり、実password値を返さない。
SQL/constraint名、rawPassword cannot be null等の内部例外、hash/token/署名鍵をAuthエラーへ露出しない。
Authの汎用RuntimeExceptionとSystem.out.printlnを削除し、同内容のloggingへの置換も行わない。
他の既存例外handlerとOrderのIDEMPOTENCY_KEY_CONFLICTを維持する。
OpenAPIのRegister/Login requestへminLength/maxLength/pattern、password writeOnlyとUTF-8 byte制限を表示し、
register200/400/409/429/503、login200/400/401/429/503とRetry-Afterを明示する。
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

## ログイン試行制限（Issue #74 / A-01・#97）

有効形式の `POST /auth/login` だけを対象に、DB照会・BCryptより前で接続元と接続元＋usernameの
枠、および認証処理中の同時実行permitを一括予約する。成功・失敗とも試行を1回消費し、成功で
枠をリセットしない。拒否時はDB・BCrypt・JWTへ進まず、新しいカウンターも作らない。
入力不正は、接続元を確認できた通常経路では既存400が優先する。登録・業務API・JWT検証は対象外。

| Spring property (`app.login.rate-limit.` に続ける) | 既定値 | 用途 |
|---|---|---|
| `pair-limit` | 5 | 同一の確認済み接続元＋username（case insensitive） |
| `source-limit` | 100 | 同一接続元から全usernameへの試行 |
| `max-concurrent` | 4 | DB lookup〜BCrypt〜JWT返却までのプロセス内同時処理数 |
| `window` | `PT1M` | 接続元の最初の受付からの固定窓。配下のusername枠も同時に期限切れ |
| `max-entries` | 4096 | 接続元と配下usernameの保存エントリ合計上限 |
| `trusted-proxies` | 空 | 実際のtransport peerの完全一致IP一覧。本番は設定必須 |
| `require-trusted-proxy` | false / prodはtrue必須 | 本番は信頼済みpeer＋正しい転送情報だけを受付 |

上限は正整数、同時処理数は1〜64、保存容量は2〜100000、窓は1秒〜24時間。
Spring外部設定で調整する（例 `APP_LOGIN_RATE_LIMIT_MAX_CONCURRENT=4`）。
旧 `global-limit` / `APP_LOGIN_RATE_LIMIT_GLOBAL_LIMIT` は廃止し、残っていれば起動時エラーにする。
失敗履歴でプロセス全体の1分間の枠を使い切る方式は採用しない。

同時処理枠は待ち行列なしで予約し、全体が満杯なら429・Retry-After: 1。
Controllerのtry-with-resourcesで成功・401・例外の全経路から解放し、過去の失敗が別接続元の
1分間の拒否として残らない。BCrypt処理自体の上限であり、CPU使用率や成功率のSLAではない。
本番のCPU・DB pool・メモリと実測に合わせて同時数を選ぶ。大きくすればよい設定ではない。

接続元・pair枠は固定窓。残り時間を切り上げた正の秒数を429のRetry-Afterに返す。
窓の前後には2窓分のburstがあり得る。Clock後退では最後の受付時刻にclampする。
全カウンター確認・容量調整・加算・permit取得は同じ同期区間で行う。
同一接続元はusernameの変更で自分の枠を捨てられない。

保存容量が足りなければ、現在受付中の接続元以外の最古の接続元窓を、配下usernameと一緒に退避する。
満杯の履歴だけを理由に新しい接続元をすべて429にしない。自分の窓だけで容量を使い切った場合は、
その接続元だけを期限まで429にする。保存量は常に上限以内。password・HTTP body・JWTは保存しない。
IPとusernameはカウンターのメモリ内だけで利用し、ログ・レスポンス・DBに記録しない。

**退避には限界がある。** 多数の実接続元を持つ攻撃者は他の接続元の履歴を押し出せるため、
退避された接続元の試行枠も再開し得る。厳密な分散ブルートフォース制御を保証しない。
同時認証数の上限は退避とは独立して維持する。容量調整、エッジでの送信元別制限・接続制御、
外部DoS対策は運用側の責務であり、この変更では新サービスやCaddy pluginを導入しない。

同一usernameでも異なる確認済み接続元の枠は独立する。username単独のaccount lockoutは作らない。
ただし実際に同じpublic IPを使うNAT・企業proxy・IPv6の別アドレス・複数JVM等は別の制約である。
特に同じNATの攻撃者と正規利用者をIPだけで識別することはできず、pair/source枠は共有される。
分散攻撃が同時枠を継続占有すれば正規利用者も一時429になり得る。完全な可用性保証ではない。

401・403・通常の400、JWT署名/issuer/期限、USER/ADMIN認可は維持する。
429は既存の6フィールドProblemDetail、code `LOGIN_RATE_LIMITED`、固定detailを維持。
接続元を検証できないloginには新たに503の6フィールドProblemDetail、
code `LOGIN_SOURCE_UNAVAILABLE`、title `Login Source Unavailable`、固定detail
「ログイン接続元を確認できません。運用者にお問い合わせください」を返す。
この場合は資格情報と回数の検証を行わず、Retry-Afterを付けない（運用設定の是正が必要）。
接続元不明の経路では入力不正400より503が先になる。一般的なログイン失敗の401とは区別する。

失敗は `auth.login.failed`、429は `auth.login.throttled`、成功は既存userId/role、
内部エラーは安全な `system.unhandled_error` で診断する。503はhttp.request.completedのstatusで確認する。
raw IP/username/password/hash/JWT、転送ヘッダーの値をログに追加しない。

## 接続元とプロキシの信頼境界

現構成は client → host Caddy（TLS終端）→ `127.0.0.1:8080` → Docker port forwarding → app。
DBのhost portは公開しない。appへの外部直結を許可しない。
同じhostやDocker networkへ到達できるプロセスは、信頼済みpeerと同じ経路を利用できる場合がある。
hostとnetworkへの実行権限も信頼境界であり、IP allowlistだけでCaddyプロセスを暗号学的に証明しない。

`LoginClientAddressFilter` はRequestCorrelationFilterの後、Spring ForwardedHeaderFilterの前で
socket peerを捕捉する。prodは `server.forward-headers-strategy=framework` に固定し、
Tomcat nativeによって捕捉前にpeerが書き換わる設定は起動時に拒否する。

本番では空・不正のtrusted-proxies、require-trusted-proxy=falseをDB初期化前に拒否する。
runtimeでは信頼済みpeerからの、単一header・単一の数値IPのX-Forwarded-Forだけを採用する。
未知peer、欠落・不正・chain・重複XFF、予期しないForwarded、無効socket peer、
捕捉属性の欠落は503にする。peerや `unknown` の共通枠へ戻さない。
IPv6を正規化し、IPv4-mapped IPv6はIPv4と同じkeyにする。hostname・port・zone・CIDRは受け付けず、
DNS lookupは行わない。private network全体やloopbackを暗黙には信頼しない。

prod以外の直接接続では、空のtrusted-proxiesで実socket peerを使い、一般クライアントの
X-Forwarded-For/Forwardedを接続元識別には採用しない。明示的に信頼したpeerの情報が不正なら、
prod以外でも503にし、誤った共有枠を作らない。

Caddyの公式既定動作は、未信頼clientのX-Forwarded-For/Proto/Hostを無視して再設定する。
リポジトリのCaddyはForwarded/Port/Prefix/Sslも除去する。
実Caddy 2.11.7のテストで、異なるsocket clientを分離し、重複した偽装XFFも上書きされることを確認する。
Caddyにtrusted_proxiesを加える場合やCDN/ALBを前段へ加える場合は、単一XFFの前提が変わるため別設計が必要。

### 運用者の設定・移行・復旧

1. 配備hostのCaddy設定、appのloopback限定publish、Docker network、他の接続可能プロセスを確認する。
2. gatewayのinspectは候補の確認にすぎない。承認された使い捨てprobe等で、Caddyからと同じ経路を
   通る接続のapp側socket peerを実測する。Docker Desktop・NAT・network再作成で値が変わり得る。
   appのログへIPを追加したり、Config.Envやsecretをdumpしたりしない。
3. 検証した完全一致IPだけをhostローカル `.env` の `LOGIN_TRUSTED_PROXIES` に設定する。
   comma separated。コードの例示IPをコピーしない。Composeは未設定・空をconfig解決時に拒否する。
4. 古いglobal-limitのoverrideを削除し、max-concurrentをhost容量と実測から選ぶ。
   Deployは有効化せず、承認された公開前検証で、異なる実clientの正常login・偽装header拒否を確認する。
5. 空設定では起動しない。誤った非空peerではreadinessがUPでもloginは503となり得るため、
   readinessだけで信頼設定の正しさを判定しない。503時は値を公開せず経路と設定を見直す。
6. 既存Deployの旧image・解決済みCompose・旧environmentによるapp-only rollbackを維持する。
   DB/volumeの再作成やmigration変更は不要。旧imageへ戻すと旧共有枠のリスクも戻るため暫定措置とする。

この変更でAWS・実本番peer・実利用者・本番設定は検証していない。
Docker検証では新規networkの使い捨てJDK probeでhost-published経路のpeerを取得し、
同じ経路のappへ設定する。IP値は成果物に記録しない。この測定は本番の正しい値の根拠にはならない。

根拠: [Caddy reverse_proxyのheader既定処理](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#defaults)、
[Springの転送ヘッダーの信頼境界](https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html#filters-forwarded-headers)。

## 制限と回帰検証

### 認証拒否とDB障害（AUD-10 / Issue #114）

ログインのパスワード不一致は401 INVALID_CREDENTIALS、有効JWTのユーザーが存在しない場合や
JWTの署名・issuer・期限が不正な場合は401 UNAUTHENTICATEDを維持する。
ユーザー確認時のDB接続不能/切断・取得/クエリtimeoutは認証拒否ではなく503 SERVICE_UNAVAILABLE。
同じ分類をMVCにも適用し、登録や認証後の業務処理で検出した該当障害も固定の安全なProblemDetailにする。
503でJWTを破棄したり、ログインを無条件に繰り返したりしない。復旧後は未失効の同じJWTが使える。
DB認証設定・SQL/schema不備、原因不明のDB障害、プログラム例外は500 SYSTEM_ERRORで、すべてを503へは変換しない。
分類条件・書込み再送の注意は[エラー仕様](api-errors.md#db障害の分類aud-10--issue-114)を参照する。

DB障害の503にRetry-Afterは付けない。接続元を確認できない専用503、受付制限429/Retry-After、
BCrypt、role判定、JWT発行/検証、login/registerの独立カウンター/permitは変更しない。
故障注入用ITの接続取得/validation/socket timeoutは試験を有界にする設定であり、
本番のpool・timeoutや#81のフィルタ登録経路・除外パスはこの変更の対象外。

stateは単一JVM内だけ。再起動/deployで枠はresetされ、複数JVMは枠を共有しない。
恒久account lockout・MFA・CAPTCHA・ネットワークDoS対策や全APIのuser enumeration解消は今回の範囲外。

LoginRateLimiterTestは境界・時計後退・case・source/pair・同時数・permitの重複解放・例外時解放、
10000の異なる接続元による容量と新規client可用性を検証する。
LoginClientAddressFilterTestは偽装・欠落・不正・信頼peerとIP正規化・unknown拒否を検証する。
ProductionConfigurationTestは本番の不足設定と不正設定をDB初期化前に拒否する。
LoginSecurityITは実MariaDB、prod、実embedded HTTP、MockMvcで攻撃者と正常client、
401/429/503、Retry-After、BCrypt/DB同時数、JWT、実consoleの非露出を確認する。
既存のdummy BCryptの1回照合、CaddyProxyIT、JWT/Authorization、ADMIN provisioning、
注文・在庫・Transaction、migration V1〜V6の回帰を維持する。

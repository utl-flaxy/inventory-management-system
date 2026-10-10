# Auth APIの入力・エラー契約（Issue #31）

`POST /auth/register` / `POST /auth/login` はapplication/jsonのbodyを受け取り、成功200を維持する。
両RequestへBean Validation、Controllerへ `@Valid` を追加する。入力不正はService・Repository・BCrypt・JWT生成より前に400となる。
エラーは既存Map形式を維持し、ProblemDetailへの移行やJWT仕様変更は行わない。

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
{"code":"USERNAME_ALREADY_EXISTS","message":"ユーザー名は既に使用されています"}
```

## Login

入力形式が有効ならfindByUsernameを1回実行し、PasswordEncoder.matches成功時だけJWTを生成する。
成功レスポンスはtokenだけ。署名、issuer、subject、iat、expは従来どおり。
JWT role claimを追加せず、protected endpointの認可は毎request取得するDB roleをsource of truthとする。

user不存在・password不一致はともに401、status/code/messageを同じにする。
レスポンス差によるuser enumerationを抑える。dummy BCryptや高度なconstant-time認証は今回導入しない。

```json
{"code":"INVALID_CREDENTIALS","message":"ユーザー名またはパスワードが正しくありません"}
```

## エラー・非露出・OpenAPI

| 状況 | HTTP / body |
|---|---|
| username/passwordのBean Validation違反 | 400、field名→固定日本語messageのMap |
| malformed JSON、bodyなし、JSON null | 400 INVALID_REQUEST |
| 未対応Content-Type | 415 UNSUPPORTED_MEDIA_TYPE |
| duplicate username | 409 USERNAME_ALREADY_EXISTS |
| 形式は有効だが認証情報が一致しない | 401 INVALID_CREDENTIALS |
| 予期しないAuth内部エラー | 500 SYSTEM_ERROR、固定の「認証処理中にエラーが発生しました」 |

Validation responseの`password`はfield名であり、実password値を返さない。
SQL/constraint名、rawPassword cannot be null等の内部例外、hash/token/署名鍵をAuthエラーへ露出しない。
Authの汎用RuntimeExceptionとSystem.out.printlnを削除し、同内容のloggingへの置換も行わない。
他の既存例外handlerとOrderのIDEMPOTENCY_KEY_CONFLICTを維持する。
OpenAPIのRegister/Login requestへminLength/maxLength/pattern、password writeOnlyとUTF-8 byte制限を表示し、
register200/400/409、login200/400/401を明示する。

## 検証

Java 17の `./mvnw test` はDocker不要。AuthControllerTestは両APIのusername/password境界とService未呼出、
DTO・専用error・内部例外非露出を検証する。AuthServiceTestはlookup/encode/matches/JWTの呼出、
ROLE_USER強制、対象UNIQUEだけの識別、失敗後の再照会なしを検証する。

`./mvnw verify` はMariaDB12.2.2のAuthApiITを必須実行する（Docker必須、未起動時skipなし）。
旧635テストを削除せず、旧Authの500/blank受理の期待値を新契約へ更新する。
DB BCrypt、401本文完全一致、case-insensitive照合と列長保持、UTF-8境界、実UNIQUE競合、OpenAPI、JWT・DB roleを確認する。
barrierで10件同時POSTを開始し、同username1成功/9競合、異なるpasswordの勝者hash、別username全件成功を確認する。
全Future回収・Executor終了・timeoutを明示し、勝者をschedulerに依存して固定しない。

OAuth2/OIDC、refresh/reset/verification、MFA、rate limit/account lockout、CAPTCHA、breach DB、
メール登録、ProblemDetail全面移行は対象外。

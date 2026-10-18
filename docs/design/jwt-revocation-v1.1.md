# ADR: v1.1 JWTのユーザー単位失効（Issue #79 / Phase A）

状態: **独立レビュー待ちの設計決定。以下のv1.1仕様は未実装**。2026-10-19 JST。
調査基準はmain [`82d0eaee27215ea5d3e68f6c2e00549a9ba1eb1b`](https://github.com/utl-flaxy/inventory-management-system/tree/82d0eaee27215ea5d3e68f6c2e00549a9ba1eb1b)。
[着手指示](https://github.com/utl-flaxy/inventory-management-system/issues/79#issuecomment-6256326834)に対応する。
このPRは文書のみ。現行契約は[認証仕様](../auth.md#jwtライフサイクルと失効制約)が正本であり、
Issue #79はPhase B完了までOPENを維持する。ポートフォリオv1.0完成と、実利用者向け **PRODUCTION NO GO** を区別する。

## 1. 実コード・テストと脅威モデル

| 調査対象・現在の証拠 | 現mainで成立すること | v1.1で埋める不足 |
|---|---|---|
| [JwtUtil](../../src/main/java/com/example/demo/security/JwtUtil.java)、[単体テスト](../../src/test/java/com/example/demo/security/JwtUtilTest.java) | HMAC署名、iss、iat、exp、sub=username。外部鍵、既定TTL 3600000ms、外部上書き可。期限欠落も拒否 | immutable uidと失効世代の発行・検証。TTL短縮だけを失効としない |
| [JwtAuthenticationFilter](../../src/main/java/com/example/demo/security/JwtAuthenticationFilter.java)、[CustomUserDetailsService](../../src/main/java/com/example/demo/security/CustomUserDetailsService.java) | 署名等のparse後、usernameでDBの現在roleを取得。role claimは権限に使わない | コピーされた正規JWTの期限前拒否、username再割当てとの切離し |
| [SecurityConfig](../../src/main/java/com/example/demo/security/SecurityConfig.java)、[実HTTP IT](../../src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java) | Servlet登録無効、Security chain内1経路。公開除外は正確なPOST login/registerだけ | 新APIを匿名除外へ加えない。標準LogoutFilterを失効APIと誤認しない |
| [AuthService](../../src/main/java/com/example/demo/service/AuthService.java)、[AuthApiIT](../../src/test/java/com/example/demo/security/AuthApiIT.java) | loginはusername照会1回、実hashまたはdummyへmatches1回、成功時JWT。registerはROLE_USER固定 | 発行とpassword/失効世代変更の競合を閉じる。現在は変更/失効APIなし |
| [User](../../src/main/java/com/example/demo/entity/User.java)、[UserRepository](../../src/main/java/com/example/demo/repository/UserRepository.java)、[V1](../../src/main/resources/db/migration/V1__baseline_schema.sql) | BIGINT AUTO_INCREMENT id、username UNIQUE、BCrypt hash、role CHECK。失効列・UserのJPA @Versionなし | 認証用不変IDとtoken_version。既存の業務PK/FKを置換しない |
| [CurrentUser](../../src/main/java/com/example/demo/security/CurrentUser.java)、[OrderService](../../src/main/java/com/example/demo/service/OrderService.java)、[InventoryService](../../src/main/java/com/example/demo/service/InventoryService.java) | Securityのnameをusernameとして再照会し、業務PKで所有者/ロックを扱う | filterだけをuid化しても不十分。principalと業務側の再解決もuidへ統一 |
| [初期ADMIN CLI](../../src/main/java/com/example/demo/provisioning/InitialAdminProvisioner.java)、[再起動IT](../../src/test/java/com/example/demo/provisioning/InitialAdminLifecycleIT.java) | 専用接続のGET_LOCK、READ_COMMITTED、ADMIN不在時INSERTのみ。通常起動では作らない | raw JDBC INSERTとschemaReadyを新列へ対応。既存ADMINを昇格/再設定しない |
| [Flyway V1〜V6](../../src/main/resources/db/migration)、[DB運用](../db/README.md) | V2金額、V3完了時刻、V4注文冪等性、V5履歴、V6調整冪等性。失効schemaなし | 新しい追加migrationのみ。既存migrationの書換え禁止 |
| [JWT否定系IT](../../src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java)、[DB障害テスト](../../src/test/java/com/example/demo/security/JwtDatabaseFailureTest.java) | 未署名/改ざん/不正署名/別鍵/issuer/期限切れの6ベクトルは401、DB/業務変更なし。DB一時障害503、未知障害500 | 正しい署名でも旧世代/削除済みuidを拒否する回帰。これらはまだ既存テストの保証外 |

対象脅威は、JWTコピーの再利用、password変更後の旧JWT利用、usernameの別主体への再割当て、
盗まれた低権限JWTが昇格後に強くなること、発行/失効/変更の同時実行である。
署名鍵・DB・管理者端末自体の侵害、既に実行中の処理の取消、分散DoSの完全防止はこの方式の保証外。
現mainで事故が発生したという主張ではなく、コードから成立する条件を設計対象にする。

## 2. 採用方式と代替案

Phase Bは **immutable uid + tokenVersionによるユーザー単位一括失効** を採用する。
同じユーザーの全端末・全JWTを止める方式であり、単一JWTだけの失効ではない。

| 方式 | 状態・照会・効果 | 判断 |
|---|---|---|
| uid + tokenVersion | usersの固定量の状態を現在のユーザー照会で確認。version増分で全JWTを無効化 | v1.1で優先。DB依存は残り、正常な別端末も再ログイン |
| jti denylist | tokenごとのランダムIDとissuer、失効状態を保存/照会し、expと許容skew経過後に削除 | 個別失効の後続課題。容量上限、cleanup、索引、複数instance整合性、障害時fail-closedが別途必要 |
| TTL短縮・現行文書のみ | 追加状態なし。ただしコピー済みJWTは期限まで有効 | v1.0の制約説明として維持。v1.1の期限前失効の代替にはしない |

jtiを付けるだけでは失効しない。tokenVersionだけでは特定端末のlogoutを実現しない。
個別失効は[後続Issue #134](https://github.com/utl-flaxy/inventory-management-system/issues/134)で別に追跡する。
refresh tokenは導入しない。現時点でrefreshの要件はなく、現行の再ログイン契約を維持し、rotation/reuse検知/保存/失効の新しい攻撃面を今回は増やさない。
必要性が生じたら専用Issue/ADRで再評価する。RedisやOAuth2/OIDCへの全面移行も前提にしない。

## 3. ID・永続化・claimの確定仕様

既存users.idは注文/FK用に保持する。認証用の **auth_uid（UUID v4）** を別に追加する。
AUTO_INCREMENT idの再投入・再利用に認証の同一性を依存させないためである。
既存PKをuidとして非再利用運用だけで保護する案は列追加が少ないが、認証IDの再利用防止を運用に依存する。
UUID案は列/UNIQUE索引/backfillのコストを受け入れ、業務PKと認証主体を分離する判断である。
Java側で生成するcanonical lowercase UUIDをCHAR(36) CHARACTER SET ascii COLLATE ascii_binで保存し、
UNIQUE・NOT NULL・形式CHECK（UUID v4、IETF variant、lowercase）を要求する。既存行にも各々一度だけ割り当て、改名/role変更/再起動で変えない。
同usernameで別主体を作る場合は必ず新uid。uidの指定を公開registerの入力として受け付けない。
Entityの通常setter/更新queryへuidの書換え経路を作らず、import/復元時の再利用も許可しない。
DB管理権限による上書きをこの制約だけで防げるとは主張しない。

token_versionはSIGNED BIGINT NOT NULL DEFAULT 0、CHECK >= 0。認証世代であり、JPA @Versionではない。
失効時はDB内の `token_version = token_version + 1` を有界なTxで行い、read→無条件saveによる更新消失を禁止する。
最大値9223372036854775807では増分/発行を500でfail-closedとし、0へのreset・wraparoundを禁止する。
単調性は稼働DB上の規則であり、古いbackupからの復元では保証されない。

| v1.1 JWT | 確定する表現・検証 |
|---|---|
| sub / uid | 両方同じcanonical UUID文字列。subもusernameからuidへ変更し、username claimは追加しない |
| tokenVersion | 非負10進整数のcanonical **文字列**（0以外の先頭0なし、最大SIGNED BIGINT）。JSONの数値丸め/coercionを避ける。DB値と完全一致必須 |
| iss / iat / exp / 署名 | 現行issuer・必須exp・HMAC検証を維持。iatも発行時刻として必須。署名検証後に追加claimを型/範囲検証 |
| role / jti | Phase Bでは発行しない。roleはDBが正本。個別token識別の保証なし |

TTLの既定3600000ms、外部設定名、鍵選択の現行契約を変更しない。実稼働の設定値は未確認。
署名・issuer・expと追加claimを1回のparseで検証し、欠落/不一致/型不正は保護APIで401 UNAUTHENTICATED。
未署名/改ざん等を先に拒否し、その場合DB照会へ進まない。

### 検証・principal・業務の影響

通常の認証はprimary DBへuidで1回の新しいcommitted readを行い、internal id/uid/version/roleを取得する。
読取専用projectionを使い、principalにはpassword/hash/JWTを保持しない。認証結果のcacheやread replicaは採用しない。
filterのDB照会回数を通常1回に保つが、既存業務内のユーザー照会まで含め全HTTPが1 SQLだとは扱わない。
認証readは業務のREPEATABLE_READや過去のPersistence Contextに参加させず、短いREAD_COMMITTEDの専用境界に置く。
uid不在/version不一致は401、現在roleの権限不足は403。DB確認不能を401へ変換せず、AUD-10の503/500を維持する。

認証principalはuid/internal id/検証済みversion/current authoritiesを持ち、nameはuidとする。
CurrentUser.requireUserもuidで再解決し、usernameへfallbackしない。注文のcustomer_idや在庫履歴actor_user_idは従来のPKのまま。
業務のロック順・所有者判定・金額・在庫・migration V1〜V6は変更しない。
認証後に開始した業務のsnapshot内で再照会する場合も、別主体へ切り替えないことをテストする。

## 4. 同時実行と失効の境界

**失効Txのcommitが境界**。その後に認証readのsnapshotを開始した旧世代JWTは401となる。
commitより前に認証を終えた要求は継続し得る。実行中の注文の取消や、進行中応答までの瞬時停止は保証しない。
失効の成功応答はcommit後だけ。rollbackならversionもpasswordも変更しない。

loginでは次の二段階を採る。

1. 現行の接続元制限/同時permitの中でusernameを照会し、保存hashまたはdummyへmatchesを1回行う。
2. 成功時だけ短いREAD_COMMITTED Txで同じユーザー行をFOR UPDATEし、uid/保存username/hash/versionが最初のsnapshotと同じか確認する。
   JPAの古いEntityを再利用せず、ロック下でfresh read/refreshする。変更済みなら401 INVALID_CREDENTIALSで再ログインを求め、
   古いpasswordの照合結果を新versionへ繰り上げない。一致時だけそのversionで署名し、Tx終了後に200を返す。

BCryptを行ロック中に実行せず、追加の照合ループ・自動retryを作らない。現在のlogin lookup1回から成功時はロック確認が1回増える。
失効が発行直後にcommitすると返却JWTが既に無効になり得るが、旧passwordの結果で新世代の有効JWTを発行するより安全な境界とする。
Tx/署名失敗時はtokenを返さない。request内のJWT parseを再実行しない。

失効・password変更は対象Userだけをロックする。管理者操作ではactor/targetのUserを重複排除してinternal id昇順で取得し、
ロック下でactorのuid/version/ROLE_ADMINと対象を再確認する。これらのTxでOrder/Productロックを取得しない。
既存のUser→Product、取消のOrder→User→Product順へ逆順の依存を追加しない。
SQL増分と同一Txの監査記録をcommitし、更新件数とoverflowを検査する。

role変更は現在公開APIがないため新設しない。将来の正規変更経路はroleとversion増分を同一Txで行う契約にする。
降格だけでなく昇格時も旧JWTを失効させ、古いUSER JWTが自動的にADMIN権限を得ないよう再ログインを要求する。
直SQLでroleだけを書き換える運用はこのv1.1保証外。既に認可済みの要求の完遂可能性は残る。

## 5. 後続APIの契約（いずれも未実装）

全APIはBearer headerのみで認証し、body/queryのtoken/username/roleを認証主体として使わない。
公開除外2パスは維持し、新APIは保護する。JWTをCookieへ移す設計変更は含めない。

| API・段階 | 成功・作用 | 認証/失敗/再送 |
|---|---|---|
| POST /auth/logout-all（B1） | 204、bodyなし。principal本人のversionを増分し全端末を失効 | 有効Bearer必須。Userロック下でも受信versionを照合。commit後の同じJWTによる再送は401（204を繰り返す契約ではない） |
| POST /auth/password（B2） | currentPassword/newPasswordを受け、hash更新とversion増分を一括commit、204。新tokenは返さず新passwordで再ログイン | 本人Bearerに加えcurrentPassword照合。入力400、現password不一致401 INVALID_CREDENTIALS。並行変更/失効は401 UNAUTHENTICATED、DB無変更 |
| POST /api/v1/admin/users/{uid}/token-revocations（B3） | 204、対象ユーザーの全JWTを失効。password/roleは変更しない | 現在のADMIN＋bodyのcurrentPasswordによる再認証。未認証401、USER403、現password不一致401 INVALID_CREDENTIALS、ADMINに対する対象不在404。自己失効も許可し次回401。別の有効なADMIN要求の再実行は再増分可 |

currentPasswordは既存LoginRequestと同じ検証とし、既存hashの資格情報を追加の長さ条件で拒否しない。
newPasswordはBCrypt用の入力検証（trimなし、UTF-8最大72bytes）を継承し、一般ユーザー最低8文字、
ADMINはoffline provisioningと同じ最低16文字・制御文字禁止等の検証を適用する。新hash生成/現hash照合を有界なpermit内でTx外に実行し、
短いUserロック下でuid/受信version/事前hashを再確認してから更新する。競合時は新hashを保存せず再認証を求める。
B3もADMINの現hash照合をTx外で1回だけ実施し、actor/targetロック下でactorのhash/version/roleを再確認する。
tokenだけをコピーした攻撃者に管理者強制失効を許さないための追加再認証であり、MFAを実装するものではない。
login/registerの既存枠は共有・緩和しない。B1〜B3は認証管理操作専用の受付枠を共有し、待ち行列なしの同時上限2、
確認済み接続元＋操作種別あたり5回/分、状態上限4096を初期仕様とする。成功/失敗とも消費、超過は429/正の秒のRetry-After。
安全に接続元を確認できなければ503とし、偽装XFFやunknown共通枠へfallbackしない。
B1はBCryptを実行せず、同じversionの同時要求はUserロック下で1回だけcommitする。permitは全例外経路で解放し、消費した回数は戻さない。
受付枠は単一JVMの負荷防御であり、NAT共有・窓境界burst・状態退避の限界は既存limiterと同様に残る。
複数JVMの分散制限やCPU/DBのSLAではない。B1から上記境界を回帰テストし、B2/B3で共有permitの干渉も検証する。

400/401/403/404/429/503/500は既存のapplication/problem+json、固定detail、server生成requestIdを使う。
既知DB一時障害503 SERVICE_UNAVAILABLE、未知/設定障害500 SYSTEM_ERRORを維持し、401に潰さない。
追加する認証管理/発行確認のUserロックでは、確認済みのロック待機timeout/デッドロックを一時不可503へ限定変換する。
業務用の409 CONCURRENT_UPDATE（商品/注文detail）へ混ぜず、不明な例外を一律503にしない。DB由来503にRetry-Afterは付けない。
新しい枠の429はAUTH_MUTATION_RATE_LIMITED、接続元不明503はAUTH_MUTATION_SOURCE_UNAVAILABLEとし、
固定detail/Retry-After有無を既存の制限/接続元不明の形式に揃え、OpenAPI/エラー文書を同じPRで更新する。
標準 /logout の応答は未実測であり、上記logout-allへのaliasとは扱わない。

失効操作はactor/targetの内部ID、操作種別、結果、requestIdのみを安全な監査対象とする。
成功イベントはcommit後だけ発行し、password/hash/JWT/署名鍵/raw IP/usernameはログ・レスポンス・artifactへ出さない。
通常ログは永続監査台帳ではないため、B1で同じDB Txに専用の失効監査行をINSERTし、rollback時は両方残さない。
その追加tableには秘密値やJWTを保存せず、actor/targetの削除で監査が消えない保持方針・閲覧権限を定義する。
管理者強制失効はアカウント停止ではない。passwordを知る主体の再ログインは別途阻止しない。
侵害時の停止/復旧、password reset、MFAは別設計が必要。

## 6. 移行・ADMIN・rollback

追加migration（現mainでは次はV7。実装開始時に番号再確認）で新列と監査tableを用意する。
users/orders/FK/既存hash/roleを保持し、uidのbackfill、NULL/重複/形式/非負versionを検証後に制約を有効化する。
UUID v4はJavaの生成器を使うためJava Flyway migrationを候補とし、有界なbatchでbackfillする。全行同じdefaultやusername由来IDを使わない。
MariaDB DDLが一括rollback可能とは仮定せず、途中失敗のpreflight/再開手順と既存データ保持をdisposable DBで検証する。
必要なDDL権限はmigration専用、通常アプリの権限は最小限。自動repair/cleanや破壊的down migrationを作らない。

registerとoffline ADMINのINSERTは新uid/version=0を明示生成し、新schema確認に対応する。
ADMIN CLIの専用接続GET_LOCK/既存ADMINがあれば無変更/秘密入力破棄を維持し、失効やpassword resetに流用しない。
旧CLIはNOT NULL uid列により書込み失敗させ、新schemaへ旧CLIを継続使用しない。既存ADMINのuidは移行で一度だけ設定する。

**旧JWTは切替時に一括拒否する。** uid/tokenVersionなし、旧sub=username、型不正、新subとの不一致を401にし、
username fallbackや期限内のdual acceptance期間は作らない。既存のAPI body/role/TTLは維持するが、全利用者の再ログインが必要な互換性変更である。
移行前の全検証processを停止・drainし、新schema/新binaryだけで受付再開する手順をB1に含める。
旧/newの混在fleetや旧binaryへの無条件rollbackは許可しない。バックグラウンドjob/CLIも版を照合する。

旧binaryはuid/versionを見ずusername JWTを受け入れるため、imageだけ戻せば失効保証が消える。
新JWTのUUID subをusernameとして誤解する可能性もあり、claimを追加するだけでは旧binaryとの互換性は成立しない。
古いDB backupは失効世代も巻き戻す。異版rollback/復元には受付停止と承認済みの全JWT再無効化計画が必要であり、
このPRで実secretや鍵を操作しない。通常の復旧は失効検証を保つ修正版へのforward fixを優先する。
既存Docker証跡は同一image IDの設定故障復帰であり、v1.1から旧binaryへの安全なrollbackの証明ではない。

## 7. Phase Bの受入条件・PR分割

| 段階 | 同じPRで閉じる実装と試験 | 完了条件 |
|---|---|---|
| B1: identity/世代/全端末logout | User/Repository、追加migrationと監査、register/ADMIN CLI、JwtUtil/Clock、filter/principal/CurrentUser、login競合対策、logout-all、移行runbook | 新claim発行/照合と旧JWT拒否を一体で有効化。schemaだけ/検証だけの不完全な稼働版を出さない |
| B2: password変更 | 専用DTO/Service/API、入力・認証管理用受付枠、hash+version+監査の原子更新、OpenAPI | 正しい現passwordのみ成功、旧JWT全拒否、新passwordの再ログイン、競合/例外時DB不変 |
| B3: ADMIN強制失効 | ADMIN認可/対象解決/認証管理用受付枠/監査、自己失効・権限変更競合、運用手順 | 他人操作はADMINのみ、昇格/降格時の世代更新方針、秘密非露出。role変更APIやreset APIは追加しない |
| C: 個別JWT | Issue #134でjti denylistを設計・実装するか判断 | 期限cleanup/容量/索引/障害時/個別と全端末の区別。B1完了を個別失効完了と呼ばない |

各段階は独立レビュー/CI後に進む。B1ではraw SQL/Entity builder/テスト専用UserDetailsを含む既存fixtureも新schema/principalへ対応し、
既存テストをskipせず継続する。Phase AのCI成功は**現行実装の回帰確認**であり、以下の未実装機能の合格を意味しない。

| テスト群 | 実装フェーズで必要な証拠 |
|---|---|
| 固定Clock + unit | 発行/検証を同じClockで制御、exp境界、署名/issuer必須、6否定系維持。uid/sub不一致・欠落・型・tokenVersion負数/先頭0/範囲外/数値型をDB前に拒否 |
| MariaDB migration / CLI | 空DB V1〜新migration、V6既存user/hash/role/FK保持、uid一意/不変、負version拒否、ADMIN並行/再起動/再実行、旧CLI失敗、DDL中断の復旧 |
| 実HTTP / Security | 新JWT成功、失効後401、別ユーザーJWT維持、USER/ADMIN/所有者境界、parse1回、認証SELECT1回、requestId/秘密canary非露出 |
| commit境界 | 別接続とlatch/barrierで失効Txを未commit→commit→rollbackに制御。開始前の認証継続可/commit後の新認証拒否を区別し、キャッシュ/古いPersistence Contextに隠れた再受理なし |
| 発行・更新競合 | logout-all同versionは正確に1増分、独立した管理者操作は成功数だけ増分、password二重更新は1成功。古いhash照合→変更commit→発行再確認で新世代tokenを発行しない |
| 再割当て・権限 | 旧uid削除/新uid同usernameで旧JWT401（業務FKを壊さない独立fixture）。filter後の再解決も旧主体のまま。role+世代更新後に旧USER/ADMIN JWT拒否、再ログインで新role |
| 故障・予算 | 取得待ち/ロック/接続断/不正schemaを区別し503/500等の既存分類維持。全失敗でhash/version/監査の原子性とpool残留0、業務stock/order不変。上限overflowはfail-closed |
| 全回帰・運用境界 | Java17 full verify既存2176件以上+追加、Python29、Terraform mock27、Secret例外0、SpotBugs、OSV/Trivy、Docker/実CI5ジョブ。新機能の機能試験と件数だけの判定を区別 |

競合テストは異なる実DB connection/Txを観測し、Futureの例外を回収する。長いsleep、skip、pool/timeout緩和で成立させない。
本番pool10、接続取得5秒、ロック5秒、Tx10秒、driver timeoutは維持し、Userロック競合による追加遅延を測る。
失効と既存注文/取消/在庫調整の同時実行も含めるが、全負荷・全スケジュールの無停止を保証しない。

## 8. 残余・レビューの判断点

Phase Bは未実装。新列/監査table/追加SQL/互換性切替/新API/追加負荷制限を含むため、独立レビューでこの契約を承認してから着手する。
未解決のWindows DatabaseTimeoutIT初回DB観測CLI3失敗は根因未確定として保持する。今回/後続の成功で消さない。
Trivy OS49件の過去観測、Flyway/MariaDB互換性WARN、異版rollback未検証、実AWS/TLS/secret/DB/容量/SLA/復旧の未確認は別の残余である。
OSV 0件とOS package 0件を混同せず、各PRで対象SHAとscan時点を記録する。実利用者向け本番運用は引き続きNO GO。

一次資料（設計根拠。これらがアプリの失効を自動実装するわけではない）:

- [RFC 7519 §4.1.2 / §4.1.7](https://datatracker.ietf.org/doc/html/rfc7519#section-4.1.2): subは主体識別、jtiはJWT識別子。両者とユーザー世代を区別する。
- [RFC 8725 §3.8 / §3.10](https://www.rfc-editor.org/rfc/rfc8725): issuer/subjectの検証と、受信claimの無条件信用を避ける根拠。
- [MariaDB SET TRANSACTION](https://mariadb.com/docs/server/reference/sql-statements/transactions/set-transaction): READ_COMMITTEDのstatementごとのsnapshotとロックreadを区別する。
- [JJWTのClock仕様](https://github.com/jwtk/jjwt/tree/0.12.7#custom-clock-support): 決定的な期限試験を設計する根拠。現mainにはClock注入はまだない。

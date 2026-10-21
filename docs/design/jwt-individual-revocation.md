# ADR: jtiによるJWT単体失効の必要性と設計（Issue #134 / C-A）

状態: **設計提案・独立レビュー待ち。実装は保留**。2026-10-21 JST。
[最新の着手コメント](https://github.com/utl-flaxy/inventory-management-system/issues/134#issuecomment-6270310710)に対応する。
基準mainは [`17b540e27b6c9ed62090b214ee5975cf4bd48405`](https://github.com/utl-flaxy/inventory-management-system/tree/17b540e27b6c9ed62090b214ee5975cf4bd48405)。
[main CI](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39273705003)のcompleted/success・必須5ジョブ成功を独立確認した。
B1/B2/B3（PR #137/#139/#141）はmerge済み、Issue #79はCLOSED。**Issue #134は未実装バックログとしてOPENを維持する**。
本PRは文書だけであり、以下の「採用時」は将来の契約案。現行API・JWT・DB・設定・CIを変更しない。

## 1. 判断と必要性

**今すぐの実装は推奨しない。選択的logoutが必要と承認された場合の方式として、primary MariaDBのjti denylistを条件付きで推奨する。**
現行ポートフォリオの受入に「他JWTを維持したまま1つだけ止める」という確認済みの必須要件はなく、B1〜B3でユーザー単位の期限前失効は可能。
台帳・認証read・cleanup・容量制御・非互換切替を追加するコストを、機能が未実装という理由だけで負わない。
本ADRの承認は実装開始・本番導入の許可ではない。§10の要件/容量/移行ゲートを満たした実装Issueを別にレビューする。

| シナリオ | B1〜B3の現在の対処 | jti採用時の効果と限界 |
|---|---|---|
| 今使っているJWTだけlogoutし、他のJWTを維持 | クライアント破棄はコピーを無効化しない。logout-allは全世代JWTを無効化 | 当該JWTの全コピーを401、同世代の別jtiは維持 |
| 盗難JWTだけ止めたい | 本人logout-all、侵害時はpassword変更/ADMIN強制失効 | 対象JWTを特定できる場合に限る。初期案は現在のBearerだけが対象で、別端末から未知の盗難jtiを指定する機能はない |
| 複数端末で利用 | 失効は他の正常端末にも及ぶ | jtiは端末IDではない。同じJWTをコピーした2端末は両方止まり、1端末で別途発行した複数JWTは1回では全部止まらない |
| password漏洩/広範な侵害 | B2/B3と別の復旧手順 | 個別失効は再loginを禁止しない。B2/B3を置き換えず、MFA/アカウント停止/resetも実装しない |
| 複数instance/DB復元 | primary DB世代照会、復元で世代巻戻しの危険 | 共有台帳が必要。古いbackupでtombstoneが消える危険も増える |

| 選択肢 | 利点 | 不足/費用・判断 |
|---|---|---|
| B1〜B3を継続 | 追加台帳なし。緊急時の全JWT失効が既にある | 他JWTへの影響を許容する現時点の推奨 |
| 短いTTLとクライアント破棄 | 状態追加なし | 期限前のコピー拒否を保証せず、単体失効の代替ではない |
| JWT全体のSHA-256 digestを台帳へ保存 | raw JWTを保存せず、jtiなしtokenも文字列単位で識別できる | 技術的な代替案だが、同一文字列は分離不能。発行ごとの一意性は増えず、容量/cleanup/read障害の課題も残る |
| MariaDB jti denylist | 発行時の明示的な識別子。既存primary DB/Tx/監査を利用可能 | 全認証readへの索引参照、行数制御・cleanup・全員再loginが必要。要件承認時の第一候補 |
| Redis等/発行allowlist/refresh・OIDC | 分散TTLやセッション管理など別の利点 | 追加store整合性・運用/移行が必要。今回の前提にしない |

## 2. B1〜B3の実装確認

以下は現mainの事実であり、jtiの提案と区別する。

| 実装・既存テスト | 確認した契約 |
|---|---|
| [JwtUtil](../../src/main/java/com/example/demo/security/JwtUtil.java)、[claims/Clock unit](../../src/test/java/com/example/demo/security/JwtRevocationClaimsTest.java) | HMAC署名・issuer・iat/exp、sub=uid=UUIDv4、tokenVersionは文字列。jtiなし。期限ちょうどから拒否、明示的skew設定なし。TTL既定3600000ms、正の外部上書きに上限なし。発行ごとの別JWTを保証する固有IDはない |
| [Filter](../../src/main/java/com/example/demo/security/JwtAuthenticationFilter.java)、[実HTTP IT](../../src/test/java/com/example/demo/security/JwtFilterRegistrationIT.java) | 署名等を1回parse、不正JWTは認証DB照会前に拒否。Security chainの1経路。公開除外は正確なPOST login/registerのみ |
| [JwtUserAuthenticator](../../src/main/java/com/example/demo/security/JwtUserAuthenticator.java)、[principal](../../src/main/java/com/example/demo/security/AuthPrincipal.java) | primaryへ1 SQLのJDBC projection（id/uid/version/role）、REQUIRES_NEW・readOnly・READ_COMMITTED。業務snapshot/Entity cache/replicaから分離。uid不在/世代不一致401、現在roleで認可。principalにJWT/hash/usernameなし |
| [LoginTokenIssuer](../../src/main/java/com/example/demo/service/LoginTokenIssuer.java) | Tx外のBCrypt後、User行ロック/refreshでUID・保存username・hash・世代を再照合して署名。古いproofを新世代へ繰り上げない |
| [B1 Tx](../../src/main/java/com/example/demo/service/AuthRevocationTransactions.java)、[IT](../../src/test/java/com/example/demo/security/AuthRevocationIT.java) | 本人の世代増分とLOGOUT_ALL監査を同一Tx。204はcommit後、再送401、同世代競合1成功 |
| [B2 Tx](../../src/main/java/com/example/demo/service/PasswordChangeTransactions.java)、[IT](../../src/test/java/com/example/demo/security/AuthPasswordIT.java) | 本人再認証とhash/世代/PASSWORD_CHANGE監査の一括commit。全旧JWT拒否。別ユーザーは維持 |
| [B3 Tx](../../src/main/java/com/example/demo/service/AdminTokenRevocationTransactions.java)、[IT](../../src/test/java/com/example/demo/security/AdminTokenRevocationIT.java) | 現ADMIN＋本人再認証。actor/target重複排除・内部ID昇順ロック、freshなUID/世代/hash/role再確認、FORCE_REVOKE監査と世代更新の同一Tx |
| [User](../../src/main/java/com/example/demo/entity/User.java)、[V7](../../src/main/java/db/migration/V7__add_auth_identity_and_revocations.java)、[V8](../../src/main/resources/db/migration/V8__allow_password_change_audit.sql)、[V9](../../src/main/resources/db/migration/V9__allow_admin_force_revocation_audit.sql) | immutable auth_uid・非負世代・業務PK/FK保持、秘密なし監査。V8/V9は監査action追加。JWT単体の台帳なし |
| [ADMIN CLI](../../src/main/java/com/example/demo/provisioning/InitialAdminProvisioner.java) | V7履歴/checksum等を確認し、既存ADMIN不変・新UID/世代0をINSERTするだけ。JWTを発行せず失効/resetにも使わない |

[B1〜B3 ADR](jwt-revocation-v1.1.md)のPhase A表・OPEN/レビュー待ち表記は当時の履歴。
今回の現状は上のmainとIssue状態で確定し、過去記録を遡及して書き換えない。

## 3. 採用時のJWTと認証条件

loginの発行ごとに `SecureRandom` の32 bytesを生成し、paddingなしcanonical Base64url（43文字）の `jti` を署名済みclaimへ追加する。
username/uid/version/時刻だけから導出せず、同じuid・世代・固定Clockでも異なるjtiを発行する。JWT生成器の全経路で生成し、クライアント入力から採らない。
受信時は文字列型・長さ・alphabet・decode後32 bytes・再encode一致を検証する。署名/issuer/iat/exp/uid/version検証を省略しない。
`iss` は設定値との完全一致、sub=uidとcanonical世代を維持し、jti欠落/型不正/別形式はDB前401。aud/role/refresh等を勝手に追加しない。

認証成功は次の**AND**。台帳に載っていないだけでは成功しない。

1. 正しい署名/issuer/期限/必須claimとcanonical jti。
2. primaryの同一UIDのUserが存在し、JWT世代とDB世代が一致する。
3. issuer+jtiの失効行がない。存在時は401。行のuid/世代/expが署名済みcontextと矛盾する場合は整合性障害500でfail-closed。
4. §5のstore policy/時刻検査・永続expiry floorを満たす。現在DBのroleで既存認可を行う。

`VerifiedJwt`に検証済みissuer/jti/expを追加し、Filterから認証と失効Serviceへ1回のparse結果を渡す実装案とする。
新しいimmutable token contextをrequestの認証principalに紐付けるが、`getName()`のUIDと業務用id/version/roleは維持する。
token contextのtoStringはredactし、raw JWT/passwordをprincipalへ保持しない。body/queryでjtiを上書きさせない。

正常認証は現在と同じ短いREQUIRES_NEW・READ_COMMITTEDの境界で、User、単体失効のPK照会、policyの単一行を**1 SQL/1 statement snapshot**で読む案。
UIDのUNIQUE索引と台帳PKを使い、hashをprojectionしない。policyはLEFT JOIN等で存在を区別し、欠落を「user不在401」に潰さず500。
cache（非失効結果も含む）/read replica/失敗時の空台帳fallbackは採用しない。SQL回数維持は性能同等の証明ではない。

## 4. 採用時の最小APIと失効Tx

初期案は **`POST /auth/token-revocations`、現在のBearer JWT自身だけ**。bodyなし、UID/jti/tokenの入力なし、新JWTなし。
bodyやqueryで対象を指定する要求は400 INVALID_REQUESTとする。任意jtiを受け付けて本人確認するAPIへ拡張しない。
匿名/不正/期限切れ/旧世代/失効済みは401。USERとADMINはいずれも本人の提示JWTだけを失効でき、他人・別JWTの指定権限を新設しない。
標準LogoutFilterの `/logout` と別経路。公開除外は増やさず、正規POSTへ検証済みsource捕捉を追加する。
他人の全端末失効は既存B3、本人の全端末失効はB1、password侵害はB2を使う。ADMIN向け任意jti API、端末一覧、password再認証はこの本人JWT廃棄操作には追加しない。

既存[認証管理limiter](../../src/main/java/com/example/demo/security/AuthMutationRateLimiter.java)に別operationとして参加する案。
接続元＋操作5回/分、全操作共有同時2・状態4096を維持し、login/registerは独立。検証済みproxy以外のXFFやunknown fallbackは採用しない。
単一JVMの制御だけで複数instanceの全体DoSを防げるとは扱わず、DB全体の行数上限を§5で補う。

失効TxはREAD_COMMITTEDで、ロック順 **User（1行）→capacity/policy（1行）→単体失効行→監査INSERT**。
UserのUID/内部ID/受信世代をrefreshして再検証し、検証済みexpと台帳状態も再確認する。Order/Productはロックしない。
初回だけ台帳INSERT・capacity+1・既存監査へ `TOKEN_REVOKE`（actor=targetの内部ID、結果/時刻/requestId）を同一Txでcommitし204・空body。
Userの世代/hash/roleを変えず、同世代の別jtiは有効なまま。成功ログはafterCommitだけ。台帳/counter/監査のどれかが失敗すれば全部rollbackする。

**再送は204を繰り返す契約にしない。** commit後は通常Filterで401。
同じJWTの同時要求が両方Filterを通ってもUserロック下で再照会し、1件204/1件401、台帳/監査/容量は各1だけ。
PK重複を無条件成功と扱ったり、`INSERT IGNORE`等で本物の障害を隠したりしない。
別jtiの並行失効は各1成功、別JWTには影響しない。B1/B2/B3が先にcommitした古い要求は401・追加行なし。
単体失効が先ならB1/B2/B3は別の有効JWTから継続でき、全端末失効とのAND条件で双方を拒否する。

| 失敗条件 | 将来のHTTP契約 |
|---|---|
| 不正/期限切れ/不在/旧世代/失効済みJWT | 401 UNAUTHENTICATED。無効JWTを再認証する特例なし |
| 受付枠超過 / 接続元不明 | 429 AUTH_MUTATION_RATE_LIMITED＋正のRetry-After / 503 AUTH_MUTATION_SOURCE_UNAVAILABLE |
| DB接続断・取得待ち・確認済み認証ロックtimeout/deadlock、容量満杯、時刻確認不能 | 503 SERVICE_UNAVAILABLE。DB等の503にRetry-Afterなし |
| schema/SQL権限/設定/整合性・未知障害 | 500 SYSTEM_ERROR。台帳を読めないことを401や許可へ変換しない |

既存[障害分類](../../src/main/java/com/example/demo/exception/ServerFailureResponse.java)と[認証ロック限定変換](../../src/main/java/com/example/demo/security/AuthStateLocks.java)、
ProblemDetail/requestId、既存403/業務409を維持。Cookie/JWT・password・hash・raw IP・username・jti/台帳keyをログ/エラー/CI artifactへ出さない。
監査にraw JWTやjtiを残さず、台帳はaudit_idの論理参照を持つ案。期限後のJWTの永久追跡は保証しない。

## 5. 台帳、容量、時刻とcleanup

primary MariaDB/InnoDBの追加table案。Redisや全発行JWTのallowlistは作らない。以下の型/値は**将来の初期契約案であり未作成**。

| 保存先 | 項目・制約/索引 |
|---|---|
| `jwt_token_revocations` | issuerの正確なUTF-8 bytesのSHA-256 `issuer_hash BINARY(32)`＋decode済み `jti BINARY(32)` の複合PK。uid CHAR(36) ascii_bin、非負BIGINT世代、署名から検証したexp/失効時刻のUTC DATETIME(6)、audit_id BIGINT、全項目NOT NULL |
| expiry索引 | `(expires_at, issuer_hash, jti)`。認証はPK lookup、cleanupはexpiry順の有界batch。username/hash/raw JWTは保存しない |
| `jwt_revocation_capacity` | singleton id=1、retained_rows（0〜100000）、policy_revision=1、単調な `reject_exp_through`（UTC、初期1970-01-01）。既存User/監査へcascade削除するFKは作らない |

issuer hashは大小文字変換/Unicode正規化/trimをしない。複数issuerの名前空間を分離し、署名済みuid/世代/expも行と突き合わせる。
衝突確率が小さいことを絶対衝突不能とは呼ばない。署名鍵・DB管理者の侵害は別の脅威。

**容量**: 全instance/全issuerで保持行100000を初期上限とする。成功INSERT、counter+1、監査を同一Txにし、singletonをロックした条件付き加算で上限を守る。
expiredでも未削除の行を数える。満杯なら新規単体失効は503・DB無変更とし、期限内の行を捨てたりLRUで成功を装ったりしない。
DBが健全ならB1/B2/B3の世代失効は台帳枠を消費せず利用可能。満杯は既に失効したJWTの再受理理由にならない。
有効JWTを大量取得できる利用者が接続元/instanceを分散して失効すると、共有枠を満杯にして他人の単体失効を妨害し得る。
行数capは容量保護であり妨害防止の証明ではない。採用前にユーザー単位/edgeを含む受付制御・枯渇監視/復旧の要否と費用を承認する。
counter欠落/不正は500。起動検査は同singletonロック下で実COUNTとの一致を確認し、不整合を自動repairしない。
全アプリ経由のINSERT/DELETEが同じ原子的counter更新を通ることが前提。直接DMLによる破壊や監査tableを含む容量運用は別の管理境界。
このsingletonは書込ボトルネックとなるため性能測定が実装可否の条件。通常認証は非ロックreadであり、その上限を待つ設計ではない。

容量計算例は `必要行数 ≒ 失効率 × (最大JWT寿命＋保持余裕) ＋ cleanup滞留`。
仮に1KiB/行で計画すると100000行は約100MBだが**実測値ではなく**、索引/空きpage/MVCC undo/binlog/別の監査/backup/メモリ予算は別途必要。
論理行数の上限は物理diskやRSSの厳密な上限ではない。実EC2容量を推定せず、既存1GiB同居構成の安全性も保証しない。

**寿命とclock**: 現行skewなしを維持し、採用時も受理leeway `S=0`。
将来はJWTの `exp > iat`、最大寿命 `Lmax=24h` を発行/検証の双方に要求する案とする。既定1hは変更せず、外部TTLの上書きは24h以内へ制約する。
現行にはこの上限がないため、新しい設定互換性条件である。実稼働TTLは未確認で、上限超なら起動を拒否し、黙って切り詰めない。
台帳expは検証済みの署名値であり、body/失効要求時のTTLから再計算しない。

validatorとDBの時刻差の許容上限を `Δ=60s` とする設計案。認証/発行/失効でprimaryのUTC時刻を読み、注入Clockとの整合を検査する。
query前後のClockを採り、時刻逆行・差が上限を超える/確認できない場合は503。実host/NTPがこの条件を満たすことは未検証。
cleanupはDB UTCを基準に `expires_at <= db_now - S - Δ` だけを削除候補とする。未来行や世代失効済みという理由だけの早期削除は禁止。

時刻が後から巻き戻っても削除済みJWTを再受理しないよう、同じTxで `reject_exp_through=max(旧値, db_now-S-Δ)` を永続化する。
全認証で `signed exp > reject_exp_through` を追加条件にし、cleanupで行が消えてもこのfloor以下のJWTは拒否する。
cleanup遅延で候補行が残っても安全側であり、古いfloorへ戻す運用は禁止。両時計の同時逆行もClock差の検査だけでは防げないためfloorが必要。

cleanupは1分ごと・1Tx最大500行の案。各instanceから起動してもsingletonで直列化し、**capacity/policy→expiry行**の順で取得、Userをロックしない。
削除件数だけcounterを減らしfloorと一括commit、rollbackならすべて残す。大量滞留を1つの長いTxで解消しない。
現行のpool10/取得5秒/行ロック5秒/Tx10秒/driver timeoutを緩和せず、batch/頻度の成立は実測で判断する。
監査行はdenylist expiry cleanupでは削除しない。監査保持/容量/閲覧・運用権限は別に承認し、無期限に安全と仮定しない。

## 6. commit境界・競合・障害

失効commit後にprimary認証readのstatement snapshotを開始したJWTは401。それ以前に認証済みの注文等は継続し得る。
すべての認証readをUser/台帳FOR UPDATEで直列化する案は採らず、実行中業務の取消・応答直前までの瞬時停止を保証しない。

| 競合 | 採用時の不変条件 |
|---|---|
| 同じjti、異なるinstance | User→singletonの共通順とPKで1 commit。敗者401、audit/counter各1、例外はFutureから回収 |
| 異なるjti、同世代/同uid | 各失効を独立に記録し、第三のjtiを維持。tokenVersionは増やさない |
| B1/B2/B3と失効 | 同じUserロックで世代再確認。旧proofを新世代へ繰り上げない。B3の複数User昇順に逆順の依存を追加しない |
| cleanupと認証 | 1 statementでfloorとtombstoneを読む。削除前snapshotは行で拒否、削除後snapshotはfloor/expで拒否 |
| cleanupと失効/容量境界 | 共通singleton→台帳順、cleanupからUserへ戻らない。実削除分だけ空きを返し、有効期限内の行を犠牲にしない |
| DB断/監査INSERT故障/commit失敗 | 許可や偽204へfallbackしない。未commitなら台帳/容量/監査全部rollback。接続断時のcommit結果不明は再認証で確認し、無条件の書込retryをしない |

容量/cleanupの単一rowとUser待機のため遅延は増え得る。deadlockの全スケジュール不在、複数instanceの負荷上限、SLAを設計だけで保証しない。
DB read不能時は全保護APIをfail-closedとし、失効APIだけ停止して他APIが台帳検査を飛ばす方式は不可。
clock差の障害も認証不能となるため、監視・復旧性が運用前条件。OSV/Trivy成功はこれらの成立証明ではない。

## 7. 旧JWT移行・ADMIN・rollback

将来の追加migrationは実装時の最新番号を確認して **V10以降**に新table/counter/policyとTOKEN_REVOKE監査CHECKを追加する。
V1〜V9の書換え、全userのUID/hash/世代の再生成、既存監査の削除は行わない。既存audit actionも許可し続ける。
複数DDLの一括rollbackや任意中断後の自動再開は保証せず、部分DDL/履歴/checksum/件数のpreflightと停止復旧を実MariaDBで検証する。
通常アプリの台帳read/INSERT、capacity更新、監査INSERTとcleanupのDELETEの権限を最小化し、migrationのDDL権限とは分離して評価する。
ADMIN CLIはJWTを扱わず既存User INSERTだけなので、jtiの操作権限を追加しない。通常起動でADMINを作らない契約も維持する。

移行手順案:

1. 全validator・login発行processを停止/drainし、承認済みbackupと復旧計画を確認する。PR/設計作業から実環境操作はしない。
2. 追加schema/policyを検証し、counterと実行数、floor、索引、旧行/checksum保持を確認する。自動repair/clean禁止。
3. **jti必須の新binaryだけ**で再開。B1〜B3由来のuid/version付きでもjtiなしJWTは期限内でも401、全員再login。dual acceptance・任意jtiの補完はしない。
4. 新JWT Aの単体失効401、同世代Bは200、B1〜B3で両方401、他ユーザー正常を確認する。公開login/registerは既存契約のまま。

旧B1〜B3 binaryは未知の追加jtiを無視して世代だけを検証するため、正しく署名された単体失効済みJWTを受理し得る。
新旧混在fleet、台帳feature flagをoffにする復帰、cache/replicaへの退避は禁止。原則は同じ失効検証を保つforward fix。
古いbackupはtombstone/counter/floor/世代/hashを巻き戻す。**復元後に世代を単純に+1しても、復元後の値が過去の発行世代と衝突し得るため、全JWT拒否の保証にはならない。**
復元/異版rollbackは受付停止と、承認された鍵更新等による全JWT再無効化・全員再loginの計画が必要。今回、鍵や実secretを操作しない。
[現行の移行runbook](../security/jwt-revocation-migration.md)と同様、同一image IDの設定故障復帰は異なる旧binaryの安全なrollbackを実証しない。

## 8. 実装する場合の受入試験（全項目未実施）

| ID | 必要な実MariaDB/HTTP・固定Clockの証拠 |
|---|---|
| C01 | 同uid/世代/Clockでも発行jtiが異なる。署名/issuer/iat/exp・必須claim・43文字canonical jti、6JWT否定系をDB前に拒否 |
| C02 | 同世代のA/Bを200→A失効204→A全コピー401/B200、他User不変。B1/B2/B3なら両401。jti単体指定/username/role注入不可、ADMINも他人指定不可 |
| C03 | DB接続ID/別Tx/2つのapp contextとlatchでcommit前後を固定。認証parse1回/1SQL、古い業務RR/Entity cacheが再受理しない。commit前の処理継続を区別 |
| C04 | 同じjti並行1成功/残り401、別jti各成功、B1/B2/B3/password再認証/権限変更・UID削除再作成との競合。counter/監査整合、業務不変/pool残留0 |
| C05 | expちょうど/直前直後、S=0、clock差±60s境界、DBだけ/validatorだけ/両方の逆行、cleanup後の旧JWT、24h寿命/設定上限・拒否を検証 |
| C06 | expiry batch500と並行INSERT/cleanup、99,999→100,000→満杯503、複数instanceでも上限維持。失敗rollback後の空き/再実行、counter不正は500、未期限行を削除しない |
| C07 | DB接続断/取得待ち/行ロック/SQL設定/整合性/監査INSERT・cleanup故障で503/500を区別。失効行を読めないときに200や401へ潰さない。秘密canary非露出 |
| C08 | 空DB/既存V9から追加migration、全user/uid/hash/世代/注文/FK/監査/checksum保持、DDL中断と再実行。CLIの既存ADMIN不変と追加権限不要を確認 |
| C09 | 1万/10万行、複数instanceでPK/expiryのEXPLAIN・実rows/latency・pool/lock/CPU/RSS/disk/cleanup滞留を測定。単純な長時間sleep、pool/timeout拡大、skipは禁止 |
| C10 | 旧binaryが単体失効を無視すること、旧backup復元で再受理し得ることを隔離環境で負の対照として実証。無害な設定故障復帰と区別 |
| C11 | Java17 full verify基準2370件＋必要追加、Python29、Terraform mock27、Secret例外0/SpotBugs/OSV/Trivy、Docker元9段階+B1/B2/B3+C、PR HEAD CI5ジョブ成功 |

これらは**将来の受入条件**であり、本PRのCI成功をjti機能の試験成功と呼ばない。
性能/SLAの閾値は利用要件と実測から実装レビュー時に承認する。閾値未承認・実測未実施では本番容量や可用性を保証しない。

## 9. 今回の証拠と残余

今回の変更は本ADRと既存ADRからの参照だけ。実Java/schema/runtime/CIのblob一致・文書リンク・diffを確認し、
同じPR HEADで既存Java全回帰とCI5ジョブを確認する。実run URL、件数、初回失敗/再試行はPR本文に記録する。
基準mainのCI成功はB1〜B3の回帰証拠であり、未作成のjti台帳/cleanupの証拠ではない。

Windows DatabaseTimeoutITの過去の初回DB観測CLI3失敗、Caddy/MariaDB証明書時刻の過去観測は根因未確定として保持する。
Trivy OS49件の既存観測（OSV0件と別）、Flyway互換性WARN、異版rollback/本番復旧未検証も継続する。
実AWS・TLS・secret設定・DB・容量・SLA・復旧は未確認。本番運用は **PRODUCTION NO GO**。
AWS/Deploy/実secret/本番DB/既存volumeには触れず、PR #103は対象外。本PRは未merge・auto-mergeなしで独立レビューを待つ。

## 10. 実装フェーズと判断ゲート

1. **C-A（今回）:** 選択的logoutの必要性、API範囲、全員再login、台帳/clock/容量/rollback負担を独立レビューする。Issue #134は閉じない。
2. **C-B（別Issue・独立PR、要件承認後のみ）:** jti発行/必須検証＋追加migration＋全認証read＋本人単体失効＋原子監査/counter＋安全なcleanup/floor＋互換切替を一体で実装する。
   jtiだけ発行、台帳だけ書込、cleanupなし等の不完全な稼働版を分割mergeしない。PRが大きくなれば先に分割設計を再承認する。
3. **C-Bの採否:** 24h TTL ceiling/60s clock境界/100000行/500行batchの提案値、実利用者が許容する再login、監査保持/運用責任、C01〜C11と容量実測を承認できなければB1〜B3を継続する。
4. **別段階:** 端末セッション一覧・別端末からの任意token失効・ADMIN単体失効・refresh/reset/MFA・分散storeは今回の方式に混ぜない。

## 一次資料

2026-10-21参照。標準の説明と本アプリの提案値を区別する。

- [RFC 7519 §4.1.7](https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.7): jtiは衝突確率を小さくしたJWT識別子。jtiだけで失効処理が自動的に実装されるわけではない。
- [RFC 7519 §4.1.4](https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.4): expの受理境界と任意の小さなclock leeway。S=0/Δ=60s/floorは本ADRの設計案。
- [RFC 8725 §3.8 / §3.10](https://www.rfc-editor.org/rfc/rfc8725.html#section-3.8): issuer/subject照合と受信claimを無検証で検索等に使わない根拠。
- [MariaDB READ COMMITTED](https://mariadb.com/docs/server/reference/sql-statements/administrative-sql-statements/set-commands/set-transaction#read-committed): statementごとのcommitted snapshot。具体的JOIN・索引/競合/性能の成立はC-Bの実DB試験が必要。

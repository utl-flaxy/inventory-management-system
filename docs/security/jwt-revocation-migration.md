# JWT B1/B2移行・復旧手順（Issue #136 / #138）

## B3追加移行（V8済み環境からV9へ）

[V9](../../src/main/resources/db/migration/V9__allow_admin_force_revocation_audit.sql)は監査actionのCHECKを
LOGOUT_ALL / PASSWORD_CHANGE / FORCE_REVOKEの3種にする単一ALTERだけ。V1〜V8とusers/hash/UID/世代/業務/FK/監査行は変更しない。
新規DBはV1〜V9、B2済みDBはV9だけを適用する。[実MariaDB IT](../../src/test/java/com/example/demo/repository/AdminAuditMigrationIT.java)で
全既存行・V1〜V8履歴/checksum保持、追加actionの許可、未知action拒否、再実行0件、予期しないCHECK欠落時の停止を検証する。
DDLは全体rollbackや任意のhost crashからの自動再開を保証しない。CHECK欠落の故障注入ではV9成功にならず、旧データは保持されるが運用者のschema確認が必要。

運用者は承認済みbackup、migration権限、監査table容量/ALTER待機を確認し、受付停止/drain後に適用する。
V9成功履歴と3actionのCHECK実体を照合しB3 binaryだけで再開する。失敗時は停止を保ち、原因と実DDL状態をレビューする。
自動repair/clean、CHECK無効化、監査削除で復旧しない。旧B2/V8へimageだけ戻すとB3失効APIがなくなるため、forward fixを優先する。
世代を検証しない旧binaryや古いDB backupへの復帰は失効保証を失う。異版rollbackの安全性は未検証。

専用の使い捨てADMIN/対象で、USER403、ADMIN本人の誤password401、対象強制失効204→旧JWT401→同password再login200、
自己失効204→旧ADMIN JWT401、FORCE_REVOKE監査のactor/targetを確認する。CLIは既存ADMINのresetや強制失効へ流用しない。
公開role変更・password reset・個別JWT失効は追加しない。Issue #79は独立レビュー/mergeまでOPEN、本番運用NO GOを維持する。

以下のB1/B2節は各段階の移行契約として保持する。新規受付再開時はV9までの成功を確認する。

契約: [レビュー済みADR](../design/jwt-revocation-v1.1.md) §3〜§7、[認証API](../auth.md#jwtライフサイクルと失効制約)。
Java17 / Spring Boot4.1.1 / MariaDB12.2.2を対象とする。実環境の移行・鍵変更は実施していない。
実利用者向け **PRODUCTION NO GO** は継続する。

## 互換性と停止境界

- V7は既存usersのPK・username・password hash・role、注文/FK、V1〜V6を保持する追加migration。
  `auth_uid CHAR(36) ascii_bin UNIQUE NOT NULL` にJava UUIDv4を一度だけ割当て、
  `token_version BIGINT NOT NULL DEFAULT 0 CHECK >= 0` と専用監査tableを追加する。
- 新JWTは `sub = uid = auth_uid`、`tokenVersion` は非負BIGINTのcanonical十進文字列。
  username主体・claim欠落・不正型の旧JWTは、署名/期限が有効でも401。dual acceptanceはなく全端末再ログインが必要。
- **旧validatorを停止・drainしてから移行し、新binaryだけで受付を再開する。混在fleetは禁止。**
  古いvalidatorは世代を検証しないため、追加schemaだけで安全な互換運転にはならない。
  アプリ、バッチ、ADMIN CLI、運用ツールをすべて版管理する。

## B2の追加移行（V7済み環境からV8へ）

[V8](../../src/main/resources/db/migration/V8__allow_password_change_audit.sql)は、監査actionのCHECKを
LOGOUT_ALL / PASSWORD_CHANGEの2種類へ拡張する一つのALTER TABLEだけ。V1〜V7、既存user/hash/uid/世代/業務/監査行は変更しない。
新規DBはV1〜V8、B1済みDBはV8だけを適用し、履歴/checksum・既存行保持・再実行0件を
[実MariaDB移行IT](../../src/test/java/com/example/demo/repository/PasswordAuditMigrationIT.java)で確認する。

運用者は承認済み保全先、DDL権限、監査tableの実サイズとALTER待機を確認し、受付停止/drain後に適用する。
V8成功履歴とCHECK定義の一致を確認してからB2 binaryで受付再開する。V8未適用でpassword変更を稼働させない。
Flyway失敗履歴・予期しないschemaでは停止を維持し、状態を確認してレビューする。自動repair/clean、CHECK無効化、
監査行の削除による復旧は行わない。DDLの途中失敗・あらゆるhost crashからの無条件な再開を保証するものではない。
ADMIN CLIはB1のV7 checksum確認・INSERT専用最小権限を維持し、password変更や既存ADMINのresetには使わない。

専用の使い捨て検証アカウントで、`POST /auth/password` の204→旧JWT全401→旧password login401→新password login200を確認する。
bodyはcurrentPassword/newPasswordだけを安全なクライアントで送る。値をshell引数・history・ログ・artifactへ保存しない。
実環境のTLS・運用者設定や実ユーザーのpassword変更はこのPRで実施していない。

## 運用者が実環境で確認する手順

1. 承認済み停止時間、全validator/CLIの所在、対象DB、移行権限、復旧用の保全先を確認する。
   実際の容量・SLA・処理時間を使い捨てDBの結果から推定して承認しない。
   現行は起動Flywayとappが対象DB限定の既存DDL権限を共用する。実環境でのmigration用資格情報と
   通常appのDML権限の分離は別途運用確認が必要であり、本PRで既存DBの権限を変更したとは扱わない。
2. 既存の[backup/restore手順](../db/README.md)で取得・整合性・復元先を確認する。
   保全先へ秘密値を公開しない。raw table/credential/解決済みComposeをログへ出さない。
3. 新規受付を止め、進行中要求をgraceful shutdownでdrainし、旧processが残っていないことを確認する。
   [DB待機・shutdown契約](../configuration.md)のpool10、取得5秒、ロック5秒、Tx10秒は緩和しない。
4. V1〜V6の履歴/checksumとInnoDBを確認し、新binaryのFlywayでV7を適用する。
   V7はJava migration `db.migration.V7__add_auth_identity_and_revocations`、固定checksum `1360701`。
   SQL migrationのディレクトリにV7.sqlを追加する方式ではない。公開後はV7を書き換えない。
5. 成功したV7履歴、uidの非NULL/一意/UUIDv4形式、非負世代、監査table、既存PK/FK/件数の保全を確認する。
   B2 binaryでは続けて上記V8を適用し、V8成功と両actionのCHECKを確認する。
   管理者向けの集計結果だけを残し、uid・username・hashをartifactへ出さない。
6. 新binaryだけを起動し、readiness、旧JWT401、新ログイン、USER/ADMIN境界、注文/在庫を確認する。
   `POST /auth/logout-all` の204→旧JWT401→再ログインも専用検証アカウントで確認する。
   実ユーザーを無断で失効させない。新規ADMINが必要な場合だけ[新CLI](../initial-admin-provisioning.md)を明示実行する。
7. 問題がなければ受付を再開し、秘密値なしのrequestId・安全なエラー分類・DB負荷・監査容量を観測する。
   このPRの実証は使い捨て環境だけであり、上記本番作業を完了したとは扱わない。

## MariaDB DDL途中失敗

MariaDB DDLの一括rollbackは保証しない。V7は200行ずつNULL uidだけをbackfillし、既に割当てたuid/世代を再設定しない。
部分列の型/データ、重複/形式/世代、table engineを検査し、不適切な状態は停止する。
restartableなmigration本体と、Flywayの失敗履歴を自動的に消すことは別である。

- 受付を停止したままにし、どのDDLまで完了したか・成功/失敗履歴・backupを運用者が確認する。
- 不整合データを削除したり、全uidを再生成したり、世代を0へ戻したりしない。
- 既存列・制約・監査tableがV7定義と一致するかもレビューする。任意の既存schemaの自動修復は保証しない。
- 原因を解消し、残存DDLと保全を確認したうえで、失敗履歴の扱いを独立承認する。
  `repair` / `clean` を起動時やCIで自動実行する処理はない。
- `AuthIdentityMigrationIT` はDDL後の故障と本体再開、uid不変、Flyway失敗履歴による再起動拒否を実証する。
  巨大な既存DBでの停止時間や、あらゆる外部DDL改変からの自動復旧は未保証。

## 失効・監査・不確実な応答

logout-allは本人Userだけをロックし、受信世代を再照合してSQL増分と監査INSERTを同じTxでcommitする。
監査はactor/targetの内部ID、LOGOUT_ALLまたはPASSWORD_CHANGE、SUCCEEDED、時刻、server requestIdのみ。
password/hash/JWT/username/raw IPを含めず、User削除にcascadeしない。アプリに監査閲覧APIや自動削除処理はない。
閲覧は承認済みDB権限に限定し、保存期間・容量・保全/削除の運用承認は公開前の課題とする。
通常ログの成功eventはcommit後だけ発行する。失敗ログには安全な型とrequestIdを使う。

204はcommit後。同じ世代の並行要求は1成功、他は401であり、同JWTでの再送に204を保証しない。
認証readがcommit後なら旧世代は401だが、既に認証済みの業務を取り消す機能ではない。
Tx rollbackでは世代/監査の両方を戻す。一方、通信断やcommit時の障害で結果が不明な場合、
500/503を「必ず未commit」と決めつけない。再認証と承認済み監査確認で判断し、盲目的な書込みretryをしない。
password変更も、事前の現password照合と新hash計算はTx外・共有permit内で行い、短いロック下でuid/内部ID/世代/事前hash/roleを再確認する。
hash更新・世代増分・成功監査を同一Txにし、logout-all/二重変更との競合で古い認証結果から新世代へ更新しない。
明確なrollback時は3点すべて不変。commit後は全端末で新passwordによる再loginが必要（新JWTは204に含めない）。

## rollback / backup復元の制約

Docker検証のrollbackは**同じB1/B2 image IDの設定故障からのapp-only復帰**である。
DBコンテナを再作成せず、更新hash・世代と監査を保持し、旧JWT401/再ログインJWT有効を確認する。
異なる旧アプリバイナリへの安全なrollbackを実証したものではない。

世代検証のない旧imageへ戻すことは禁止。旧imageは旧JWTを再受理し得る。
新uid必須列に対する旧CLIのINSERTも失敗するため、旧CLIを継続使用しない。
追加列を残しても旧validatorの安全性は回復しない。破壊的なdown migrationは用意しない。
問題時は停止を維持し、新validatorを保持した修正版による復旧を優先する。

DBを過去backupへ復元するとtoken_versionも古くなり、以前失効したJWTが再受理され得る。
password hashも巻き戻り得るため、古いpasswordが再び有効になるリスクを含めて判断する。
復元テストはuid/世代/監査を保存できることを示すだけで、失効の単調性を復元越しに保証しない。
復元時は全validator停止、JWT鍵・全JWT拒否方針の独立承認、新規発行まで含めた再検証が必要。
旧鍵へ戻すと旧署名が再び有効になり得る。漏洩鍵の再利用は禁止。実secretの取得・変更は本PRでは行わない。

B3 ADMIN強制失効は上記の契約で実装。[jti個別失効](https://github.com/utl-flaxy/inventory-management-system/issues/134)、
refresh、アカウント停止、MFAは未実装。[Issue #79](https://github.com/utl-flaxy/inventory-management-system/issues/79)はOPENを維持する。

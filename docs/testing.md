# テスト品質ゲート

## Java 17・実行コマンド

高速なローカル回帰（Docker不要）:

```bash
./mvnw test
```

完全検証（PR前必須・GitHub CIと同じ品質ゲート）:

```bash
./mvnw verify
```

Windowsでは `mvnw.cmd test` / `mvnw.cmd verify`。Java 17をJAVA_HOMEで選択する。
古いレポートを消して件数を比較する場合は `clean test` / `clean verify` を使う。
`test` の成功だけではmerge可能とは判断しない。

## 命名と実行phase

| 分類 | 命名 | plugin / phase | 対象 |
|---|---|---|---|
| Unit / lightweight | `Test*`, `*Test`, `*Tests`, `*TestCase` | Surefire / test | domain、Mockito、JWT、Controller slice、H2 JPA補助 |
| Integration | `*IT` | Failsafe / integration-test + verify | MariaDB、Flyway、実Security + JWT + DB、実lock/Transaction |

Surefire/FailsafeのversionはSpring Boot parent管理（現行3.5.5）を使う。
標準[Failsafeのライフサイクル](https://maven.apache.org/surefire/maven-failsafe-plugin/usage.html)を使用し、
ITの失敗はverify goalで非zero終了にする。独自runnerやfailure-ignoreは使わない。
CIはJava 17で `./mvnw verify` を実行し、timeoutは10分を維持する。

Unit tests: Surefire、Integration tests: Failsafe。両方の件数・failure/error/skippedはMavenログに表示される。
レポートは `target/surefire-reports` / `target/failsafe-reports`（標準配置）。
`verify` はcompile → Unit → package → Integration → verifyを実行する。

## 必須MariaDB統合テスト

起動済みDockerとMariaDB 12.2.2イメージの取得が必要。CIはDockerが使えるrunnerを前提とする。
TestcontainersをDocker未起動時にskipしない。接続不能やpull失敗はbuild失敗となる。
本番DB・EC2・本番秘密値は使わず、各classの使い捨てMariaDBを使う。

| class | 回帰対象 |
|---|---|
| AuthorizationMatrixIT | 実SecurityFilterChain、JWT、DB role、所有者/ADMIN境界、DB不変、並行操作 |
| InventoryConcurrencyMariaDbIT | 旧InventoryConcurrencyMariaDbTest。実lock、同時注文/cancel、version、rollback |
| SchemaConstraintIT | Flyway + validate、MariaDB NOT NULL/CHECK/FK/UNIQUE/index |
| EmptyDatabaseMigrationIT | 空DB migration、実起動・再起動、schema drift、baseline拒否 |
| LegacyDatabasePreflightIT | 既存DBの不正データ/不足履歴での停止とDB不変 |
| AuthApiIT | register/login、BCrypt、資格情報非露出、role注入拒否、DB role優先 |
| SalesQueryIT | COMPLETEDのSUM/COUNT、全4状態、期間境界、legacy NULL、UTCマッピング |
| MoneyApiIT | 高額Product/Order API、snapshot/CSV、OpenAPI int64、Long overflow時DB不変 |
| MoneyMigrationIT | 既存値/V1履歴保持、V1→V2、BIGINT境界と制約、再実行なし |
| SalesApiIT | 固定Clock、実JWT/ADMIN、日時400、期間境界、完了日時レスポンス/OpenAPI |
| CompletionMigrationIT | V1/V2→V3、既存値/履歴保持、legacy NULL、validate、条件付き手動補完 |
| IdempotentOrderIT | header必須、実JWT、replay/conflict、snapshot、20同時送信×3、mixed body、scope、legacy、OpenAPI |
| IdempotencyMigrationIT | V3→V4、既存全4状態/履歴保持、ascii_bin列、customer/key UNIQUE、validate、再起動pending0 |

Issue #27はmain基準519件を削除せず、売上の期待値を正式なCOMPLETED計上ルールへ更新する。H2補助テストはSurefireに残す。
Issue #27のJava 17検証はSurefire 288件 + Failsafe 277件 = 565件（46件追加）、failures/errors/skipped=0。
Docker接続先を無効にしたclean testでも288件成功し、Docker不要のUnit品質ゲートを維持する。
Issue #29は565件を削除せず、既存createテストへ一意keyを付ける。Docker不要のUnitを33件、実MariaDBのITを37件追加する。
Java 17でDocker接続先を無効にした `mvnw.cmd clean test` は321件成功。
`mvnw.cmd clean verify` はSurefire321件 + Failsafe314件 = 635件成功、failures/errors/skipped=0。
OrderRequestFingerprintTestは既知UTF-8/SHA-256ベクトル、明細順、ID/数量差、64文字lowercase hexを検証する。
OrderIdempotencyServiceTestはkey拒否・User lock順序・scoped lookup・replay/conflict時Product未アクセス/未保存・対象外制約を検証する。
IdempotentOrderITは実SecurityFilterChainとJWTを通す20同時HTTP送信×3、10+10異なるbody、別key/別customerをbarrierで同期する。
全Futureを回収し、Executor終了・timeoutを明示する。新APIはtest都合でheader optionalにしない。
H2の成功でMariaDB CHECK・lock・Flywayの正しさを代用しない。
DeploymentWorkflowTest / DeploymentScriptTestはYAML/Bashの静的・mock制御テストであり、
本番Docker配備を行わないためSurefireに残す。Deployの運用上の無効化状態は変更しない。

ITだけを開発中に選択する場合:

```bash
./mvnw verify -Dit.test=AuthApiIT,SalesQueryIT
```

このコマンドもUnitを実行するが、PR前には絞り込みなしのverifyを必ず実行する。
各ITのDBはclass単位で分離し、Authは毎回ユーザーを削除、SalesはTransaction rollbackで破棄する。
実行順への依存やstatic共有レコードは作らず、既存の並行実行timeoutも維持する。

## Authの固定する挙動と残る課題

登録はROLE_USERを強制し、clientのADMIN指定を無視する。DBのpasswordは実BCrypt hash。
User.passwordのJSON出力だけを抑止し、登録のpassword/hash非露出を保証する。
loginは署名・issuer・有効期限を持つJWTだけを返す。JWT payloadにpassword/hash/署名鍵は含めない。
認可にはJWTのrole claimを使わず、毎回usernameから取得した現在のDB roleを使う。

Issue #4ではAuthの入力Validation/例外設計を変更しないため、以下は現行挙動のcharacterization testとする。
これらを安全な最終仕様とは扱わず、別のAuth入力Validation/エラー応答Issueで修正すべき課題として残す。

- 重複username、registerのnull username/passwordは500（SYSTEM_ERROR）。ユーザーは追加されない。
- blank username/passwordは現状登録可能。BCrypt保存とレスポンス非露出を確認する。
- wrong password、存在しないuser、loginのnull/blank入力は現行500。JWT/資格情報を返さない。

将来Authの仕様を改善する際は、そのIssueでHTTP status/Validationのテストも更新する。

## Salesの現行仕様

Issue #27から、COMPLETEDかつcompletedAt非NULLのみをSUM/COUNTの単一DB queryで集計する。
PENDING/SHIPPED/CANCELLEDと完了日時不明のlegacy行は、全期間/期間集計ともに除外する。
空なら0L/0L、0円の完了注文も件数に含む。totalOrdersは売上対象注文件数であり、全注文数・明細数ではない。
固定Instantで下限inclusive/上限exclusive・片側省略・orderedAtを使わないこと・UTC DATETIME(6)のround tripを検証する。
OrderCompletionTest/SalesServiceTestはdomain invariantと不正期間時のRepository未呼出をDockerなしで検証する。
InventoryConcurrencyMariaDbITは20操作の同時completeを3回実行し、成功1件/他409相当・日時不変・1注文分の売上を確認する。
時刻の検証にはClockと固定Instantを使い、Thread.sleepを使わない。詳細は[売上仕様](sales.md)。

## 金額のLong/BIGINT回帰（Issue #25）

金額は円の整数値をLong/longで扱い、DBはV2でBIGINTへ移行する。V1は不変。
商品APIは1円以上でJSON number/field名を維持し、OpenAPIはint64。数量・在庫はint32のまま。
Money Value Object、BigDecimal、小数/多通貨は未導入。
Math.multiplyExact/addExactのlong演算で、注文のoverflowを在庫変更前に400で拒否する。
UnitのOrderAggregateTest/OrderServiceTestもLong境界に対応し、Dockerなしで実行する。
Integrationでは30億円×2個=60億円、複数明細180億円、価格変更後のsnapshot、CSVの整数出力、
0L/70億円のJPQL SUM(Long)・totalSales、V1→V2の20億円保持・制約・再起動を検証する。
Long.MAX_VALUEの商品も許可し、quantity=1は成功、quantity=2や合計のLong overflowは400となる。
API clientもint64を保持できる型で扱う。COMPLETEDの30億+40億=70億、PENDINGの50億/CANCELLEDの60億除外も検証する。

## 別Issueへ分離した内容

注文冪等性はIssue #29で[必須headerとreplay契約](orders/idempotency.md)を実装し、実IdempotentOrderITで検証する。
GlobalExceptionHandlerは現行Map形式のまま。ProblemDetail導入と契約テストはその機能Issueで扱う。
coverage/scanner gate、branch protection変更、Deploy有効化、JWT_SECRET設定、EC2操作も対象外。

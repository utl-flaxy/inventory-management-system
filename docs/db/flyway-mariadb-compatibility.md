# Flyway / MariaDB互換性判断（Issue #69）

Issue #89のBoot 4.1.1 PoCでは、公式BOMのFlyway core/mysql 12.4.0とJDBC 3.5.10を検証する。
MariaDB server 12.2.2、DB制約、migration V1〜V6、既存volumeは変更しない。
専用Testcontainers DBでFlyway11.7.2→12.4.0→11.7.2のvalidate/migrateとhistory checksum、保存dataの不変性を確認した。
通常起動と旧Flyway rollback fixtureの警告件数は、[Issue #89の比較](../audit/2026-10-12-issue89-boot4.md)とPR本文へ実測を記録する。
以下のIssue #69/#86の記録は履歴として保持し、警告をlogger設定で隠さない。

Issue #86ではBoot 3.5.16のセキュリティBOM更新に伴い、JDBC clientを3.5.8へ更新する。
Flyway core/mysql 11.7.2、MariaDB server 12.2、migration V1〜V6、既存volumeは維持する。
以下はIssue #69時点の履歴であり、JDBC patch後の全回帰・警告実測は
[Issue #86のBOM監査比較](../audit/2026-10-12-issue86-boot-bom.md)とPR本文に別記する。
今回のJDBC更新はセキュリティ/BOM是正であり、互換性警告を消す目的ではない。

## 判断と対象

2026-10-11、main `54f2a30f43106a6691197c7dc7461fe1e743cc04` を基準に調査した。
**選択肢C: runtime versionの変更を保留する。互換性警告は残す。**
Boot管理のFlywayを単独で更新する候補は実起動に失敗した。既存volumeのDB version変更も行わない。
本判断はMariaDB 12.2の公式認証や、将来のmigrationの安全性を保証するものではない。

## 実versionと警告の証拠

Java 17を選択して次を実行し、effective POMと解決後の依存ツリーを確認した。
Windowsでは `./mvnw` を `.\mvnw.cmd` に読み替える。

```bash
./mvnw help:effective-pom -Doutput=../issue69-effective-pom.xml
./mvnw dependency:tree '-Dincludes=org.flywaydb:*,org.mariadb.jdbc:*'
```

| 対象 | 解決済みversion / 設定 | 根拠 |
| --- | --- | --- |
| Java / Maven | Temurin 17.0.18 / wrapper経由3.9.14 | `java -version` / `mvnw -version` |
| Spring Boot | 3.5.12 | parent / effective POM |
| flyway-core / flyway-mysql | 両方11.7.2 | Boot BOM / effective POM / dependency:tree。overrideなし |
| MariaDB JDBC | 3.5.7 | Boot BOM / dependency:tree |
| Jackson BOM | 2.19.4 | Boot BOM / effective POM |
| Compose DB | `mariadb:12.2` | `docker-compose.yml`。調査日の取得済みimageは12.2.2 |
| Testcontainers DB | 主に `mariadb:12.2.2` | migration / API IT。BackupRestoreITはComposeと同じ `12.2` tag |

取得済みの両imageへ `docker run --rm --network none --entrypoint mariadbd IMAGE --version` を実行し、
両方とも `12.2.2-MariaDB-ubu2404` であることを確認した。DBは起動せず、既存volumeもmountしない。
minor tag `12.2` は可変なので、将来もpatchが同一とは仮定しない。
Terraform userdataはDB/image/volumeを起動せず、db-init scriptもDB versionを選択しない。
backup/restoreは稼働DB container内のbinary/socketを使う。別のhost DBやdriverを選択しない。

[PR #68の実CI run 38131627192](https://github.com/utl-flaxy/inventory-management-system/actions/runs/38131627192)
のJava job logで次のmessageを **95回** 確認した。
loggerは `org.flywaydb.core.internal.database.base.Database`、levelはWARN。

```text
Flyway upgrade recommended: MariaDB 12.2 is newer than this version of Flyway and support has not been tested. The latest supported version of MariaDB is 11.2.
```

これはSQLエラーでもJDBC driverの接続エラーでもない。
[Flyway 11.7.2のMariaDBDatabase](https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/mariadb/MariaDBDatabase.java)
が検証上限 `11.2` と実server version `12.2` を比較して出している。
複数のIT context起動やFlyway操作で繰り返すため、95件を95種類の不具合と解釈しない。
loggerのOFF設定やfilterは追加しない。

## 公式情報の境界（取得日2026-10-11）

- [Boot 3.5.12 BOMの定義](https://github.com/spring-projects/spring-boot/blob/v3.5.12/spring-boot-project/spring-boot-dependencies/build.gradle)
  はFlyway 11.7.2 / MariaDB JDBC 3.5.7を管理する。DB専用moduleを追加し、
  [Bootの初期化仕様](https://docs.spring.io/spring-boot/3.5/how-to/data-initialization.html)に従ってmigrate後にHibernateをvalidateする。
- [Flyway MariaDB reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mariadb)
  （2026-10-09更新）はVerified Versionsに5.1 / 12.3.2、driverに2.0.0以降を記載する。
  この現在の表はFlyway 11.7.2 + MariaDB 12.2を過去に検証したという証拠にはならない。
- [Flyway support matrix](https://documentation.red-gate.com/flyway/getting-started-with-flyway/system-requirements/supported-databases-and-versions)
  はSupportedとCompatibleの検証・サポート範囲を区別する。MariaDB 10.11等の掲載があり、12.2は明示掲載されていない。
  driver referenceのVerified Versionsと、edition / support levelの表を同じ意味で扱わない。
- [Flyway release notes](https://documentation.red-gate.com/flyway/release-notes-and-older-versions/release-notes-for-flyway-engine)
  では11.7.2は2025-04-17、11.20.3は2026-01-26、12.11.0は2026-07-09。
  調査時の最新13.10.0（2026-10-08）へ一括更新しない。
  12.0.0にはbreaking changes、12.0.3にはJackson 3への変更が記載されている。
  12.11.0のrelease notesだけではMariaDB検証上限の変更を特定できないため、下記のrelease tagの実装も照合した。
- [11.20.3のMariaDB実装](https://github.com/flyway/flyway/blob/flyway-11.20.3/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/mariadb/MariaDBDatabase.java)
  は上限11.7。
  [12.10.0](https://github.com/flyway/flyway/blob/flyway-12.10.0/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/mariadb/MariaDBDatabase.java)
  の11.7から、[12.11.0](https://github.com/flyway/flyway/blob/flyway-12.11.0/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/mariadb/MariaDBDatabase.java)
  で12.3へ変わることを確認した。これは警告判定の上限であり、12.2の個別の公式認証とは区別する。
- [MariaDB Connector/Jの対応表](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j)
  は3.5系列のJava 17対応とMariaDB serverとの互換性を記載する。
  [3.5.7 release notes](https://mariadb.com/docs/release-notes/connectors/java/3.5/3.5.7)も確認した。
  JDBCを更新してもFlyway自身の上限判定は変わらない。

## 更新候補の実験と選択肢比較

checkoutのPOMは編集せず、Maven propertyでcore/mysqlを同じ12.11.0へ一時解決した。
候補の[core POM](https://github.com/flyway/flyway/blob/flyway-12.11.0/flyway-core/pom.xml) /
[parent POM](https://github.com/flyway/flyway/blob/flyway-12.11.0/pom.xml)ではJackson databind/core 3.1.4を使う。
実際のdependency:treeでも `tools.jackson.*:3.1.4` が追加され、共通の
`com.fasterxml.jackson.core:jackson-annotations` はBoot管理の2.19.4に解決された。
parentのJava releaseは17なので、失敗原因をJava version不足としない。

```bash
./mvnw dependency:tree -Dflyway.version=12.11.0 '-Dincludes=org.flywaydb:*,tools.jackson.core:*,com.fasterxml.jackson.core:*'
./mvnw test-compile failsafe:integration-test failsafe:verify -Dflyway.version=12.11.0 -Dit.test=EmptyDatabaseMigrationIT,ProbeHealthIT
```

使い捨てMariaDB 12.2.2での候補試験は **18件、Failures 2 / Errors 16 / Skipped 0、BUILD FAILURE**。
Flyway bean生成時に `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs` となり、
fresh migration / prod probeのcontextを起動できなかった。
この失敗試験を採用版の検証成功として数えない。候補の警告消失も実起動で確認できたとは主張しない。
実験後の通常verifyにはversion overrideを渡さず、Boot管理版を検証する。

| 選択肢 | 結果 / 不採用理由 |
| --- | --- |
| A: Flyway patch / 11系更新 | 調査した11.20.3でも上限11.7であり、12.2警告の解消にならない。警告のためだけに依存変更しない |
| Aの拡張: 12.11.0へ単独更新 | 上限12.3だがBoot BOM外のmajor更新。実起動に失敗。Jackson等も合わせて更新する互換性検証は別の依存更新作業とする |
| B: 公式表のDB versionへ変更 | 10.11等への変更は現在12.2の既存volumeではdowngradeになる。12.3.2へ上げても現行Flywayの上限11.2を超える。DBだけのtag差し替えを解決策にしない |
| C: 現行維持 + 判断固定 | **採用**。現行の実DB回帰を維持し、未検証境界を明示。warning suppression、無関係な依存更新、schema / image変更なし |

MariaDBは[major version間のdowngradeを前提にしていない](https://mariadb.com/docs/server/server-management/install-and-upgrade-mariadb/upgrading/platform-specific-upgrade-guides/upgrading-on-linux/upgrading-between-major-mariadb-versions)。
旧imageで既存volumeを開かない。DB方針を再検討する場合は承認された別作業で、新規volumeへの
logical restore / データ・索引・制約・schema history検証 / rollback計画を用意する。

## 現行構成の品質ゲートと残余リスク

通常の `./mvnw verify`（Java 17、version overrideなし）は既存1673件以上、F/E/S=0を必須とする。
基準はSurefire 948 / Failsafe 725。Terraformはmock providerで27件、fmt/init（backendなし・lockfile readonly）/validateも実行する。
採用版の実件数、warningの比較、GitHub Java/Terraform CI runはIssue #69のPR本文へ記録する。
調査後のローカル実検証は948 + 725 = **1673件、F/E/S=0、BUILD SUCCESS**（11:32）、Terraform mock27件成功。
Flyway warningは95件のまま（基準比0）。JVM CDS warning 2件とDB起動時のTLS時刻リトライ1回も記録し、テストは全件完了した。

- `EmptyDatabaseMigrationIT`: 空DBにV1〜V6、Hibernate `validate`、pending=0、再起動時migration実行0。
  `version / description / type / script / checksum / success / installed_on` のhistoryと保存行が再起動前後で同一。
  未管理DBの自動baseline拒否、欠落migration / schema driftの起動拒否も維持する。
- 既存の各migration IT: legacy schemaからの段階適用、旧SQL hash / history、索引・制約・snapshotの維持。
  V1〜V6のSQLは変更しない。git差分なしと調査前後の同一checkoutでのSHA-256一致を確認する。
  改行差があるworking copyのSHA-256と、Flywayが計算するmigration checksumを混同しない。
- `BackupRestoreIT`: 実backup/restore scriptで使い捨てDBへ復元し、schema/data/history/checksum、
  Flyway再適用なし、Hibernate validate、失敗時の終了を検証する。本番DBや既存volumeは使わない。
- 認証/JWT、注文状態・idempotency・Transaction、在庫、売上、CSV、ページング、
  prod readiness/liveness、deploy/rollback契約を既存のfull verifyで回帰確認する。

実テストで確認できるのは本リポジトリのschema / migration / APIと試験したDB imageの範囲。
上流の12.2未検証警告、将来のSQL / driver / server patchの差、全production data形状の差は残る。
MariaDB DDLは完全にtransactionalではなく、app image rollbackはDB rollbackではない。
配備前のbackup・使い捨てrestore drill・旧app/schema互換性確認は引き続き必要で、Deployはdisabledのまま。

フォローアップはBoot側の管理versionとFlywayのMariaDB境界を再確認し、候補のJackson共通annotation等を含む
依存ツリー・Java 17起動を先に検証する。成立した候補だけを別PRでfresh migration、保存済みV1〜V6 history、
backup/restore、probe、全業務回帰とCIへ進める。将来の更新で `repair` / 再baseline / checksum書換を回避策にしない。

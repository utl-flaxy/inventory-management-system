# MariaDB logical backup / restore（Issue #57）

対象はMariaDB 12.2のapplication DBとFlyway history。Bash 4+、Docker CLI、gzip、GNU coreutils
（sha256sum/mktemp/chmod/mv）があるPOSIX hostで実行する。production DBへの実行は運用者の手動作業であり、
deploy/rollback/CIから本番restoreを実行しない。CIの実復元先はTestcontainersのdisposable DBだけ。

## Backup

```bash
bash scripts/db/backup.sh
# source/outputを明示する場合（非secretの識別子/pathだけ）:
DB_CONTAINER=inventory-db BACKUP_ROOT="$HOME/inventory-management-system-backups" bash scripts/db/backup.sh
```

source DB名はcontainer内のMARIADB_DATABASEから取得する。identifierは先頭英字＋英数字/underscore、
MariaDBの上限64文字まで。system DBは拒否する。root credentialはcontainer内でMARIADB_ROOT_PASSWORDから
MYSQL_PWDへ渡し、socketで接続する。host argvへ実passwordを展開せず、全container environmentも取得しない。

mariadb-dumpのsingle-transaction/quick/skip-lock-tablesはInnoDBのconsistent snapshotを取得する。
通常のDMLを止める必要はないが、**backup中にFlyway migrate、ALTER、CREATE、DROP、RENAME、TRUNCATE等のDDLを行わない**。
schema変更・配備の運用枠と排他する。script自身はsourceに書き込み/DDL/LOCK TABLESを実行しない。
非InnoDB table、stored routine/eventがあるsourceは不完全なbackupを黙って生成せず拒否する。
現行V1〜V6は全table InnoDBでroutine/eventなし。triggerは含める。
将来routine/eventや別engineを追加する場合は、backup方式とactual restore drillを同じ変更で見直す。
根拠: [MariaDB公式mariadb-dump](https://mariadb.com/docs/server/clients-and-utilities/backup-restore-and-import-clients/mariadb-dump)。

default outputはrepo外の`$HOME/inventory-management-system-backups`。1 backupは以下のbundle。

```text
<UTC YYYYMMDDTHHMMSSZ>_<source DB>_<unique suffix>/
  dump.sql.gz
  dump.sha256
  manifest.properties
```

root/bundleは0700、3 artifactは0600。gzip -nでheaderのfilename/timestampを省く。
manifestはformat_version=1、source_database、created_at_utc、mariadb_server_version、dump_tool_versionの5項目だけ。
dumpはDBを単独指定し、CREATE DATABASE/USEを埋め込まず、schema/data/Flyway historyを含める。
同一rootのprivate `.partial.*`にdump→gzip検査→SHA-256→manifestを完成させ、最後だけdirectory renameで公開する。
途中失敗はpartialを削除し、completed bundleを残さない。成功stdoutはbundle pathだけを含むsummary。

**dumpにはusername、bcrypt hash、注文、冪等性key/fingerprint、audit historyが含まれる機密artifact**。
Gitへ追加しない。catやCI artifact uploadをしない。未暗号化backupを公開/shared storageへ置かず、
アクセス制御された**storage側暗号化を必須**とする。転送/保存先でもbundle一式とchecksumを保持する。
encryption実装、offsite upload、retention/scheduling、binlog/PITR、physical backup、system user/grants backupは今回の対象外。

## Restore drill / incident runbook

1. **incident判定・write freeze**: 復元点/データ損失範囲を承認し、appと全external writerを停止する。
   productionでは`inventory-app`を明示的にstopし、restart policyや監視が再開させないことを確認する。
   scriptのapp-running guardだけでは外部接続や途中の再起動を防げない。復元中のwrite freezeは運用者が維持する。
2. **target確認**: container ID/name・volume・DB名・MariaDB versionを確認する。まず別volumeのdisposable DBでdrillする。
   対象DBは既存の空DBを用意し、Compose相当の専用app user/権限も事前に初期化する。
   backupはmysql/system DBやuser/grantsを復元しない。production volumeを削除しない。
3. **trusted bundleとchecksum**: 明示したbundleのprovenance、permissions、manifest DB名を確認する。
   `cd <bundle> && sha256sum --check dump.sha256`で破損を確認する。SHA-256は破損検知であり真正性の証明ではない。
   SQLをrootで実行するため、trusted/private storage由来のbundleだけを使用する。暗黙のlatest選択はしない。
4. **restore**: sourceとtarget DB名は完全一致が必須（暗黙rename/別名restoreは非対応）。

   ```bash
   bash scripts/db/restore.sh --bundle /private/backups/<bundle> --container <disposable-container> --database asset_management
   # 非空のdisposable targetを意図的に置換するdrill:
   bash scripts/db/restore.sh --bundle /private/backups/<bundle> --container <disposable-container> --database asset_management --replace --confirm-db asset_management
   ```

   scriptはcompressed artifactをprivate tempへcopyし、そのbytesのSHA-256/gzip/strict manifestを検証してからDBへ接続する。
   plaintext dumpは一時展開せずstream import。tempにはcompressed backup相当の空き容量が必要。
   defaultはtable/view/routine/eventが0のDBだけ。--replaceはexact --confirm-dbが必要で、対象DBだけDROP/CREATEする。
   system DB・DB名不一致・未知manifest・symlink artifactは拒否。production名はcontainer ID経由でも識別し、
   inventory-appがrunningなら拒否する（空DBも含む）。Docker volume削除やDB container再作成は行わない。
   import失敗や接続確認失敗はnon-zeroで、成功扱いしない。restoreのDDL/importは全体をatomic rollbackできず、
   失敗したtargetは部分復元の可能性がある。write freezeを維持し、trusted bundleでdrill/再実行方針を確認する。
5. **Flyway validation**: backup時と対応するGit revision/migration setでV1〜V6 historyのversion/description/type/checksum/successを照合する。
   actual Flyway validateとpending=0を確認する。drillの実行例は下記BackupRestoreIT。
   本番で別revisionのpending migrationを無確認に適用しない。
6. **Hibernate/app startup**: 専用app userでddl-auto=validateを実行し、Flyway validationも成功することを確認する。
   Spring Boot通常起動はvalidate-on-migrate後にmigrateを実行するため、対応revisionとpending=0の確認を先に行う。
   rootへの切替、ddl-auto=update、Flyway baseline/cleanでエラーを隠さない。
7. **business sanity**: users/products/orders/order_items/inventory_movementsのtable set/row count、stock、BIGINT金額、
   注文状態、snapshot、在庫delta/before/after/reason/source、両idempotency metadataを照合する。
   機密値はprivate比較だけに使い、hash/key/fingerprintや行全体をlog/チケットへ貼らない。
8. **readiness**: `/readyz`がHTTP200・status UP、`/livez`も正常であることを確認する。healthだけを業務整合性の証明としない。
9. **write再開**: 検証と復元点の承認後にapp/external writerを再開する。復元中の問題はwriteを再開せず調査する。

本番restoreは不可逆なdestructive operationになり得る。backup/restoreをdeploy成功/失敗処理へ自動挿入しない。
Deploy workflowはdisabledのまま。このIssueではAWS/EC2/production DBへ接続・操作していない。

## Repository restore verification

```bash
./mvnw -Dtest=BackupRestoreScriptTest -Dit.test=BackupRestoreIT verify
./mvnw verify
```

Java17 / Dockerが必要。WindowsはGit Bashとmvnw.cmdを使用する。
mock shellはsecretのhost argv/出力、失敗cleanup、checksum-first、manifest、identifier、非空DB、exact confirmation、
production app guard、import失敗を検証。実MariaDB12.2のsourceはactual Flyway V1〜V6＋専用userでseedし、
実backup.sh/restore.shを呼んで別containerへ復元する。全行・history/checksum、BIGINT/4状態/在庫履歴/冪等性metadataを照合し、
改ざん・非空・誤確認拒否時のtarget不変とdisposable replace成功、Flyway validate/pending0、Hibernate validateを確認する。
POSIX CIではroot/bundle0700・artifact0600を実filesystemで検証する。Windowsではscript契約を確認し、POSIX mode assertionだけ分岐する。
test削除/skipは追加しない。機密値はassertion failureに表示しない。

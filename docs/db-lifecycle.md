# DB待機予算と停止手順（AUD-11 / Issue #116）

## 対象と根拠

main `8599aea6cdfad48b97a79600eb2c5a290daace3f` のprod実起動で、pool10、接続取得30000ms、validation5000ms、
sessionのInnoDB row lock待ち50秒、JpaTransactionManagerの既定timeout=-1を確認した。
poolを占有したHTTP要求は7秒後も待機し、商品を別Txでロックした注文は8秒後も待機した。
解放後は200となるため、既存の整合性検証だけでは待機時間の上限を証明できない。

同時注文の既存InventoryConcurrencyMariaDbITは、同期開始用の専用pool110/取得30秒と複数の実顧客で、
在庫・version・snapshot・履歴・冪等性・rollback・昇順ロックの整合性を検証する。
このfixtureを変更せず、prodの既定pool10での枯渇・timeoutはDatabaseTimeoutITで別途検証する。

## prodの既定値

| 境界 | 設定 | 意味 |
|---|---|---|
| pool容量 | maximum-pool-size=10 | 既存容量を維持。負荷測定なしに拡大しない |
| 生存確認 | validation-timeout=1000ms | pool接続の検証。取得待ちより短い |
| 接続取得 | connection-timeout=5000ms | poolからの借用待ち。SQL実行やHTTP全体の期限ではない |
| driver接続 | connectTimeout=3000ms | 新規接続の確立。Hikariの借用待ちとは別 |
| driver読取り | socketTimeout=30000ms | 借用済み接続でDBが応答しなくなったときの有限のI/O待ち |
| 行ロック | connection-init-sqlでsession innodb_lock_wait_timeout=5 | 各InnoDB row lock待ち。全statement/metadata lockの一律期限ではない |
| Tx | spring.transaction.default-timeout=10s | Spring管理の新規Txと対応するJDBC query timeout。全Java処理を強制停止するtimerではない |
| HTTP drain | server.shutdown=graceful、timeout-per-shutdown-phase=30s | SIGTERM時に新規受付を止め、進行中HTTPを待つ |
| Docker停止 | app.stop_grace_period=35s | HTTP drainに加え5秒の終了処理余裕。DB側の停止設定は変更しない |

設定はapplication-prod.propertiesに限定する。個別テストの短い障害再現用設定や、100worker専用設定を緩めない。
環境変数やJDBC URLによる上書きは有効値を変更し得るため、運用者は上書きも含めて確認する。
socketTimeoutは通常のSQL期限として使わず、Tx/query期限後もserverが応答できない場合の最終的なI/O境界とする。
接続取得、認証のuser lookup、業務Tx、response生成は別段階であり、値をHTTPのSLAとして足し引きしない。
CPU処理、OS停止、VM時計、複数ライフサイクルphase、ネットワーク条件はこの数値だけでは制御できない。

## エラーと整合性

- pool取得や既知のquery timeoutは503 SERVICE_UNAVAILABLE。JWTを無効とみなさない。
- row lock timeoutは409 CONCURRENT_UPDATE。MariaDB単体では失敗statementだけがrollbackされる場合があるが、
  業務例外を握りつぶさずSpringの外側Tx全体をrollbackする。商品・注文・明細・履歴、user lockとkey状態を検証する。
- 接続断のSQL例外と、commit/rollback中に発生する別の例外を区別する。rollback時に原因が分類不能となった場合は500 SYSTEM_ERRORを維持。
  全DataAccessExceptionや全5xxを503へ変換しない。接続が切れたときの書込み結果はクライアント側では確定できないことがある。
- 401/403/429、接続元不明503、入力400、業務409、6フィールドProblemDetailと安全な固定detailは変更しない。
- 復旧後はreadinessと結果を確認し、対応APIでは同じIdempotency-Key/内容でretryする。自動的に新keyで注文を再送しない。
- prodではFlywayExecutorのURL付きINFO bannerだけをWARN閾値へ絞る。migrationは実行し、既存のMariaDB互換性WARNを維持する。

## 使い捨て環境による証明

DatabaseTimeoutITは既存DB/volumeを使わず、実HTTP・実JWT・MariaDBで次を検証する。
pool全接続の占有と503/解放/同JWT復旧、別接続・別Txのproduct row lockと409/全行不変/同key retry、
更新後の長いSQLに対するquery timeoutと全Tx rollback、実接続KILL時の未分類rollback失敗500とDB不変、
借用済み接続でDBをpauseしたときのdriver読取り期限とunpause後の復旧。pauseは必ずfinallyで解除する。
長いSQLや診断用HTTP入口はtest sourcesだけにあり、production image/APIには含めない。

Docker動的検証では、実JRE/non-root/read-onlyのappへSIGTERMを送り、Tomcatのdrain開始後に新しいHTTPが処理されないこと、
ロック解放後に進行中注文が200で一度だけcommitされること、ロックを保持した場合は409/全体rollbackとなることを確認する。
pool shutdown完了、app用DB接続0、DB lock wait0、exit0/143・OOMなし・SIGKILLなし、再起動後の同JWT/同keyを検証する。
既存のapp-only切替/失敗時rollback/DB container起動時刻・data不変の検証も維持する。

変更前の使い捨てイメージでは、進行中のrow lock待ちに対してexit137とHTTP切断を観測した。DBはrollbackされ、
同key再送は一度だけ成功した。ローカルのstop command実測は3.422秒であり、文書上のCompose既定値10秒と混同しない。
最終値・実行したHEAD・CI URL・コンテナ起動時の環境エラーはPRへ記録する。

## 運用・rollback・非保証

本番のinstance容量、SLA、failover、負荷下の待ち時間や復旧性は未検証。pool10/5秒/10秒はこの単一JVMの保守的な初期値で、
本番保証ではない。運用者は実トポロジー、DB接続上限、transaction/statementの分布とエラー率、CPU/メモリ、
drain時間を測定してから別レビューで調整する。requestIdとsafe eventを使い、認証情報・JWT・DB URLやSQL本文をログへ出さない。
停止猶予を超えれば切断は起こり得る。gracefulは接続済みclientの応答受領や全処理の完了を無条件には保証しない。
外部service manager/host停止にも35秒以上の猶予が必要。複数phaseや異常な終了処理では更に必要な場合がある。

schema/migration/API/lock順序は変更しない。rollbackは既存の旧app imageと保存済みComposeへ戻す。
DBを停止/初期化/巻き戻さない。旧構成では長い待機と短い停止猶予のリスクも戻るため、暫定対応として扱う。
AWS/Deploy/本番DB/既存volumeは検証対象外。

根拠: [Hikari設定](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)、
[借用済み接続とdriver timeout](https://github.com/brettwooldridge/HikariCP/wiki/Rapid-Recovery)、
[MariaDB Connector/J](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j)、
[InnoDB待機](https://mariadb.com/docs/server/server-usage/storage-engines/innodb/innodb-system-variables)、
[Boot graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)、
[Compose停止猶予](https://docs.docker.com/reference/compose-file/services/#stop_grace_period)。

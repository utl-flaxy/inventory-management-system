# 在庫更新の並行制御（Issue #12）

注文作成、cancel、商品PUTが在庫を書き換える。商品POSTは新規行を作成する。
注文時の在庫不足は従来どおり400、禁止状態遷移・version不一致・ロック競合は409を返す。
同じリクエストの自動再試行は行わない。

## 採用方式とTransaction

注文作成はProductをPESSIMISTIC_WRITEでロックし、価格確認・金額計算・在庫減算・Order/OrderItem保存を
同じTransactionで実行する。価格と在庫を同じ行で保護でき、既存のEntity不変条件も維持できるため、
条件付きUPDATEではなく悲観ロックを選んだ。待機した注文は先行Transactionの確定後の在庫・価格を使う。

ship/complete/cancelは最初にOrderをPESSIMISTIC_WRITEでロックし、最新状態でIssue #11の遷移規則を検証する。
cancelはその後、明細に含まれるProductを重複除去し、ID昇順でロックする。ロック下でrefreshして、
Persistence Contextに先に読み込まれた古い在庫も使わない。
OrderのcascadeからREFRESHだけを除き、Orderのrefreshが関連商品を暗黙にロックすることを防いでいる。
PERSIST/MERGE/REMOVE/DETACHのcascadeは維持する。

全商品の在庫復元をMath.addExactで事前計算し、成功時だけ在庫と状態を保存する。
同時cancelは1件だけ成功し、後続はCANCELLEDを読み取って409となる。cancelとshipも一方だけ成功する。
保存途中の失敗は在庫・状態・明細を含むTransaction全体でrollbackする。

ロック順序は既存Order→ProductのID昇順。注文作成はProductをロックしてから新規OrderをINSERTするため、
他の処理がロック済みの既存Orderを後から取得する逆順経路は作らない。商品PUTはProductだけを更新する。

これらの更新ServiceはREAD_COMMITTEDを指定する。MariaDB 12.2.2のsnapshot isolationでは、
REPEATABLE_READの古いread viewから後でロック・更新するとError 1020になるため、
更新時は古いread viewを保持せず、明示的な行ロックとversionで整合性を保証する。
Serviceは通常のHTTP呼び出しでTransactionを開始する。既存Transactionから呼ぶ場合は、
呼び出し元もREAD_COMMITTEDを使用する（REQUIREDで参加するTransactionのisolationは変更できない）。

## 商品更新とAPI互換性

Productに@Versionを追加し、注文・cancel・商品PUTによる実更新でversionが進む。
商品一覧・検索・詳細・作成・更新レスポンスにversionを含める。

商品PUTは取得したversionを必須で送信する:

```http
PUT /products/1
Content-Type: application/json

{"name":"apple","price":100,"stock":20,"version":0}
```

version未指定/null/負数は400、現在versionと不一致なら409。
同じTransactionで読み取り後に別処理が変更した場合も@VersionでUPDATEを拒否し、409を返す。
古い管理画面が注文の減算後にstockを絶対値で上書きする経路も、client versionの検証で拒否する。
競合時は再取得して変更内容を確認し、最新versionで送信する。

商品POSTの入力は変更しない。PUTのname/price/stockは既存形式を維持し、versionだけを追加する。
在庫調整APIや履歴の全面設計は行わず、今回はversion付きPUTでサイレント上書きを防ぐ。

既存DB用の限定的なDDLは[issue-12-product-version.sql](../db/legacy/issue-12-product-version.sql)。
既存行のversionを0で初期化し、stock・priceを変更しない。適用中に旧アプリがversionなしで更新しないよう、
このSQLは旧DB向けの履歴アーカイブ。現行schemaのversion列はFlyway V1が管理し、Hibernateはvalidateのみ行う。
既存DBは[Flyway移行手順](../db/README.md)に従い、旧SQLだけで移行済みと判断しない。本番DBへの適用は行わない。

## 実MariaDB回帰テスト

Dockerが必要。Java 17で `./mvnw clean verify` を実行するとMariaDB 12.2.2のTestcontainersも必ず実行する。
Docker非稼働時はskipせず失敗する。CIの同じコマンドでも実DBテストを行う。

各workerはREQUIRES_NEW/READ_COMMITTEDで独立Transactionを持つ。
最大100 worker全員が異なるMariaDB接続を取得してからCountDownLatchで同時開始する。
Hikariのテスト用poolは110、MariaDBのテスト用max-connectionsは160。
sleepは使わず、同期ポイント・Transaction/test/Futureのtimeout・全Future回収・Executor終了を明示する。

- stock=1/10/100へ20/20/100同時注文し、成功1/10/100とstock=0、Order/OrderItem件数、合計数量、versionを検証。
- 同じ注文の20同時cancel（互換APIを含む）は1成功・19状態競合、復元1回。
- cancel対shipを5回検証し、cancel対completeも検証。
- 商品PUT対注文、古いclient version、Transaction内の古いProduct、価格読み取り後の管理更新を検証。
- 先にProductを読み込んだcancel対注文、明細順が逆の複数商品注文の同時cancelを検証。
- 同じversionでの20同時商品更新は1成功・19競合。
- OrderのrefreshがProductを暗黙ロックしないことを、商品更新との同期テストで検証。
- 既存DBへのversion列追加のbackfillと再適用を検証。

Issue #5のself cancelは所有者条件とPESSIMISTIC_WRITEを同じqueryに含め、同じ復元処理を使う。
owner cancel対ADMIN shipと他人のcancel拒否も実MariaDBで検証する。
認可の詳細は[注文APIの認可](../security/order-authorization.md)を参照。

JWT（#9）、入力Validation・Product/OrderItem/DB不変条件（#10）、状態遷移・cancel rollback（#11）、Order集約（#14）を維持する。
Flyway（#15）、CD全面修正（#17）、
売上集計変更、金額型変更、OAuth2移行は対象外。

# 注文CSV export（Issue #63）

`GET /api/v1/admin/orders/export` はJWTで認証済みのROLE_ADMINだけが利用できます。
USERは403、未認証は401で、既存ProblemDetailを維持します。
成功は200、`Content-Disposition: attachment; filename=orders.csv`、
`Content-Type: text/csv; charset=UTF-8`です。UTF-8、BOMなし、レコード終端はCRLFです。
以前もBOMを追加していないため、今回も追加しません。

列順は `注文ID,商品名,数量,単価,合計金額,ステータス`。1行は1明細で、
注文時の商品名snapshot・単価、数量、`Math.multiplyExact`による明細合計、注文状態を出力します。
金額は円の整数（long）で、現在の商品名/価格へ置き換えません。空DBでもヘッダを出力します。

カンマ・引用符・CR/LFを含むセルはRFC 4180のdouble quoteで囲み、内部のquoteを2個にします。
セル内部の改行・CRLF・日本語・空文字を保持します。数字・enumは型付き値から生成します。

## Spreadsheetでの式対策

商品名の最初の有効文字が `= + - @`（全角の `＝ ＋ － ＠` も含む）の場合、
CSVセルの先頭に **apostrophe + tab (`'\t`)** を付けてquoteします。
危険性の判定時だけUnicode空白・space・control・format文字を読み飛ばします。
先頭部分のtab/CR/LFもtext化します。元の空白や制御文字をCSVから削除しません。
保存済みの商品名・snapshotは一切書き換えません。

quoteだけでは式を防げません。apostropheによるtext化に加え、Excelが再保存時にapostropheを
除いてもtabによるtext化を残す方式です。[OWASPのCSV Injection対策](https://community.owasp.org/attacks/CSV_Injection)
を参考にしています。parserで読むと、このprefixもセル値の一部になります。
すべてのspreadsheetや再編集・再取り込み後の変換に共通する保証はありません。
機械取り込みではこのexport仕様を考慮し、spreadsheetへの取り込み列はtextとして扱ってください。

## DB取得・メモリ・一貫性

CSV専用のscalar projectionだけを取得し、Entity・商品・customer・idempotency metadataを取得しません。
取得順は `(order_items.order_id ASC, order_items.id ASC)`、cursorもこの一意な組です。
SQLは以下（DTO向け列aliasは省略）です。先に500明細を確定する派生テーブルを使い、
注文JOINのoptimizer選択による残り全行の繰り返しsortを避けます。

```sql
SELECT i.order_id, i.id, i.product_name_snapshot, i.quantity, i.price, o.status
FROM (
  SELECT id, order_id, product_name_snapshot, quantity, price
  FROM order_items
  WHERE order_id >= :orderId
    AND (order_id > :orderId OR (order_id = :orderId AND id > :itemId))
  ORDER BY order_id ASC, id ASC
  LIMIT 500
) i JOIN orders o ON o.id = i.order_id
ORDER BY i.order_id ASC, i.id ASC;
```

native projection queryのlimitは常に500で、OFFSETもcount queryもありません。
全件List、全量CSV buffer、collection fetch join、明細ごとのqueryはありません。
最大保持単位は500個のDTO（商品名はDB VARCHAR(255)）と固定8192文字のwriter buffer、
UTF-8 encoderの固定byte bufferです。前バッチを解放してから次を読みます。
500行を送信・flushしてから次のqueryを実行します。
query数は空DBで1、通常はceil(明細行数/500)、ちょうど500の倍数では終端確認の空queryが1回追加されます。

Flyway V1の `idx_order_items_order(order_id)` を使用できます。InnoDB secondary indexには
primary keyのidが含まれ、この取得順を支えます。V2〜V6も保持し、migrationは追加しません。
MariaDB ITで実索引・EXPLAIN（内側は索引順、外側sortは最大500行）・複数バッチ・
statement件数・managed Entity数を確認します。

export全体は、同じrequest thread上の**read-only / REPEATABLE READ transaction**内で実行します。
初回SELECTで確定したInnoDB snapshotをすべてのバッチで使い、後からcommitされた新規注文や
状態変更はそのexportへ混ぜません。バッチを跨ぐ同一注文でも同じstatusを出力します。
snapshotより後の削除もsnapshot上の行を出力します。DDL/migrationとの同時実行は保証対象外です。

## DB snapshotとHTTP送信の分離（Issue #156）

以前はこのTransaction内で各バッチをHTTPへflushしていました。prodの業務Tx期限
`spring.transaction.default-timeout=10s` はexportにも適用されるため、clientが遅い・データが多いと
送信開始後の次バッチ取得で `TransactionTimedOutException` となり、HTTP 200のCSVが途中で切れました。
所要時間がclient速度に依存するため、再取得でも同じ位置付近で失敗し得ました。
誤っていた前提は「DB Txの長さはDB処理量で決まる」ことで、実際はネットワーク送信時間を含んでいました。

現在は2段階です。

1. **snapshot確定**: 上記のread-only / REPEATABLE READ Tx（既存の業務Tx期限10秒、変更なし）で、
   全行を上限付きの一時file（`java.io.tmpdir`、`order-csv-*.csv`、POSIXではowner-only）へ書きます。
   この段階ではHTTP headerを一切送信しません。
2. **送信**: Tx終了・connection返却後に `Content-Length` 付きでfileを送信します。
   遅いclientはservlet threadだけを保持し、DB connection・Transaction・MVCC snapshotを保持しません。

| 項目 | 既定値 | 設定 |
|---|---|---|
| spool上限 | 16 MiB | `app.order-csv.max-bytes`（64 KiB以上） |
| 同時export | 1 | `app.order-csv.max-concurrent`（1〜4） |
| 合計spool | 24 MiB以下 | `max-bytes × max-concurrent`。Composeの `/tmp` tmpfs 32 MiB（メモリ消費）未満に固定 |
| snapshot取得時間 | 10秒 | 既存の業務Tx期限をそのまま使い、CSV専用に延長しない |

同時export数のpermitはspool作成からHTTP送信完了まで保持し、成功・DB失敗・上限超過・
client切断の全経路でfile削除とpermit解放を行います。heapはwriter/転送用の固定bufferだけです。
Spring Security context、MDC/requestId、Transactionを別threadへコピーせず、
StreamingResponseBodyのworkerやOSIVへ依存しません。`prepare` は `Propagation.NEVER` で、
外側Txへ参加して送信中までsnapshotを延ばす誤用を拒否します。
read-only接続・REPEATABLE READ・managed Entity 0件・送信中のactive connection 0件は実MariaDB ITで検証します。

## 失敗時

DB取得・上限・混雑の失敗はすべてCSV header送信前に起きるため、ProblemDetailで返します。
部分CSVは返しません。

| 状況 | HTTP | code | 再試行 |
|---|---|---|---|
| 他のexportがspool中/送信中 | 503 | `ORDER_CSV_EXPORT_BUSY` | DB queryなし。先行export終了後の再試行で成功し得る |
| spoolがmax-bytes超過、またはsnapshot取得が業務Tx期限超過 | 503 | `ORDER_CSV_EXPORT_LIMIT_EXCEEDED` | データ量が上限を超える限り同じ結果。運用上限として扱う |
| その他のDB/IO失敗（どのバッチでも） | 500 | `SYSTEM_ERROR` | 既存契約どおり |

header送信後の失敗（client切断、spool読取失敗）はHTTP statusを変更できません。CSVにJSON/error行を追加せず、
例外をcontainerへ伝えて接続を中断します。`Content-Length` が宣言済みなので、clientは長さ不足で途中切断を検知できます。
この段階ではDB処理がないため、client速度によるTx期限超過は起きません。

**運用上残る上限:** 16 MiB（1明細100〜数百byteとして数万〜十数万明細）を超える全件exportはこのAPIでは出力できません。
全件の大量出力が必要になった場合は、期間指定などの絞り込みAPIか非同期job化を別Issueで設計します。
また、送信中のpermitは遅いclientが送信完了まで保持するため、その間の他ADMINのexportは503になります
（完全に停止したclientの切断時期はTomcatの接続/書込みtimeout設定に依存し、本変更では本番値を検証していません。
極端に遅いが進行し続けるclientはpermitを保持し続け得ます。対象はADMIN認証済みrequestに限られます）。

診断は既存 `system.unhandled_error` の型・frameだけです。containerへ伝える例外も固定messageで、
raw cause/SQL message/CSV値/tokenは保持しません。実prod consoleを成功・初回DB失敗・途中DB失敗で監査します。
注文・価格・在庫・status・冪等性・売上処理には変更を加えません。

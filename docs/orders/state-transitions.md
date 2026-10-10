# 注文状態遷移（Issue #11）

新しい注文はPENDINGで始まる。状態遷移はOrderStatusのルールとOrderの業務メソッドで検証する。
同じ状態への更新と終端状態からの変更は許可しない。PAIDは追加しない。

| 現在の状態 | PENDING | SHIPPED | COMPLETED | CANCELLED |
| --- | --- | --- | --- | --- |
| PENDING | 禁止 | ship | 禁止 | cancel |
| SHIPPED | 禁止 | 禁止 | complete | 禁止 |
| COMPLETED | 禁止 | 禁止 | 禁止 | 禁止 |
| CANCELLED | 禁止 | 禁止 | 禁止 | 禁止 |

| API | 動作 |
| --- | --- |
| POST /orders/{id}/ship | PENDINGから発送済みへ変更。在庫は変更しない |
| POST /orders/{id}/complete | SHIPPEDから完了へ変更。在庫は変更しない |
| POST /orders/{id}/cancel | PENDINGからキャンセルへ変更し、在庫を復元する |
| PATCH /orders/{id}/cancel | 既存APIの互換入口。POSTのcancelと同じ処理 |
| PATCH /orders/{id}/status | 既存APIの互換入口。SHIPPED/COMPLETED/CANCELLEDをそれぞれ業務コマンドへ振り分ける |

status APIのCANCELLED指定もcancel処理を通り、直接状態を書き換えない。
PENDINGへの変更は禁止。statusが未指定・null、または未知の値なら400。
禁止された遷移・二重cancelは409、存在しない注文は404を返す。
注文詳細GETの不存在も同じ404とする。他のエラー形式は変更しない。

cancelは全明細の復元後在庫をMath.addExactで事前計算する。同一商品が複数明細にあれば累計し、
上限超過・不正な明細数量は409で、変更前に拒否する。計算が成功してから在庫と状態を同じTransactionで保存する。
途中で永続化に失敗した場合はTransaction全体をrollbackする。
直列の再cancelでは状態検証により再度の在庫復元を拒否する。

回帰テストは全16通りの遷移、各APIの400/404/409、stock=10→数量5の注文→5→cancel→10→再cancel失敗→10、
在庫上限、実SQL発行後の保存失敗とrollbackを検証する。Java 17で `./mvnw clean test` を実行する。

所有者・認可（#5）、並行実行時の排他・競合対策（#12）、Order集約再設計（#14）、Flyway（#15）、
CD修正（#17）は対象外。異なるTransactionが同時に変更する場合の競合は#12で扱い、今回ロックは追加しない。
READMEのPAID表記と、キャンセル済み注文を含む既存の売上集計仕様も変更しない。

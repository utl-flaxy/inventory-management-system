# 注文状態遷移（Issue #11）

新しい注文はPENDINGで始まる。状態遷移はOrderStatusのルールとOrderの業務メソッドで検証する。
同じ状態への更新と終端状態からの変更は許可しない。PAIDは追加しない。

Issue #27ではSHIPPED→COMPLETED時だけ、ServiceのUTC Clockから取得したInstantを
`Order.complete(Instant)`へ渡し、completedAtを一度設定する（DB DATETIME(6)に合わせてmicrosecond精度）。
既存のOrder PESSIMISTIC_WRITEロック下で状態確認・日時/状態設定・saveを同じTransactionで行う。
PENDING/SHIPPED/CANCELLEDのcompletedAtはnull。新規/更新のOrderは状態と日時の不変条件をJPA callbackでも検証する。
legacy COMPLETED/NULLは読み取りを許可し、売上から除外する。読み取り時の補完やDB CHECKは追加しない。
二重completeは409で、最初の日時も売上も変化しない。在庫・cancelの復元処理は変更しない。

| 現在の状態 | PENDING | SHIPPED | COMPLETED | CANCELLED |
| --- | --- | --- | --- | --- |
| PENDING | 禁止 | ship | 禁止 | cancel |
| SHIPPED | 禁止 | 禁止 | complete | 禁止 |
| COMPLETED | 禁止 | 禁止 | 禁止 | 禁止 |
| CANCELLED | 禁止 | 禁止 | 禁止 | 禁止 |

| API | 動作 |
| --- | --- |
| POST /api/v1/me/orders/{id}/cancel | 所有者のPENDING注文をキャンセルし、在庫を復元する |
| POST /api/v1/admin/orders/{id}/ship | ADMIN: PENDINGから発送済みへ変更。在庫は変更しない |
| POST /api/v1/admin/orders/{id}/complete | ADMIN: SHIPPEDから完了へ変更。在庫は変更しない |
| POST /api/v1/admin/orders/{id}/cancel | ADMIN: PENDINGからキャンセルへ変更し、在庫を復元する |
| PATCH /api/v1/admin/orders/{id}/cancel | ADMIN: POSTのcancelと同じ処理 |
| PATCH /api/v1/admin/orders/{id}/status | ADMIN: SHIPPED/COMPLETED/CANCELLEDをそれぞれ業務コマンドへ振り分ける |

status APIのCANCELLED指定もcancel処理を通り、直接状態を書き換えない。
PENDINGへの変更は禁止。statusが未指定・null、または未知の値なら400。
禁止された遷移・二重cancelは409、存在しない注文は404を返す。
注文詳細GETの不存在も同じ404とする。他のエラー形式は変更しない。

cancelは全明細の復元後在庫をMath.addExactで事前計算する。同一商品が複数明細にあれば累計し、
上限超過・不正な明細数量は409で、変更前に拒否する。計算が成功してから在庫と状態を同じTransactionで保存する。
途中で永続化に失敗した場合はTransaction全体をrollbackする。
直列の再cancelでは状態検証により再度の在庫復元を拒否する。

回帰テストは全16通りの遷移、各APIの400/404/409、stock=10→数量5の注文→5→cancel→10→再cancel失敗→10、
在庫上限、実SQL発行後の保存失敗とrollbackを検証する。Java 17とDockerで `./mvnw clean verify` を実行する。

並行実行時のロック・version・Transactionの扱いは[在庫更新の並行制御（Issue #12）](inventory-concurrency.md)を参照。
所有者・認可と旧URLの廃止は[注文APIの認可（Issue #5）](../security/order-authorization.md)、
集約・複数明細は[Order集約（Issue #14）](order-aggregate.md)を参照。
完了時の売上計上とcompletedAtは[売上仕様（Issue #27）](../sales.md)を参照。

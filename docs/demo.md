# 認証からキャンセルまでの隔離Dockerデモ

Issue #123第二PR。production資格情報を入力せず、実app / 実MariaDBを固有名の使い捨て環境で動かす。
これは公的HTTPSの公開デモではない。通信はhostのIPv4 loopback HTTPであり、Caddy/TLSの別テストとは区別する。

## 前提と実行

Git、Docker daemon（linux/amd64）とCompose v2、Python3.12以上、image/Maven/scannerを取得できるnetworkが必要。
Docker Desktopも利用できる。Javaのhost installはfull verify用で、image buildはdigest固定JDK内で行う。
新規appに1 GiB、DBに512 MiB、build等の余裕が必要。1 GiB hostの本番運用可能性を示す試験ではない。

repository rootで、レビューするcommitをcheckout・commit済みにして実行する。
imageの入力は`git archive HEAD`なので、未コミットのsrc/main修正をimageへ混ぜない。
通常の`docker-compose.yml`は固定名・永続volumeを持つため、このデモで直接upしない。

```bash
python3 scripts/docker/verify_image.py --scan --output target/portfolio-demo
```

Windows PowerShell（`python`がPython3.12以上を指す場合）:

```powershell
python scripts/docker/verify_image.py --scan --output target/portfolio-demo
if ($LASTEXITCODE -ne 0) { throw 'Disposable demo failed; inspect the value-free summary.' }
```

既存のimageを渡す`--image`はbuildを省く別モード。このデモ・CIのclean build証拠には使わない。
`--baseline-image`、`--boot4-image`、`--capacity-probes`も今回の標準手順では指定しない。
Windowsのprivate TEMPからのbind mount拒否を避けるため、接続元確認用の公開Javaソースは
確認用コンテナ内のbounded tmpfsへ書き込む。秘密値を引数へ移す変更ではなく、private env fileの権限は広げない。

## 実際に行う操作と成功条件

scriptはtoken/passwordをメモリ内で扱い、APIの戻り値・DB状態をassertしてから値を含まない`demo_steps`へ記録する。
公開registerはUSERだけ。ADMINは使い捨てDBに限定してusersのSELECT(username,role)/INSERT(username,password,role,auth_uid,token_version)と
flyway_schema_historyのSELECT(version,success,checksum)だけの専用userを作り、B1版offline provisioning CLIで用意する。

| 順番 | 操作 | 必須の観測値 |
|---|---|---|
| 1 | POST `/auth/register`（2ユーザー） | HTTP200、両方ROLE_USER、ADMIN0。role要求で昇格しない |
| 2 | offline ADMIN CLIを独立3プロセスで実行 | ADMIN作成は1件、残りはADMIN_PRESENT、再実行後もhash/role不変 |
| 3 | USER / ADMINでPOST `/auth/login` | 両方HTTP200。tokenは出力しない |
| 4 | 未認証/不正JWT、USERのADMIN操作 | 401 / 401 / 403。通常の公開APIと権限境界を維持 |
| 5 | ADMINがPOST `/products` | price100、stock10、HTTP200 |
| 6 | USERが数量2でPOST `/api/v1/me/orders`、同keyでretry | 同一注文、totalPrice200、stock8。二重減算しない |
| 7 | ADMINがPOST `/api/v1/admin/products/{id}/inventory-adjustments` | delta+3 / RECEIPT、HTTP200、stock11 |
| 8 | USERがPOST `/api/v1/me/orders/{id}/cancel` | CANCELLED、HTTP200、stock13 |
| 9 | ADMINがGET `/api/v1/admin/orders` | HTTP200、同じ注文1件、CANCELLED |

**在庫10→8→11→13**と金額200を実際の応答で検証する。順番に進めるので正常業務フローであり、負荷測定ではない。
Issue #136では9段階を維持した後、B1のlogout-all 204、旧JWT/同JWT再送401、再ログイン200、世代1・監査1行、
別ADMIN有効、同じ注文key再送のDB不変を別の `jwt_revocation` 証跡で確認する。値やJWTは出力しない。
Issue #138では続けてB2のpassword変更204、旧password/JWT/再送401、新password再login200、世代2・PASSWORD_CHANGE監査1行
（LOGOUT_ALLと合わせ計2行）、別ADMIN有効、同じ注文key再送のDB不変を `password_change` 証跡で確認する。
V1〜V7を保持した追加V8が適用され、同一imageのapp-only設定故障復帰後も両旧世代JWT401・新JWT有効・hash/世代/監査不変を検証する。
旧JWTの移行・異binary/過去DBへの復帰制約は[移行runbook](security/jwt-revocation-migration.md)を参照。
さらに登録/loginの独立負荷制限・Retry-After、101明細400とDB不変、headers、prod docs/H2の404も既存scriptが確認する。
JWT未署名・payload/MAC改ざん等6種類の専用検証は[Javaの受入条件](testing.md#jwt否定系の受入条件issue-123-第一pr--aud-23)とfull verifyで行い、Docker scriptだけでその全件を実行したとはしない。

## 隔離と秘密情報

- resourceは毎回nonce付き`inventory76-*`。既存`inventory-db`/`inventory-app`、Docker volume、AWS、実secret、本番DBを使用しない。
- DBデータは新規tmpfs。network/containerは所有labelを確認して自分の作成分だけcleanupし、全体down・volume prune・global cleanupをしない。
- 使い捨てrandom credentialsは一時directoryの0600 file、stdin、メモリで受け渡す。通常終了/例外のfinallyで自身のcontainerと一時directoryを破棄する。強制終了・host crashではcleanupが完了しない可能性がある。
- WindowsのchmodはNTFS ACLの保証ではない。信頼できる個人用host・ユーザー専用TEMPで実行し、共有hostの他ユーザー隔離や安全な消去まで保証しない。
- 同一hostの不正processやDocker管理者への秘匿は保証しない。Pythonのimmutable stringの物理ゼロ化も保証しない。
- stdout/summaryへJWT、password/hash、DB資格情報、username、Idempotency-Keyを出さない。raw応答や解決済みCompose/envをPRへ貼らない。
- デモ経路のtransport peerだけを実測してtrusted proxyへ設定する。本番peerを検証したことにも、一般クライアントのX-Forwarded-Forを無条件に信用してよい根拠にもしない。

## 証跡の確認

成功はprocess exit0かつ`target/portfolio-demo/summary.json`の`result=PASS`。
`demo_steps`の9段階と期待値、`source_revision`、`revision_label`、image ID、`clean_archive_build`、`migrations`、`jwt_revocation`、`rollback`、`image_scan`を確認する。
`source_dirty`はjob workspace全体の状態であり、trueならその事実を残す。imageの入力がclean archiveであることと区別する。
初回buildやオンラインDB取得は失敗し得る。失敗をPASSへ置き換えず、その段階を記録する。

```bash
python3 -c "import json; p=json.load(open('target/portfolio-demo/summary.json')); print(json.dumps({k:p[k] for k in ('source_revision','result','demo_steps','image_scan')},indent=2))"
```

このPRで実行したlocalの値を除去した証跡は[demo JSON](evidence/issue-123-demo.json)へ保存する。
最終PR HEADのCIでは同じscriptをfull verify後に実行し、run URLと`docker-validation-<headSHA>/summary.json`をPR本文へ記録する。
CIがPR merge commitをcheckoutした場合、run headとcheckout SHAを区別してtreeを照合する。
Java/Python/Terraform/Securityの結果と、localデモ・CIデモの結果は別の証跡として示す。

## shutdown・rollbackと保証限界

同じfixture内でin-flight注文のcommitとlock deadline中のSIGTERMを確認する。
新規受付停止、既存要求200/409、全体commit/rollback、pool解放、DB接続/待機0、同key retryを検証する。
さらにappのDB接続先を**container自身のloopback接続不能port**へ変え、readiness失敗後に直前設定へ戻す。
DBのID・起動時刻・注文/在庫・ADMIN状態が不変であることを確認する。

標準手順ではcurrent/previousは**同一image ID**。異なる旧バイナリへ戻す互換性、DBのdown migration、旧鍵再受理、実EC2での復旧を保証しない。
Issue76の旧JDK/root image対照実験は[当時のJSON](audits/issue-76-validation.json)に分け、今回の主張へ流用しない。
このscriptはapp-only切替操作の実証で、production用deploy.shの全経路を実EC2で検証したものではない。

Trivyはapp runtime imageだけを監査する。OSVのMaven依存監査と別で、OS49等のfindingが残ってもaudit処理は成功し得る。
tool/DB取得/JSON解析エラーは失敗。finding非0の監査成功を脆弱性ゼロとは報告しない。
DockerのHTTP /readyz成功と、State.Health.Statusのhealthyを明示assertした証拠も区別する。
公的TLS/DNS、MariaDB/Caddy/host全体のscan、長時間負荷・容量・SLA・本番復旧は未検証。

[現行監査状況](release-status.md)、[Docker/容量設計](docker-runtime.md)、[TLS](tls.md)、[認証制約](auth.md)も参照。

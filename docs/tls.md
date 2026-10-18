# Single EC2 HTTPS (Issue #61)

Issue #128時点の監査snapshot: main `186aad8cb41fa7d7227304aeed4a4c3d6436ad69` / **Java17・Boot4.1.1**、
[当時のmain CI 39123950859](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39123950859)で必須5ジョブSUCCESS。
以下は本番想定の定義と運用者向け手順。現行構成はAWS未配備として扱い、公的TLS稼働を主張しない。
現在のmain CIは[live確認先](release-status.md#現在のmain-cilive)で確認し、上の固定記録と区別する。
[監査証跡](release-status.md)と[loopback HTTPの隔離デモ](demo.md)を参照。

Internet TCP80/443 → host Caddy → `127.0.0.1:8080` → Compose app。
Terraformは必須FQDN `app_domain`、専用EIP/association、public TCP80/443を定義する。
SSH22は運用者CIDRだけ。8080/2019/3306やIPv6のpublic ingressは追加しない。
実AWS plan/apply・DNS変更・EC2操作・public CA証明書発行・Deploy再有効化は今回未実施。

## Binaryとbootstrap

[公式GitHub release v2.11.7](https://github.com/caddyserver/caddy/releases/tag/v2.11.7)の
`caddy_2.11.7_linux_amd64.tar.gz`のみを使用する。plugins/xcaddy/download APIは使用しない。
GitHub release assetのSHA-256 digestは
`727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b`。
公式`caddy_2.11.7_checksums.txt`はSHA-512形式で、同archiveは
`a7a433a1b133efc3c8d10eb0b99d52a24b5ef5c322dc77f5282182b1c0402139ab83f3a99f0c52409df77d20123fb0b523edad8a66d8f5e49136197bf61ef0e7`。
公式値とダウンロードしたarchiveの両digest一致を確認した。

`scripts/tls/install-caddy.sh`はdownload → SHA-256検証 → archive内のcaddyだけextract →
`caddy version`のexact v2.11.7確認 → install → 非rootでconfig validate → systemd daemon-reloadの順。
途中失敗で停止し、checksum検証前のbinaryは実行・installしない。
Terraform userdataはレビュー済みinstaller/template/unitをbase64で埋め込み、後でcloneするmainに依存しない。
bootstrapではCaddyをenable/startしない。DNS未設定のhostからACME発行を繰り返さないためである。
EIP associationがinstance作成に続くため、AL2023既存curlで外向きHTTPS到達を最大120秒retryしてからpackageを取得する。

dedicated non-login `caddy:caddy`、`NoNewPrivileges=true`、bind用`CAP_NET_BIND_SERVICE`だけで常駐する。
root-owned `/etc/caddy`は0750、Caddyfileはroot:caddy/0640。
certificate/key/account stateは`/var/lib/caddy` (caddy:caddy/0700、service umask0077)へ保持する。
app deploy・rebootで消えないが、root EBSはtermination時削除なのでhost破棄後の復旧は別途必要。
TLS stateを公開・Git登録せず、運用上の保管/復旧手順を用意する。
systemdは起動前・reload前にconfig validateし、state path以外をread-onlyにする。

## 初回公開（運用者の手動作業）

1. [Terraform手順](../terraform/README.md)の`app_domain = "api.example.com"`を所有domainへ置換し、
   運用者がplan・費用・replacement・EIP割当結果をレビューする。既存hostへ無条件applyしない。
2. apply後の`terraform output -raw public_ip`を確認し、**app_domainのDNS A record → EIP**を手動設定する。
   Route53 automationはない。今回IPv6は提供しないのでこのhostを指すAAAA recordを残さない。
3. DB/secret/appを[配備手順](deployment.md)で準備し、hostから
   `curl --fail --silent http://127.0.0.1:8080/readyz`が`{"status":"UP"}`になることを確認する。
4. 外部DNS resolverでもA recordがEIPへ反映済みであり、TCP80/443が外部から到達可能なことを確認する。
   秘密鍵・password・tokenをcommand output/作業記録へ貼らない。
5. root権限でcheckoutから次を実行する（domain/IPは文書用）。
   scriptはdomain・IPv4・installed config一致、host resolverのA record全件一致、
   Caddy config validateを確認してからenable/startする。

   ```bash
   sudo bash scripts/tls/enable-caddy.sh api.example.com 203.0.113.20
   ```

6. 公開HTTPSのcertificate/chain、HTTP→HTTPS redirect、HSTS、`X-Request-Id`を確認する。
   DNS→EIP、80/443到達性、Caddy稼働が標準automatic HTTPSの証明書発行・更新の前提。
   Caddyは標準ACMEを使い、この運用者のstart以降にpublic CAへアクセスする。

   ```bash
   curl --head http://api.example.com/readyz
   curl --fail --silent --show-error --include https://api.example.com/readyz
   sudo systemctl status caddy
   ```

`/livez`はDBを含まないliveness、`/readyz`はreadinessState+DBのまま。
TLS障害とapp readinessを別に確認し、localhost deploy healthをHTTPSへ置き換えない。
Caddy config変更/reloadは独立した運用者作業。通常app deploy/rollbackでは触れない。

## Proxy・headers・ログの境界

repo template `terraform/caddy/Caddyfile.tmpl`からvalidated domainだけをrenderする。秘密情報は含めない。
adminは`127.0.0.1:2019`のみ。upstreamは`127.0.0.1:8080`固定、Composeもloopbackだけへpublishする。
標準automatic HTTPSによるHTTP redirectで、平文APIはpublicへ提供しない。
HSTSはupstream response後に置換し`max-age=31536000`のみ。includeSubDomains/preloadは付けない。
appが返す`X-Request-Id`は変更しない。

Caddyは非信頼clientのX-Forwarded-For/Proto/Hostを上書きし、`Forwarded`と
X-Forwarded-Port/Prefix/Sslを除去する。prodだけ`server.forward-headers-strategy=framework`で
proxy情報を処理し、default/localへ強制しない。host/同一Compose networkは信頼境界内で、
loopback制限だけでhost上の不正processからappを保護できるとは扱わない。
prodのappは信頼proxyの設定不足を起動時に拒否する。Composeの`LOGIN_TRUSTED_PROXIES`は
実appから観測するCaddy transport peerを運用者が確認して設定する。Caddy側localhostやDocker内部IPを推測して固定しない。
非信頼peerのX-Forwarded-Forを認証制限の接続元として採用せず、判定不能503を共有IPへのfallbackに置き換えない。
隔離デモのpeer実測は本番のpeer確認の代用ではない。詳細は[認証設計](auth.md)。
API status/body/schema/auth/ProblemDetail/pagination/idempotencyは変更しない。

Caddy access log・debug・request credential loggingは有効化しない。
request URL/headersを含み得る`http.log.error`もglobal loggerから除外し、TLS/service診断は残す。
request/response body、Authorization、Cookie、Idempotency-Key、JWT、query付きfull URLを追加記録しない。
Issue #3のapplication structured eventsと安全な`system.unhandled_error`がrequest診断のsource of truth。
TLSのdomain/証明書運用ログは残す。

## 検証と未実施事項

Terraform mock planはEIP/association/output、SG、required domain/拒否条件、16KiB userdataを検証する。
Java mock host testはdownload/checksum/version/config/systemd失敗時のfail-closedとDNS起動gateを検証する。
`CaddyProxyIT`は公式archiveをdigest検証後、**network=none**のDocker containerで起動する。
production configのoffline validateと、port/domain/storage/admin/local issuerだけを置換したconfigで
HTTP redirect、HTTPS proxy、HSTS、request ID、401/409透過、forwarded header偽装防止、
通常応答とupstream失敗時の**実Caddy log output**へのcanary非出力を確認する。
upstream fixtureはHTTP proxy契約のテスト用。実appのprod forwarded HTTPS処理はProductionProfilesITで確認する。
TLS clientも隔離container内でlocal CAだけをtrustし、hostname/certificate検証は無効化しない。
CIからpublic CAへ証明書発行要求を送らない。

実domain/DNS/本番証明書確認、Route53/ALB/ACM/CloudFront/IPv6/mTLS/WAF/OAuth2、
remote state/backend、実AWS操作はOut of scope。
Issue61導入時のBoot3.5.12は履歴で、現行はJava17/Boot4.1.1/Terraform1.15.6/provider6.68.0。
main CIはCaddyProxyITを含むJava2,176とTerraform mock27を実行するが、実DNS・公的CA・本番peer・実EC2への到達性は証明しない。

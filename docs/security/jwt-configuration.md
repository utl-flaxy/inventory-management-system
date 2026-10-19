# JWT設定（Issue #9）

`JWT_SECRET` は必須。固定鍵へのfallbackはなく、未設定・空・空白のみ・32 UTF-8バイト未満では起動に失敗する。
設定値はUTF-8文字列として署名鍵に使う（Base64デコードはしない）。暗号学的にランダムな値を生成して
秘密情報の保管先から注入し、ソース・README・ログ・PR本文には実際の秘密値を記載しない。

設定例（秘密値はプレースホルダー）：

```text
JWT_SECRET=<random-secret-at-least-32-UTF-8-bytes>
JWT_ISSUER=inventory-management-system
JWT_EXPIRATION_MS=3600000
```

`JWT_ISSUER` と `JWT_EXPIRATION_MS` は省略可能で、上記が既定値。
issuerは空にできず、有効期間は正の値が必要。JWTの署名・issuer・期限を1回のparseで検証し、
期限切れと期限なしのJWTを拒否する。未認証・別鍵・issuer不一致の保護APIアクセスは401になる。

設定の対応は`app.jwt.secret` / `app.jwt.issuer` / `app.jwt.expiration-ms`。
アプリプロセスへ渡す環境変数等のSpring外部設定で上書きできる。TTLの変更は新規発行分に適用され、
既発行JWTの`exp`は変更されない。実稼働環境の設定値・注入状態は未確認であり、既定値から推測しない。

現在の[docker-compose.yml](../../docker-compose.yml)はappのenvironmentに
`JWT_SECRET: ${JWT_SECRET:?JWT_SECRET is required}`を明示し、未設定・空ならCompose解決時に拒否する。
privateな`.env`またはComposeへ渡すhost環境から解決する。hostにある任意の環境変数が
自動でコンテナへ引き継がれるわけではない。
`JWT_ISSUER` / `JWT_EXPIRATION_MS`は現行Composeのenvironmentに指定がないため、
hostや`.env`に置くだけではappに渡らない。Compose以外の起動でアプリへ直接渡すか、
別途レビュー済みの注入設定が必要。この文書化ではCompose・本番設定を変更しない。
[設定・secretの取り扱い](../configuration.md#docker-compose-startup)に従い、秘密値や解決済みComposeをログ・PR・artifactへ出力しない。

公開済み・漏洩した旧鍵は再利用しない。すべての検証プロセスを新鍵へ切り替えると、旧鍵で発行された全JWTが拒否される。
複数鍵の同時検証はなく、特定JWTだけの失効でもない。再ログインが必要になり得る。
旧imageへのrollbackで旧鍵・旧環境も復元すると、期限内の旧JWTが再受理され得る。
imageだけで鍵の世代は決まらないため、rollback先のコードと設定を一緒に評価する。
本書は実secretの変更や本番rotationを実施したものではない。

クライアントlogout、DB password hash変更、ユーザー不在・role変更の効果、username再利用、
logout-allと未実装の個別失効等は[JWTライフサイクルと失効制約](../auth.md#jwtライフサイクルと失効制約)を正本とする。
B1のtokenVersion＋uidとlogout-allは[現行認証契約](../auth.md#jwtライフサイクルと失効制約)、
旧JWTの一括拒否と旧image/backupへの復帰制約は[移行手順](jwt-revocation-migration.md)を参照。
jti台帳、password変更、ADMIN強制失効は後続であり未実装。

Java 17とDockerで `./mvnw clean verify`（Windows: `mvnw.cmd clean verify`）を実行する。
JWTテストには本番の秘密値・DBを使わず、署名検証と実SecurityFilterChainの401/200応答、
本番用application.propertiesでの起動失敗、各リクエストのparseが1回であることを検証する。
Issue #10の注文・商品・ドメイン・DB制約テストも全件実行する。

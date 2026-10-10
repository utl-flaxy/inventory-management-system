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

デプロイ前に実行環境へ `JWT_SECRET` を設定する。Dockerの場合は、ホストに設定するだけでなく
アプリコンテナへ明示的に渡す必要がある。現在のComposeはJWT環境変数を転送しないため、
本番の秘密情報の注入方法を整えてからデプロイする。このPRでは本番設定を変更しない。
公開済みの旧鍵は再利用しない。鍵を変更してアプリを再起動すると、旧鍵で発行されたJWTは無効になる。
本対応は設定による鍵の差し替えであり、複数鍵を同時に扱う仕組みは導入しない。

Java 17で `./mvnw clean test`（Windows: `mvnw.cmd clean test`）を実行する。
JWTテストには本番の秘密値・DBを使わず、署名検証と実SecurityFilterChainの401/200応答、
本番用application.propertiesでの起動失敗、各リクエストのparseが1回であることを検証する。
Issue #10の注文・商品・ドメイン・DB制約テストも全件実行する。

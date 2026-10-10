# 📦 在庫管理・注文管理システム


本プロジェクトは、

**「データ整合性を担保した注文処理設計」**

をテーマに開発したバックエンドAPIです。

注文処理における、

- 在庫不足による不整合
- トランザクション途中失敗
- 価格変更による履歴不整合

といった実務課題を解決するため、

トランザクション制御・状態管理・ドメイン設計を重視して構築しました。

また、開発だけでなく、

- Dockerによる環境統一
- AWS + Terraformによるインフラ構築
- GitHub ActionsによるCI/CD

まで一貫して実装しています。

---

## ✅ このプロジェクトで証明できること

- トランザクションを用いた在庫・注文の整合性設計
- Spring Bootによる実務レベルのREST API設計
- JWT認証・認可の実装
- Docker / AWS / Terraform / CI/CDを含めた本番運用構成
- レイヤードアーキテクチャによる責務分離

---

# 🎯 開発背景

ECサイト開発では、

- 在庫と注文の整合性が崩れるリスク
- 複数処理の途中失敗による不整合
- 商品価格変更による過去データへの影響

などの課題が存在します。

これらを解決するため、

「データ整合性」を重視した在庫管理・注文管理システムを開発しました。

---

# 🔧 苦労した点

- トランザクション境界の設計に苦労
- 在庫更新と注文作成の順序による不整合
- JWT認証のFilter実装

これらを検証しながら改善しました

---

# 🚀 技術スタック

|分類|技術|
|---|---|
|Language|Java17|
|Framework|Spring Boot 3.5|
|Security|Spring Security + JWT|
|ORM|Spring Data JPA|
|Database|MariaDB|
|Schema Migration|Flyway（Spring Boot管理）|
|Build Tool|Maven|
|API Document|Swagger(OpenAPI3)|
|Container|Docker / Docker Compose|
|Cloud|AWS EC2|
|IaC|Terraform|
|CI/CD|GitHub Actions|

---

## DBスキーマ管理

Flywayの `src/main/resources/db/migration` がschemaのsource of truthです。
通常起動はFlyway migrate → Hibernate validateで、Hibernateはschemaを変更しません。

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=false
spring.flyway.clean-disabled=true
```

新規の空MariaDBは接続情報とJWT_SECRETを設定してJava 17で起動するとV1が全テーブルを作成し、V2が金額3列をBIGINTへ拡張し、V3が完了日時と期間検索索引、V4が注文冪等性の列とUNIQUEを追加します。
既存DBはbackup・schema確認・data preflight・確認済み履歴の補完・schema照合を行い、
手動baseline後にvalidateします。未確認DBの自動baselineは行いません。
customer/日時/商品名snapshotを現在の値から推測で埋めないでください。
暫定SQLは `docs/db/legacy` にアーカイブしました。
起動・既存DB移行・rollbackの詳細は[DB運用手順](docs/db/README.md)を参照してください。

- mainに入ったVersioned migrationは編集・削除せず、新しいVxを追加する。
- migration名は目的を表し、schema変更はPR reviewを必須とする。
- 適用前にbackupとpreflightを行う。
- checksum mismatchは原因を調査し、repairで雑に直さない。
- 本番で `flyway clean` を実行せず、clean-disabledも解除しない。
- MariaDB DDLの失敗は完全なTransaction rollbackを保証しない。review済みの前進修復またはbackup復元を行う。

Java 17とDockerで `./mvnw verify` を実行すると、MariaDB 12.2.2でmigration・制約・再起動・
認可・並行制御を検証します。Boot管理のFlyway 11.7.2による12.2未検証警告と実DB検証の扱いも
[DB運用手順](docs/db/README.md)に記載しています。

## テスト品質ゲート

- ローカル高速テスト: `./mvnw test`（Windows: `mvnw.cmd test`）。SurefireでUnit・domain・Mockito・Controller slice・H2補助テストを実行し、Dockerは不要です。
- 完全検証・PR前必須: `./mvnw verify`（Windows: `mvnw.cmd verify`）。Surefireに加えFailsafeでMariaDB/Testcontainers統合テストを実行します。Java 17と起動済みDockerが必要です。
- GitHub CIも `./mvnw verify` を使用します。IT失敗・Docker未起動はbuild失敗となり、skipしません。

命名・実行phase・レポート・Auth/Salesの現行仕様は[テスト手順](docs/testing.md)を参照してください。

## 金額の型（Issue #25）

価格・注文時単価・明細合計・注文合計・売上合計は**円の整数値**をJava Long/longで扱います。
MariaDBのproducts.price / orders.total_price / order_items.priceはV2でsigned BIGINTへ移行します。
V1は変更せず、既存値と非負CHECK・NOT NULL・FK・indexを保持します。数量・在庫はInteger/INTのままです。
30億円×2個＝60億円など旧Integer上限を超える注文が可能です。商品APIは引き続き1円以上、
Long.MAX_VALUEまでを許可し、注文時のlong multiplyExact/addExact overflowは在庫変更前に400で拒否します。
JSON field名とnumber形式は維持し、OpenAPIの金額はinteger/int64になります。
Money Value Object、小数、税・割引、多通貨は未導入です。売上は完了日時が判明したCOMPLETED注文のみ計上します（[売上仕様](docs/sales.md)）。
移行・再起動・rollbackの扱いは[DB運用手順](docs/db/README.md)を参照してください。

---

# ⭐ 主な機能

## 商品管理

- 商品登録
- 商品一覧取得
- 商品詳細取得
- 商品検索
- 商品更新
- 商品削除

---

## 注文管理

- 注文作成
- 在庫減算
- 在庫不足判定
- 注文ステータス変更
- 売上集計

---

## 認証機能

- ユーザー登録
- ログイン
- JWT認証

---

## CSV出力

- 注文データCSV出力

---

## インフラ

- Docker化
- AWS EC2デプロイ
- Terraformによる環境構築
- GitHub Actionsによる自動デプロイ

---

# 📚 目次

- システム構成
- スクリーンショット
- API一覧
- コア設計
- ER図
- シーケンス図
- Docker構成
- Terraform構成
- GitHub Actions
- CI/CD
- 工夫したポイント
- 技術選定理由
- 今後の改善
- 採用担当者の方へ


# 🏗 システム構成

本システムは Spring Boot・Docker・AWS・Terraform・GitHub Actions を利用して構築しています。

```text
GitHub
   │
   ▼
GitHub Actions (CI/CD)
   │
   ▼
AWS EC2
   │
   ▼
Docker Compose
   │
   ▼
Spring Boot
   │
   ▼
MariaDB
```

---

# ☁️ アーキテクチャ

```text
┌──────────────┐
│    GitHub    │
└──────┬───────┘
       │ Push
       ▼
┌──────────────┐
│ GitHubActions│
│   CI / CD    │
└──────┬───────┘
       │ SSH
       ▼
┌──────────────┐
│   AWS EC2    │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│ Docker Compose│
├──────────────┤
│ Spring Boot  │
│ MariaDB      │
└──────────────┘
```

---

# 📸 スクリーンショット

## Swagger UI

OpenAPI により API ドキュメントを自動生成しています。

![Swagger UI](docs/images/swagger-ui.png)

---

## 商品一覧取得

商品一覧 API の実行結果。

![Product List](docs/images/product-list.png)

---

## 商品詳細取得

商品詳細 API の実行結果。

![Product Detail](docs/images/product-detail.png)

---

## 商品検索

キーワード検索機能。

![Product Search](docs/images/product-search.png)

---

## 注文成功

正常に注文できた場合のレスポンス。

![Order Success](docs/images/order-success.png)

---

## 在庫不足

独自例外による在庫不足エラー。

![Out Of Stock](docs/images/out-of-stock.png)

---

## JWT認証

Spring Security + JWT による認証。

![JWT Auth](docs/images/jwt-auth.png)

---

## 売上集計 API

ADMIN専用。COMPLETEDかつcompletedAtがある注文を、完了日時（UTC Instant）で集計します。

![Sales API](docs/images/sales-api.png)

---

## CSV出力

注文データをCSV形式で出力。

![Order Export](docs/images/order-export.png)

---

## Dockerコンテナ

Spring Boot + MariaDB を Docker Compose で管理。

![Docker](docs/images/docker-ps.png)

---

## Terraform

AWS環境をコード管理。

![Terraform](docs/images/terraform-main.png)

---

## GitHub Actions

CI/CD による自動デプロイ。

![GitHub Actions](docs/images/github-actions-success.png)

---

## AWS EC2

本番環境。

![AWS EC2](docs/images/aws-ec2-running.png)

---

# 🌎 本番環境

|項目|内容|
|---|---|
|Cloud|AWS EC2|
|OS|Amazon Linux 2023|
|Container|Docker|
|Application|Spring Boot|
|Database|MariaDB|
|IaC|Terraform|
|CI/CD|GitHub Actions|
|Java|17|
|Build Tool|Maven|

---

# 🚀 デプロイフロー

```text
main push → CI success（PR / failureは配備しない）
   ↓ workflow_run.head_shaをDEPLOY_SHAに固定
secrets検証 → SSH → 同時配備lock
   ↓
指定SHA checkout → Java 17 package → Compose検証 → SHA image build
   ↓ 既存appはここまで稼働
appだけ切替（DBは継続稼働）→ HTTP確認
   ├─ 成功: current revisionを確定
   └─ 失敗: previous app image/設定へrollback → HTTP確認
```

# 📋 主な機能

本システムは、商品管理・注文管理・認証機能を中心とした REST API システムです。

---

# 📦 商品管理機能

商品のCRUD操作を実装しています。

## 商品登録

```http
POST /products
```

登録項目

- 商品名
- 価格
- 在庫数

---

## 商品一覧取得

```http
GET /products
```

---

## 商品詳細取得

```http
GET /products/{id}
```

---

## 商品検索

キーワードによる検索が可能です。

```http
GET /products/search?keyword=apple
```

---

## 商品更新

```http
PUT /products/{id}
```

---

## 商品削除

```http
DELETE /products/{id}
```

---

# 🛒 注文管理機能

注文APIは自分用 `/api/v1/me/orders` と管理用 `/api/v1/admin/orders` に分離しています。
USERは自分の注文の作成・一覧・詳細・PENDINGのcancel、ADMINは管理操作を利用できます。
ADMINも自分用APIを利用できますが、その入口では自分の注文だけが対象です。
旧 `/orders/**` は廃止しました。権限表・404・ロックの詳細は[注文APIの認可](docs/security/order-authorization.md)を参照してください。

## 注文作成

```http
POST /api/v1/me/orders
Idempotency-Key: 01J9VQW2M7MX7KQ7S2H4F7ABCD
```

注文時に必須・非空の `items` 配列（商品IDと正の数量）を受け取り、認証済みUserを注文者として保存します。
同一商品IDの重複は400です。旧単一商品形式は `items` に変更してください。
Issue #29から注文作成だけ `Idempotency-Key` headerが必須です。ASCII `[A-Za-z0-9._:-]{1,128}`、
大小文字を区別し、前後空白も400で拒否します。同customer/key/明細の再送は同じOrder ID・現在状態と
最初の価格snapshotを200で返し、在庫を再減算しません。明細順は無視しますが、別内容なら409です。
別customerは同じkeyを使えます。新注文には新key、通信retryには元のkeyを送ってください。
GET/cancel/ADMINコマンドのheader契約は変わりません。[冪等性仕様・移行・並行制御](docs/orders/idempotency.md)を参照してください。

- 在庫確認
- 在庫減算
- 合計金額計算
- 注文保存

を行います。

customer行をロックしてreplay/conflictを先に判定し、新規の場合だけ全商品を商品ID昇順でロックします。
1商品でも失敗したら在庫・注文・全明細をrollbackします。
作成・一覧・詳細・状態変更はOrderResponseを返します。`orderItems`/ネストしたProductの代わりに
`items` と商品名・単価のスナップショットを返し、customerはid/usernameのみです。
移行手順・API互換性の詳細は [Order集約](docs/orders/order-aggregate.md) を参照してください。

---

## 注文一覧取得

```http
GET /api/v1/me/orders
GET /api/v1/admin/orders
```

自分用APIはDB queryで注文者を絞り、管理用APIはADMINに全注文を返します。

---

## 注文詳細取得

```http
GET /api/v1/me/orders/{id}
GET /api/v1/admin/orders/{id}
```

自分用APIで他人の注文を指定した場合は、存在しない注文と同じ404です。

---

## 注文操作

```http
POST /api/v1/me/orders/{id}/cancel
POST /api/v1/admin/orders/{id}/ship
POST /api/v1/admin/orders/{id}/complete
POST /api/v1/admin/orders/{id}/cancel
PATCH /api/v1/admin/orders/{id}/status
```

自分用cancel以外はADMIN専用です。status互換APIも既存の業務コマンドへ振り分けます。
状態遷移と二重cancelの防止は[注文状態遷移](docs/orders/state-transitions.md)を参照してください。
未認証401、USERの管理API利用403、他人/不存在404、不正状態遷移409を返します。

---

# 📈 売上集計機能

ADMINが売上情報を取得できます。COMPLETEDかつcompletedAtがある注文だけが対象です。PENDING/SHIPPED/CANCELLEDと完了日時不明のlegacy注文は除外します。

```http
GET /api/v1/admin/orders/sales
```

取得内容

- 売上対象のCOMPLETED注文件数（totalOrders）
- 売上合計

`from` / `to` は省略可能なISO-8601 Instantです（例: `2026-10-01T00:00:00Z`）。
`from <= completedAt < to`、片側だけならその境界だけを適用し、両方省略なら全期間です。
`from >= to` と不正日時は400。完了日時不明のlegacy COMPLETEDは全期間でも除外します。
詳細と移行手順は[売上仕様](docs/sales.md)を参照してください。

---

# 📄 CSV出力機能

ADMINが注文データをCSV形式で出力できます。

```http
GET /api/v1/admin/orders/export
```

---

# 🔐 JWT認証

Spring Security + JWT によりステートレス認証を実装しています。

---

## ユーザー登録

登録・loginともusernameは `[A-Za-z0-9._-]{3,50}`、passwordは8〜72文字かつUTF-8で72 bytes以下です。
null/blank・形式不正は400、trimしません。usernameの照合は現行DBどおり大小文字を区別しません。
登録はROLE_USER固定で200を返し、重複usernameは409 `USERNAME_ALREADY_EXISTS`。
password/hashは返さず、保存はBCryptです。詳細は[Auth契約](docs/auth.md)を参照してください。

```http
POST /auth/register
```

### Request

```json
{
  "username": "admin",
  "password": "password"
}
```

---

## ログイン

形式不正は400、形式が有効でもuser不存在・password不一致なら同一本文の401 `INVALID_CREDENTIALS`です。
成功は従来の200/tokenだけを返します。JWTの署名・issuer・期限と、DB roleによる認可を維持します。

```http
POST /auth/login
```

### Request

```json
{
  "username": "admin",
  "password": "password"
}
```

### Response

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

---

# 📚 API一覧

|Method|Endpoint|内容|
|---|---|---|
|POST|/auth/register|ユーザー登録|
|POST|/auth/login|ログイン|
|GET|/products|商品一覧|
|GET|/products/{id}|商品詳細|
|GET|/products/search|商品検索|
|POST|/products|商品登録|
|PUT|/products/{id}|商品更新|
|DELETE|/products/{id}|商品削除|
|POST|/api/v1/me/orders|USER/ADMIN: 自分の注文作成|
|GET|/api/v1/me/orders|USER/ADMIN: 自分の注文一覧|
|GET|/api/v1/me/orders/{id}|USER/ADMIN: 自分の注文詳細|
|POST|/api/v1/me/orders/{id}/cancel|USER/ADMIN: 自分のPENDINGをcancel|
|GET|/api/v1/admin/orders|ADMIN: 全注文一覧|
|GET|/api/v1/admin/orders/{id}|ADMIN: 任意の注文詳細|
|POST|/api/v1/admin/orders/{id}/ship|ADMIN: 出荷|
|POST|/api/v1/admin/orders/{id}/complete|ADMIN: 完了|
|POST/PATCH|/api/v1/admin/orders/{id}/cancel|ADMIN: cancel|
|PATCH|/api/v1/admin/orders/{id}/status|ADMIN: 状態変更の互換API|
|GET|/api/v1/admin/orders/sales|ADMIN: 売上集計|
|GET|/api/v1/admin/orders/export|ADMIN: CSV出力|

---

# 📦 レスポンス例

## 商品一覧取得

### Request

```http
GET /products
```

### Response

```json
[
  {
    "id": 1,
    "name": "Apple",
    "price": 100,
    "stock": 50
  },
  {
    "id": 2,
    "name": "Orange",
    "price": 200,
    "stock": 20
  }
]
```

---

# 📝 注文作成例

### Request

```json
{
  "items": [
    {
      "productId": 1,
      "quantity": 2
    }
  ]
}
```

### Response

```json
{
  "id": 1,
  "totalPrice": 200,
  "status": "PENDING",
  "orderedAt": "2026-10-10T03:00:00Z",
  "updatedAt": "2026-10-10T03:00:00Z",
  "completedAt": null,
  "version": 0,
  "customer": {"id": 1, "username": "alice"},
  "items": [{"productId": 1, "productName": "りんご", "unitPrice": 100, "quantity": 2, "lineTotal": 200}]
}
```

---

# ⚠ エラーハンドリング

GlobalExceptionHandler を利用して例外処理を共通化しています。
Issue #33から、ControllerAdviceとSecurityの主要4xx/5xxはSpring ProblemDetail、
`application/problem+json`へ統一しました。**エラーbodyの破壊的変更**で、
旧`message`は`detail`、旧Validation Mapは`errors`へ移ります。
既存status/codeと成功レスポンスは維持します。[APIエラー契約](docs/api-errors.md)を参照してください。

---

## 在庫不足

独自例外

```java
OutOfStockException
```

### Response

```json
{
  "type": "https://inventory-management-system.example/problems/out-of-stock",
  "title": "Out of Stock",
  "status": 400,
  "detail": "在庫が不足しています",
  "instance": "/api/v1/me/orders",
  "code": "OUT_OF_STOCK"
}
```

---

## 不正な認証

JWT認証エラー時

```json
{
  "type": "https://inventory-management-system.example/problems/unauthenticated",
  "title": "Unauthenticated",
  "status": 401,
  "detail": "認証が必要です",
  "instance": "/api/v1/me/orders",
  "code": "UNAUTHENTICATED"
}
```

---

# 🧩 採用担当者向けポイント

本システムでは、

- DTOによる責務分離
- REST API設計
- Spring Security + JWT
- GlobalExceptionHandler
- 独自例外処理
- CSV出力機能

を実装し、

単なるCRUDではなく、実務を意識したAPI設計を行っています。


# 🧠 コア設計

本システムは「データ整合性」を最重要として設計しています。

単なるCRUDではなく、

- 在庫数
- 注文情報
- 商品価格
- ステータス管理

を一貫性を保ちながら処理できるよう設計しています。

---

# 🔥 トランザクション管理

注文処理では、

1. 在庫確認
2. 在庫減算
3. 合計金額計算
4. 注文作成

を1トランザクションで実行しています。

途中でエラーが発生した場合はロールバックされ、不整合を防止します。

```java
@Transactional
public Order createOrder(OrderRequest request) {

    // 在庫確認

    // 在庫減算

    // 注文作成

    // 保存

}
```

---

# 📦 データ整合性

## ■ 注文時価格を保持

OrderItemに価格を保持しています。

そのため商品価格変更後でも、

過去の注文データの金額は変わりません。

### 商品

```text
Apple 100円
↓
価格変更
↓
Apple 150円
```

でも、

過去注文は

```text
100円
```

のまま保持されます。

---

## ■ 在庫管理

注文時

```text
在庫確認

↓

在庫減算

↓

注文保存
```

を同時に実行しています。

途中で失敗した場合はロールバックされます。

---

## ■ 状態管理

OrderStatusによって状態を管理しています。

```text
PENDING

↓

PAID

↓

SHIPPED

↓

COMPLETED
```

不正な状態変更を防止できる構成です。

---

# 🗃 ER図

```mermaid
erDiagram

USER ||--o{ ORDER : places

ORDER ||--|{ ORDER_ITEM : contains

PRODUCT ||--o{ ORDER_ITEM : included

USER {
    Long id
    String username
    String password
}

PRODUCT {
    Long id
    String name
    Long price
    Integer stock
}

ORDER {
    Long id
    Long customerId
    Long totalPrice
    String status
    Instant orderedAt
    Instant updatedAt
    Instant completedAt
    Long version
}

ORDER_ITEM {
    Long id
    Integer quantity
    Long unitPrice
    String productNameSnapshot
}
```

---

# 🔄 注文処理シーケンス図

```mermaid
sequenceDiagram

actor User

User->>OrderController: 注文作成

OrderController->>OrderService: createOrder()

OrderService->>ProductRepository: 商品取得

ProductRepository-->>OrderService: Product

OrderService->>OrderService: 在庫確認

alt 在庫不足

OrderService-->>User: OutOfStockException

else 在庫あり

OrderService->>OrderService: 合計金額計算

OrderService->>ProductRepository: 在庫更新

OrderService->>OrderRepository: 注文保存

OrderRepository-->>OrderService: Order

OrderService-->>User: Success

end
```

---


# 🔐 認証処理シーケンス図

```mermaid
sequenceDiagram

actor User

User->>AuthController: Login

AuthController->>AuthService: login()

AuthService->>UserRepository: findByUsername()

UserRepository-->>AuthService: User

AuthService->>JwtUtil: generateToken()

JwtUtil-->>AuthService: JWT

AuthService-->>User: Token
```

---

# 📂 ディレクトリ構成

```text
.
├── src
├── docs
│   └── images
├── terraform
├── .github
│   └── workflows
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

# 📦 パッケージ構成

## controller

APIエンドポイント

- AuthController
- ProductController
- OrderController

---

## service

業務ロジック

- AuthService
- ProductService
- OrderService

---

## repository

DBアクセス

- UserRepository
- ProductRepository
- OrderRepository
- OrderItemRepository

---

## entity

エンティティ

- User
- Product
- Order
- OrderItem

---

## dto

リクエスト・レスポンス

- LoginRequest
- LoginResponse
- ProductRequest
- ProductResponse
- OrderRequest
- OrderItemRequest
- SalesResponse

---

## security

認証処理

- SecurityConfig
- JwtUtil
- JwtAuthenticationFilter
- CustomUserDetailsService

---

## exception

例外処理

- GlobalExceptionHandler
- OutOfStockException

---

## config

設定

- OpenApiConfig

---

# ⭐ 設計で意識したポイント

### Controllerを薄くする

ControllerにはHTTP処理のみを記述し、

業務ロジックはService層へ集約しています。

---

### DTOを利用

Entityを直接公開せず、

Request / Response DTOによって責務を分離しています。

---

### Repository層を分離

Spring Data JPAを利用し、

永続化処理をRepositoryへ集約しています。

---

### 例外処理を共通化

GlobalExceptionHandlerによって

エラーレスポンスを統一しています。

---

### JWT認証

Spring Security + JWTを利用し、

ステートレスな認証を実現しています。

---

# 💡 このプロジェクトで特に意識したこと

「動けば良い」ではなく、

- データ整合性
- 保守性
- 責務分離
- 拡張性

を意識して設計しました。

特に、

**「在庫確認 → 在庫減算 → 注文作成」**

を1トランザクションで管理することで、

実務でも重要となる整合性を意識した設計を行っています。

---

# 🐳 Docker構成

本システムは Docker Compose を利用して、

- Spring Boot
- MariaDB

をコンテナ化しています。

環境差異をなくし、ローカル環境と本番環境で同じ構成を再現できるようにしています。

---

# Docker構成図

```text
Docker Compose
│
├── inventory-app
│      Spring Boot
│      Java17
│      Port 8080
│
└── inventory-db
       MariaDB 12.2
       Port 3306
````

---

# 使用イメージ

## Application

```text
eclipse-temurin:17-jdk
```

## Database

```text
mariadb:12.2
```

---

# コンテナ操作

起動

```bash
docker compose up -d
```

停止

```bash
docker compose down
```

確認

```bash
docker ps
```

---

# ☁ AWS構成

本番環境は AWS EC2 上で稼働しています。

```text
AWS EC2
│
├── Docker
│
├── inventory-app
│
└── inventory-db
```

---

# AWS環境

| 項目            | 内容                |
| ------------- | ----------------- |
| Cloud         | AWS               |
| Service       | EC2               |
| Instance Type | t3.micro          |
| OS            | Amazon Linux 2023 |
| Java          | 17                |
| Container     | Docker            |
| Database      | MariaDB           |

---

# 🏗 Terraform

Infrastructure as Code により、

AWS環境をコードで管理しています。

## 作成しているリソース

### EC2

Spring Bootアプリケーションを配置。

### Security Group

許可ポート

* 22 (SSH)
* 8080 (Application)

### Key Pair

SSH接続用秘密鍵。

### User Data

初期セットアップ

* Git
* Docker
* Docker Compose

を自動構築。

---

# Terraform構成

```text
terraform
├── provider.tf
├── variables.tf
├── main.tf
├── outputs.tf
└── userdata.sh
```

---

# ⚙ GitHub Actions

CI/CD環境を構築しています。

## CI

ファイル

```text
.github/workflows/ci.yml
```

実施内容

* Checkout
* Javaセットアップ
* Maven Build
* Test

## CD

ファイル

```text
.github/workflows/deploy.yml
```

実施内容

* main pushのCI成功のみ起動し、CIで検証したSHAに固定
* secrets検証・concurrency・EC2上のflockで切替前を保護
* Java 17 Maven package・Compose検証・SHA image buildを既存app稼働中に実施
* appだけ更新し、DB container/volumeを継続
* HTTP確認失敗時は直前app image/Compose設定へ復元

詳細・導入手順・failure scenarioは [配備手順](docs/deployment.md) を参照。
app rollbackはDB rollbackではありません。Flyway migrationを自動undoせず、
後方互換性・backup・forward-fixを配備前に確認します。

---

# 🚀 CI/CDフロー

```text
PR → CI → 配備なし
main push → CI success → head_sha固定 → 直列deploy
  → secrets / checkout / package / config / image build
  → appだけ切替 → HTTP確認 → current確定
                         └─失敗: previous app復元 / HTTP確認
```

---

# ⭐ 工夫したポイント

## レイヤードアーキテクチャ

Controller → Service → Repository の責務を分離し、

保守性・拡張性を意識した構成にしています。

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
Database
```

---

## トランザクション管理

注文処理では、

* 在庫確認
* 在庫減算
* 注文作成

を1トランザクションで実行しています。

途中で例外が発生した場合はロールバックされるため、

データ不整合を防止できます。

---

## DTOを利用した責務分離

Entityを直接返却せず、

* Request DTO
* Response DTO

を利用することで、

APIとドメインモデルを分離しています。

---

## GlobalExceptionHandler

例外処理を共通化し、

* 在庫不足
* バリデーションエラー

などを統一したレスポンス形式で返しています。

---

## JWT認証

Spring Security + JWTを利用し、

ステートレスな認証を実装しています。

---

## Infrastructure as Code

Terraformを利用して、

AWS環境をコードで管理しています。

---

## CI/CD

GitHub Actionsによって、

Build〜Deployまで自動化しています。

---

# 🔥 技術選定理由

| 技術              | 採用理由                          |
| --------------- | ----------------------------- |
| Spring Boot     | 実務で利用される標準的なJavaフレームワークであるため  |
| Spring Security | 認証・認可機能を実装するため                |
| JWT             | ステートレス認証を実現するため               |
| Spring Data JPA | Repository層をシンプルに実装するため       |
| MariaDB         | MySQL互換で実務利用が多いため             |
| Docker          | 環境差異をなくすため                    |
| AWS EC2         | クラウド環境で公開するため                 |
| Terraform       | Infrastructure as Codeを学習するため |
| GitHub Actions  | CI/CDを構築するため                  |

---

# 🚀 今後の改善

- 排他制御（楽観ロック / 悲観ロック）の導入
- RDSへの移行
- ECSによるコンテナ運用
- 負荷試験の実施

---

# 👨‍💻 採用担当者の方へ

本プロジェクトでは、単なるCRUDアプリではなく、

* データ整合性を考慮した設計
* トランザクション管理
* レイヤードアーキテクチャ
* JWT認証・認可
* Dockerによるコンテナ化
* AWS環境構築
* TerraformによるInfrastructure as Code
* GitHub ActionsによるCI/CD

まで一貫して設計・実装しました。

特に、

「在庫確認 → 在庫減算 → 注文作成」

を1トランザクションで管理することで、

実務でも重要となるデータ整合性を意識した設計を行っています。

また、

アプリケーション開発だけでなく、

インフラ構築から自動デプロイまで自ら構築することで、

「作って終わり」ではなく、

「運用まで考慮したシステム開発」

を経験することができました。

今後も保守性・拡張性を意識した開発に取り組んでいきたいと考えています。

---

# 📄 ライセンス

MIT License

---

# 🙏 Thank you for visiting!

最後までご覧いただきありがとうございます。

⭐ Star や Feedback をいただけると励みになります。

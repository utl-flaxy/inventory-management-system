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
|Build Tool|Maven|
|API Document|Swagger(OpenAPI3)|
|Container|Docker / Docker Compose|
|Cloud|AWS EC2|
|IaC|Terraform|
|CI/CD|GitHub Actions|

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

売上情報の取得。

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
git push
   ↓
GitHub Actions
   ↓
Build & Test
   ↓
SSH
   ↓
AWS EC2
   ↓
git reset --hard origin/main
   ↓
./mvnw clean package
   ↓
docker compose up -d
   ↓
Deploy Complete
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

## 注文作成

```http
POST /orders
```

注文時に

- 商品ID
- 数量

を受け取り、

- 在庫確認
- 在庫減算
- 合計金額計算
- 注文保存

を行います。

---

## 注文一覧取得

```http
GET /orders
```

---

## 注文詳細取得

```http
GET /orders/{id}
```

---

## 注文ステータス変更

```http
PATCH /orders/{id}/status
```

対応ステータス

- PENDING
- PAID
- SHIPPED
- COMPLETED

---

# 📈 売上集計機能

売上情報を取得できます。

```http
GET /orders/sales
```

取得内容

- 注文数
- 売上合計

---

# 📄 CSV出力機能

注文データをCSV形式で出力できます。

```http
GET /orders/export
```

---

# 🔐 JWT認証

Spring Security + JWT によりステートレス認証を実装しています。

---

## ユーザー登録

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
|GET|/orders|注文一覧|
|GET|/orders/{id}|注文詳細|
|POST|/orders|注文作成|
|PATCH|/orders/{id}/status|ステータス変更|
|GET|/orders/sales|売上集計|
|GET|/orders/export|CSV出力|

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
  "status": "PENDING"
}
```

---

# ⚠ エラーハンドリング

GlobalExceptionHandler を利用して例外処理を共通化しています。

---

## 在庫不足

独自例外

```java
OutOfStockException
```

### Response

```json
{
  "message": "在庫が不足しています"
}
```

---

## 不正な認証

JWT認証エラー時

```json
{
  "error": "Unauthorized"
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
    Integer price
    Integer stock
}

ORDER {
    Long id
    Integer totalPrice
    String status
    LocalDateTime orderDate
}

ORDER_ITEM {
    Long id
    Integer quantity
    Integer price
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

* EC2へSSH接続
* Git同期
* Maven Build
* Docker再起動
* 自動デプロイ

---

# 🚀 CI/CDフロー

```text
git push

↓

GitHub Actions

↓

Build

↓

Test

↓

Deploy

↓

AWS EC2

↓

docker compose up -d

↓

Deploy Complete
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

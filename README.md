# 在庫管理＋売上計算システム

Spring Boot を用いて作成した、商品管理・注文管理・在庫管理を行う業務システム風のポートフォリオです。  
商品CRUDに加えて、注文時の在庫減算、合計金額計算、在庫不足時のエラー制御まで実装しています。



## 概要

本アプリは、商品情報を管理し、注文処理を通して在庫を更新できるバックエンドAPIです。  
単純なCRUDだけでなく、注文時の業務ロジックを含めた実装を行うことで、実務に近い構成を意識しました。



## 使用技術

- Java 17
- Spring Boot 3
- Spring Web
- Spring Data JPA
- H2 Database
- Maven
- Lombok
- Postman



## 主な機能

### 商品管理
- 商品一覧取得
- 商品登録
- 商品更新
- 商品削除

### 注文管理
- 注文作成
- 注文一覧取得
- 注文明細表示
- 注文時の在庫減算
- 在庫不足時のエラー返却



## ER図

<!-- ここにER図画像を追加 -->
<!-- 例: ![ER図](./images/er-diagram.png) -->

### テーブル構成
- products
- orders
- order_items



## API一覧

### 商品API

#### 商品一覧取得
- `GET /products`

#### 商品登録
- `POST /products`

リクエスト例
json
{
  "name": "りんご",
  "price": 300,
  "stock": 10
}


#### 商品更新

* `PUT /products/{id}`

リクエスト例

json
{
  "name": "りんご改",
  "price": 350,
  "stock": 20
}


#### 商品削除

* `DELETE /products/{id}`



### 注文API

#### 注文作成

* `POST /orders`

リクエスト例

json
{
  "productId": 1,
  "quantity": 2
}


レスポンス例

json
{
  "id": 1,
  "totalPrice": 600,
  "orderItems": [
    {
      "id": 1,
      "quantity": 2,
      "price": 300,
      "product": {
        "id": 1,
        "name": "りんご",
        "price": 300,
        "stock": 8
      }
    }
  ]
}


#### 注文一覧取得

* `GET /orders`



## 工夫した点

* 商品CRUDだけでなく、注文時の在庫減算処理まで実装した
* 注文金額を `price × quantity` で計算する業務ロジックを組み込んだ
* 在庫不足時は注文を失敗させることで、異常系の動作確認も行った
* `orders` と `order_items` の関連を持たせ、注文明細まで確認できるようにした
* H2 Database をファイル保存モードに変更し、再起動後もデータが残るようにした



## 動作確認方法

### 1. 起動

Spring Boot アプリケーションを起動する。

### 2. H2コンソール

以下にアクセスしてDB内容を確認可能。

* `http://localhost:8080/h2-console`

接続情報:

* JDBC URL: `jdbc:h2:file:./data/testdb`
* User Name: `sa`
* Password: 空欄

### 3. API確認

Postmanを使用して以下のAPIを実行。

* `GET /products`
* `POST /products`
* `PUT /products/{id}`
* `DELETE /products/{id}`
* `POST /orders`
* `GET /orders`



## 今後の改善点

* 例外処理を整理し、在庫不足時に 400 Bad Request を返す
* Entityをそのまま返すのではなく、DTOを使ってレスポンスを整える
* JWT認証を追加してログイン機能を実装する
* Reactでフロントエンド画面を作成する
* AWS環境へデプロイする



## 学んだこと

* Spring Boot による REST API の基本実装
* JPA を用いたテーブル操作とエンティティ関連の扱い
* 注文処理における在庫管理ロジック
* 正常系だけでなく異常系も含めたAPI確認の重要性
* READMEで実装内容を整理して伝えることの大切さ
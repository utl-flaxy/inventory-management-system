# CLAUDE.md

このファイルは、Claude Code がこのリポジトリで安全に作業するためのガイドです。
記載内容はリポジトリから確認できる事実に基づきます。確認できなかった点は「未確認」と明記しています。

## プロジェクト概要

在庫管理・注文管理のバックエンド REST API（Spring Boot）。
README のテーマは「データ整合性を担保した注文処理設計」。

- 商品 CRUD・検索（ページング）— `/products`
- 注文作成・一覧・詳細・ステータス変更・キャンセル・売上集計・CSV 出力 — `/orders`
- ユーザー登録・ログイン（JWT 発行）— `/auth`
- リモート: `https://github.com/utl-flaxy/inventory-management-system.git`

## 技術スタック

| 分類 | 内容（出典） |
|---|---|
| 言語 | Java 17（`pom.xml` の `java.version`、CI の setup-java） |
| フレームワーク | Spring Boot 3.5.12（`pom.xml`） |
| Web / 検証 | spring-boot-starter-web, spring-boot-starter-validation |
| 永続化 | Spring Data JPA / MariaDB（`mariadb-java-client`）。H2 も runtime 依存にあり |
| セキュリティ | Spring Security + JWT（jjwt 0.12.7） |
| API ドキュメント | springdoc-openapi 2.8.8（`/swagger-ui.html`） |
| その他 | Lombok |
| テスト | spring-boot-starter-test（JUnit 5, Mockito, MockMvc） |
| ビルド | Maven Wrapper（`mvnw` / `mvnw.cmd`） |
| インフラ | Docker / Docker Compose, AWS EC2, Terraform（AWS provider `~> 6.0`, `ap-northeast-1`） |
| CI/CD | GitHub Actions |

## ディレクトリ構成

```text
.
├── src/main/java/com/example/demo/
│   ├── config/       OpenApiConfig（Swagger の Bearer 認証設定）
│   ├── controller/   AuthController, OrderController, ProductController
│   ├── dto/          リクエスト／レスポンス DTO
│   ├── entity/       Order, OrderItem, OrderStatus, Product, User
│   ├── exception/    GlobalExceptionHandler, OutOfStockException
│   ├── repository/   Spring Data JPA リポジトリ
│   ├── security/     SecurityConfig, JwtAuthenticationFilter, JwtUtil, CustomUserDetailsService
│   └── service/      AuthService, OrderService, ProductService
├── src/main/resources/application.properties
├── src/test/java/com/example/demo/
│   ├── controller/   OrderControllerTest, ProductControllerTest
│   └── service/      OrderServiceTest
├── terraform/        EC2・Security Group・Key Pair・user_data
├── .github/workflows/ ci.yml, deploy.yml
├── docs/images/      README 用スクリーンショット
├── Dockerfile, docker-compose.yml, pom.xml, README.md
```

`src/test/resources` はありません。

## アーキテクチャ

Controller → Service → Repository → Entity のレイヤード構成です。

- **Controller**: 薄く保ち、Service に処理を任せる。入力検証は `@Valid` と DTO の Bean Validation で行う（`ProductController`、`OrderController#create`）。
- **Service**: 業務ロジックを書く。更新系のうち `OrderService` の作成・ステータス変更・キャンセルは `@Transactional`。
- **Repository**: `JpaRepository` を継承。独自クエリは `@Query`（`OrderRepository#getTotalSales`）か、メソッド名で導出（`findByNameContaining`）。
- **Entity**: 注文時の単価を `OrderItem.price` に保存する（README の「注文時価格を保持」）。
- **レスポンス**: Product は DTO（`ProductResponse`）を返す。Order と User（`/auth/register`）は Entity をそのまま返している。
- **例外処理**: `GlobalExceptionHandler`（`@RestControllerAdvice`）でまとめて扱う。
  - `OutOfStockException` → 400 `{"code":"OUT_OF_STOCK","message":...}`
  - `MethodArgumentNotValidException` → 400 `{"<フィールド名>":"<メッセージ>"}`
  - その他の `Exception` → 500 `{"code":"SYSTEM_ERROR","message":...}`
  - 「商品が存在しません」などは `RuntimeException` で投げているため、現状は 500 になる。
- **認証・認可**:
  - JWT はステートレス。`/auth/**`、Swagger、`/h2-console/**` 以外は認証が必要。
  - `@EnableMethodSecurity` を有効にし、商品の登録・更新・削除は `@PreAuthorize("hasRole('ADMIN')")`。
  - `/auth/register` は常に `ROLE_USER` で登録する。ADMIN を作る手段はコード上にない。
- **注文ステータス**: `PENDING` / `SHIPPED` / `COMPLETED` / `CANCELLED`。キャンセル時は在庫を戻す。`SHIPPED` と `COMPLETED` はキャンセルできない。

## コーディング規約（既存コードから読み取れるもの）

- パッケージ: `com.example.demo.<layer>`。新しいクラスは既存のレイヤーのパッケージに置く。
- DI: Lombok の `@RequiredArgsConstructor` と `private final` フィールドでコンストラクタ注入。
- Lombok:
  - DTO は `@Data`、または `@Getter` / `@Setter`。
  - Entity は `@Getter @Setter @NoArgsConstructor @AllArgsConstructor`、必要なら `@Builder`。
- コメント: 日本語の短い1行コメントをメソッドや処理ブロックの前に置く（例: `// 注文作成`、`// 在庫チェック`）。
- バリデーションのメッセージは日本語。「〜は必須です」「〜は1以上を入力してください」のように書く。
- インデントはスペース4つ。メソッド本体の先頭に空行を1行入れるスタイルが多い。
- import はアルファベット順。`org.springframework.web.bind.annotation.*` はワイルドカードを使っている。
- 既存コードの `System.out.println` によるデバッグ出力（`AuthService`、`JwtAuthenticationFilter`）は、新しいコードではまねしない。整理するかどうかは別タスクとして扱う。

## テスト方針

- **Service の単体テスト**:
  - `@ExtendWith(MockitoExtension.class)` + `@Mock` / `@InjectMocks` でリポジトリをモックする。
  - `// Arrange` / `// Act` / `// Assert` のコメントを書く。
  - 異常系では `verify(..., never()).save(any())` で、副作用が起きていないことを確認する。
- **Controller のテスト**:
  - `@WebMvcTest(XxxController.class)` + `@AutoConfigureMockMvc(addFilters = false)` を使う。
  - `@MockBean` で Service、`JwtAuthenticationFilter`、`JwtUtil`、`UserDetailsService` をモックする（既存テストに合わせて `@MockBean` を使う）。
  - `addFilters = false` なので、認証・認可の動き（401/403、`@PreAuthorize`）は検証していない。
  - バリデーションエラーのテストでは、Service が呼ばれないことを `verify(service, never())` で確認する。
- テストメソッド名は日本語（例: `注文成功`、`商品登録バリデーションエラー`）。
- `@SpringBootTest` や DB に接続するテストはない。そのため `./mvnw test` は DB なしで動く。
- 振る舞いを変えたら、対応する Service テストと Controller テストを追加・更新する。

## よく使うコマンド

```bash
./mvnw test                 # 全テスト（CI と同じ）
./mvnw test -Dtest=OrderControllerTest   # 特定のテストクラス
./mvnw clean package -DskipTests         # jar 作成（Dockerfile は target/demo-0.0.1-SNAPSHOT.jar をコピー）
./mvnw spring-boot:run      # ローカル起動（MariaDB への接続が必要）
docker compose up -d        # MariaDB + アプリを起動（先に jar の作成が必要）
docker compose down
```

Windows では `mvnw.cmd` も使えます。

- **ローカル DB の注意**: `application.properties` の接続先は `127.0.0.1:3307` です。一方、`docker-compose.yml` が公開している MariaDB のポートは `3306` です。ローカルでどう起動するのが想定なのか（別の DB を 3307 で動かしているのか）は未確認です。
- H2 は依存関係と `/h2-console` の許可設定がありますが、`application.properties` には H2 の設定がありません。H2 をどう使うのかは未確認です。

## 変更時に注意すべきポイント

- **`main` への push は本番デプロイにつながる。**
  - `ci.yml` は `main` への push と PR で `./mvnw test` を実行する。
  - `deploy.yml` は CI が成功すると（`workflow_run`）、EC2 に SSH して `git reset --hard origin/main` → `./mvnw clean package -DskipTests` → `docker compose down` → `docker build` → `docker compose up -d` を実行する。
  - `workflow_run` のトリガーは CI がどのイベントで動いたかを区別しないため、PR の CI が成功したときにも `main` のデプロイが走る可能性がある。
  - **push や PR の作成は、ユーザーから明示的に指示されたときだけ行う。**
- **DB スキーマ**:
  - `spring.jpa.hibernate.ddl-auto=update` なので、Entity の変更は起動時にそのままスキーマへ反映される。マイグレーションツール（Flyway / Liquibase）はない。
  - カラムの削除・名前変更・型変更は既存データに影響するので、事前に確認する。
- **API の互換性**:
  - Order 系はレスポンスで Entity を直接返している。Entity のフィールドを変えると API のレスポンスも変わる。
  - `Order.orderItems` は `@JsonManagedReference`、`OrderItem.order` は `@JsonBackReference` で循環参照を避けている。
- **在庫と金額**:
  - 注文作成とキャンセルは在庫を増減する。`@Transactional` の範囲を崩さない。
  - 金額は `Integer` 同士の掛け算で計算している（オーバーフロー対策はない）。
  - 排他制御（ロック）は未導入（README の「今後の改善」に記載）。
- **エラーレスポンスの形式**:
  - 新しい例外は `GlobalExceptionHandler` に追加し、既存の `{"code","message"}` 形式に合わせる。
  - README のエラー例（`{"message": ...}` だけ）は、実装（`code` も含む）と食い違っている。
- **README**: 1400行を超える、採用担当者向けのポートフォリオ文書。API の挙動を変えたら、該当する節（API 一覧、レスポンス例、エラーハンドリング）を更新するかどうか、ユーザーに確認する。
- **コミット**: 直近のコミットは Conventional Commits 形式（例: `fix(order): ...`）。それ以前の履歴は自由形式。コミットは指示されたときだけ行う。

## 変更禁止・慎重に扱うべきファイル

**秘密情報。値を読み上げたり、ログ・コミット・外部サービスに出したりしない。**

- `terraform/terraform.tfvars`、`terraform/terraform.tfstate`、`terraform/terraform.tfstate.backup`
  - ローカルにはあるが、`.gitignore` の対象なので Git の管理外。state には AWS リソースの情報が含まれる。編集もコミットもしない。
- `*.pem` や SSH 鍵（`.gitignore` の対象）。
- `src/main/java/com/example/demo/security/JwtUtil.java`
  - JWT の署名鍵がソースに直書きされている（コメントに「本番では application.properties に切り出す」とある）。値を変えると、発行済みのトークンがすべて無効になる。
- `src/main/resources/application.properties`、`docker-compose.yml`
  - DB の認証情報が平文で書かれていて、Git の管理下にある。値を別の場所へ広めない。

**変更すると本番や CI に直接影響するもの。変更前にユーザーに確認する。**

- `.github/workflows/deploy.yml`、`.github/workflows/ci.yml`
- `Dockerfile`（jar 名 `demo-0.0.1-SNAPSHOT.jar` に依存。`pom.xml` の `artifactId` / `version` を変えると壊れる）
- `docker-compose.yml`（本番の EC2 でもこれを使う）
- `terraform/*.tf`、`terraform/userdata.sh`
  - `terraform apply` / `destroy` は実行しない。`plan` もユーザーの指示があるときだけ行う。
  - Security Group は 22 番と 8080 番を `0.0.0.0/0` に開けている。
- `security/SecurityConfig.java`（認可ルール全体）
- `pom.xml`（依存バージョン。Spring Boot の parent は 3.5.12）

**生成物・ローカル専用。編集もコミットもしない。**

- `target/`、`data/`（H2 のファイル `testdb.mv.db`）、`log.txt`、`HELP.md`、`.idea/`、`terraform/.terraform/`

## ローカル環境の注意（この作業 PC で確認したこと）

- リポジトリの所有者と作業ユーザーが違うため、git コマンドは `git -c safe.directory=C:/Users/y03iw/Desktop/demo <cmd>` の形で実行する。グローバル設定は変えない。
- `core.autocrlf=true`。`.gitattributes` で `mvnw` は LF、`*.cmd` は CRLF に固定している。
- ローカルの JDK は 17 ではない可能性がある（Java 26 で実行された記録あり）。CI とビルドの対象は Java 17。

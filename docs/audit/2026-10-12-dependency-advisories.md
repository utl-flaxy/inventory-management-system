# Issue #80 — 依存アドバイザリ是正台帳（2026-10-12）

> **履歴snapshot:** 以下の137 components / 66 advisoryは導入時の値と根拠を保持した台帳。
> 現行Boot4 mainのOSV169/0、Trivy OS49とは対象・時点が異なる。[現在値と是正PR](../release-status.md)を参照し、旧JSON/識別子/根拠は書き換えない。

調査main: `8ba4e9a9e833565edef8daa379c9f843fe2161f7`。OSV Scanner 2.6.0 / CycloneDX Maven Plugin 2.9.3。
解決済み137コンポーネント、影響ライブラリ17、重複除去済み66アドバイザリ（Critical 8 / High 22 / Moderate 28 / Low 8）。
全17ライブラリのproduction JAR格納を確認。66件は17ライブラリへのfindingであり、66ライブラリではない。直接依存はMariaDB JDBC、他16は推移的依存。
依存スキャンは監査のみ。既知findingを抑制せず、スキャン実行成功と脆弱性ゼロを区別する。更新・無期限受容は本PRに含めない。

[全66件の機械可読台帳](2026-10-12-dependency-advisories.json)にはCVSS vector、現在版に対応するOSV affected range、Maven依存経路、scope、根拠URL、未検証事項を保存。
件数・版・scopeは既存OSV/SBOM/Maven dependency tree/listとproduction JARから照合。全66件のGitHub Reviewed advisoryを2026-10-12に取得。
以下の到達性はソース調査であり、本番構成や攻撃再現の結果ではない。未使用に見える機能でもfindingを消さない。

## 是正方針と期限

- Critical/High 30件: P1、2026-10-14までに到達条件/修正版を確認、2026-10-26を是正目標とする。production exploitを確認した時点で独立緊急Issueへ昇格し48時間以内に修正または検証済み防御を用意する。
- Moderate 28件: P2、2026-11-11を是正目標。Low 8件: P3、2026-12-11を是正目標。
- ownerはrepository maintainer、割当/期限の合意は未確認。上記は優先度に応じた提案SLAであり、承認済みrisk acceptanceではない。期限に間に合わない場合は根拠・新期限・責任者をIssueへ記録し再承認する。
- productionコードからCritical/Highの悪用成立まで確認できたものは現時点0。JSON数値coercion、メトリクス、property cacheは追跡を要し、安全と断定しない。ModerateのPageable Sort引数解決はcontrollerに存在し、service allowlistだけで解析前の保護を断定しない。
- 修正版は検出版を含むrangeから抽出した候補。Spring MVC Critical 2件はこのsnapshotの6.2系列でfixed entryがなく、7.0.9を無関係に導入しない。Tomcatの10.1.58はrelease vote不成立のため実取得候補は10.1.59以降（[Apache公式](https://tomcat.apache.org/security-10.html)）。
- Boot BOMの互換更新または限定overrideを独立PRで評価。Flyway/MariaDB互換性の#69判断を勝手に変更しない。

## ライブラリ別件数

|group:artifact:version|依存|scope / production JAR|advisories|
|---|---|---|---:|
|`org.apache.commons:commons-lang3:3.17.0`|transitive|compile / 確認済み|1|
|`com.fasterxml.jackson.core:jackson-core:2.19.4`|transitive|compile / 確認済み|4|
|`com.fasterxml.jackson.core:jackson-databind:2.19.4`|transitive|compile / 確認済み|11|
|`org.apache.logging.log4j:log4j-api:2.24.3`|transitive|compile / 確認済み|1|
|`ch.qos.logback:logback-core:1.5.32`|transitive|compile / 確認済み|2|
|`org.mariadb.jdbc:mariadb-java-client:3.5.7`|direct|runtime / 確認済み|4|
|`io.micrometer:micrometer-core:1.15.10`|transitive|compile / 確認済み|2|
|`org.springframework.boot:spring-boot:3.5.12`|transitive|compile / 確認済み|1|
|`org.springframework.boot:spring-boot-autoconfigure:3.5.12`|transitive|compile / 確認済み|1|
|`org.springframework:spring-core:6.2.17`|transitive|compile / 確認済み|1|
|`org.springframework.data:spring-data-commons:3.5.10`|transitive|compile / 確認済み|4|
|`org.springframework:spring-expression:6.2.17`|transitive|compile / 確認済み|3|
|`org.springframework.security:spring-security-core:6.5.9`|transitive|compile / 確認済み|2|
|`org.springframework.security:spring-security-web:6.5.9`|transitive|compile / 確認済み|2|
|`org.springframework:spring-web:6.2.17`|transitive|compile / 確認済み|1|
|`org.springframework:spring-webmvc:6.2.17`|transitive|compile / 確認済み|11|
|`org.apache.tomcat.embed:tomcat-embed-core:10.1.52`|transitive|compile / 確認済み|15|

## 全finding（severity順）

### GHSA-5m62-pw8w-7w9f — CRITICAL / CVSS v3 9.1

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-43515。修正版候補: 10.1.55。
条件: Multiple container security constraints share an extension pattern with different HTTP-method restrictions. 到達性: Authentication/authorization use Spring Security JWT; no servlet declarative extension-pattern constraints found.
根拠: [GHSA](https://github.com/advisories/GHSA-5m62-pw8w-7w9f) / [OSV](https://osv.dev/vulnerability/GHSA-5m62-pw8w-7w9f)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: Externally supplied web.xml/container constraints not inspected.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-9xv2-5v5q-p794 — CRITICAL / CVSS v3 9.8

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-65905。修正版候補: 10.1.58。
条件: Tomcat DIGEST authentication permits replay or unknown-user authentication. 到達性: JWT/stateless authentication configured; HTTP Basic/form login disabled and no Tomcat DIGEST realm found.
根拠: [GHSA](https://github.com/advisories/GHSA-9xv2-5v5q-p794) / [OSV](https://osv.dev/vulnerability/GHSA-9xv2-5v5q-p794)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External container authentication overrides not inspected.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-gcx9-497g-6cp6 — CRITICAL / CVSS v3 9.1

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-65182。修正版候補: 10.1.58。
条件: Ordered servlet container security constraints permit a narrower path bypass. 到達性: Spring Security request matcher authorization configured; no matching Tomcat declarative constraints found.
根拠: [GHSA](https://github.com/advisories/GHSA-gcx9-497g-6cp6) / [OSV](https://osv.dev/vulnerability/GHSA-gcx9-497g-6cp6)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External web.xml/Realm setup unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-h3x4-894j-xpx5 — CRITICAL / CVSS v3 9.1

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-68525。修正版候補: 10.1.58。
条件: Tomcat FORM authentication redirects bypass method-specific constraints. 到達性: Spring formLogin disabled; no Tomcat FORM login configuration found.
根拠: [GHSA](https://github.com/advisories/GHSA-h3x4-894j-xpx5) / [OSV](https://osv.dev/vulnerability/GHSA-h3x4-894j-xpx5)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External Tomcat auth constraints unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-h6fc-48rj-7qqh — CRITICAL / CVSS v3 9.8

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-43512。修正版候補: 10.1.55。
条件: Tomcat DIGEST authentication permits replay or unknown-user authentication. 到達性: JWT/stateless authentication configured; HTTP Basic/form login disabled and no Tomcat DIGEST realm found.
根拠: [GHSA](https://github.com/advisories/GHSA-h6fc-48rj-7qqh) / [OSV](https://osv.dev/vulnerability/GHSA-h6fc-48rj-7qqh)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External container authentication overrides not inspected.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-j9f9-w8pj-32f8 — CRITICAL / CVSS v3 9.8

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-47890。修正版候補: 検出rangeにfixed entryなし。
条件: SSE rendering of view fragments permits stream corruption. 到達性: No SseEmitter, view fragments or SSE endpoint found in application sources.
根拠: [GHSA](https://github.com/advisories/GHSA-j9f9-w8pj-32f8) / [OSV](https://osv.dev/vulnerability/GHSA-j9f9-w8pj-32f8)。ソース: `src/main/java/com/example/demo/controller/`。未検証: Third-party or future SSE routes unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-pc63-qcmh-9cmg — CRITICAL / CVSS v3 9.8

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-47884。修正版候補: 検出rangeにfixed entryなし。
条件: XsltView with implicit view name under a wildcard mapping allows SSRF/RCE. 到達性: No XsltView, view resolver or wildcard view-rendering controller found; current routes are RestController JSON.
根拠: [GHSA](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) / [OSV](https://osv.dev/vulnerability/GHSA-pc63-qcmh-9cmg)。ソース: `src/main/java/com/example/demo/controller/`。未検証: External view configuration unverified; do not assume a compatible 6.2 patch exists.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-r29c-68gh-xp6x — CRITICAL / CVSS v3 9.8

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-41293。修正版候補: 10.1.55。
条件: Invalid HTTP/2 headers reach application through Tomcat without expected validation. 到達性: No server.http2 enablement or custom HTTP/2 connector found. Caddy's client-side HTTP/2 does not prove Tomcat upstream HTTP/2.
根拠: [GHSA](https://github.com/advisories/GHSA-r29c-68gh-xp6x) / [OSV](https://osv.dev/vulnerability/GHSA-r29c-68gh-xp6x)。ソース: `src/main/resources/application-prod.properties; terraform/caddy/Caddyfile.tmpl`。未検証: Live upstream connector protocol/header normalization unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-3chg-m5w7-qfv5 — HIGH / CVSS v3 7.1

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41845。修正版候補: 6.2.19。
条件: JavaScriptUtils.javaScriptEscape output is inserted into browser JavaScript. 到達性: No JavaScriptUtils or server-rendered script/view found; API controllers return JSON DTOs.
根拠: [GHSA](https://github.com/advisories/GHSA-3chg-m5w7-qfv5) / [OSV](https://osv.dev/vulnerability/GHSA-3chg-m5w7-qfv5)。ソース: `src/main/java/com/example/demo/controller/`。未検証: External frontend use of this helper unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-5mp6-jrq3-r938 — HIGH / CVSS v3 7.5

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-43513。修正版候補: 10.1.55。
条件: Case variants bypass username lockout when Tomcat LockOutRealm is configured. 到達性: Application uses its own AuthService throttling/JWT, no Tomcat LockOutRealm configuration found.
根拠: [GHSA](https://github.com/advisories/GHSA-5mp6-jrq3-r938) / [OSV](https://osv.dev/vulnerability/GHSA-5mp6-jrq3-r938)。ソース: `src/main/java/com/example/demo/service/AuthService.java; src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External container realms not inspected.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-69cc-cv78-qc8g — HIGH / CVSS v3 7.5

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-29129。修正版候補: 10.1.53。
条件: Tomcat TLS connector uses configured cipher preference ordering. 到達性: Deployment contract uses Caddy TLS termination; no application Tomcat TLS connector configuration found.
根拠: [GHSA](https://github.com/advisories/GHSA-69cc-cv78-qc8g) / [OSV](https://osv.dev/vulnerability/GHSA-69cc-cv78-qc8g)。ソース: `terraform/caddy/Caddyfile.tmpl; src/main/resources/application-prod.properties`。未検証: Actual TLS/proxy topology and external connector overrides not inspected.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-7hhh-6rmp-j9qf — HIGH / CVSS v3 7.5

`com.fasterxml.jackson.core:jackson-core:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-89425。修正版候補: 2.21.7。
条件: Malformed long token parsed through JsonFactory.createParser(DataInput). 到達性: Application accepts JSON through MVC InputStream; no application DataInput parser call found. Vulnerable parser entry not established.
根拠: [GHSA](https://github.com/advisories/GHSA-7hhh-6rmp-j9qf) / [OSV](https://osv.dev/vulnerability/GHSA-7hhh-6rmp-j9qf)。ソース: `src/main/java/com/example/demo/controller/`。未検証: Third-party DataInput usage and deployed request-size limits not traced.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-88fw-v6x4-3f58 — HIGH / CVSS v3 7.5

`org.springframework.data:spring-data-commons:3.5.10` · transitive · compile · production JAR確認済み · CVE-2026-41695。修正版候補: 3.5.12。
条件: Unbounded mapping/property cache retains attacker-supplied property paths. 到達性: Product and order services normalize allowed sort properties before repository use. Parsing happens before normalization; exact mapping-cache reachability remains unverified.
根拠: [GHSA](https://github.com/advisories/GHSA-88fw-v6x4-3f58) / [OSV](https://osv.dev/vulnerability/GHSA-88fw-v6x4-3f58)。ソース: `src/main/java/com/example/demo/service/ProductPageableNormalizer.java; src/main/java/com/example/demo/service/OrderPageableNormalizer.java`。未検証: Trace argument resolution and downstream mapping cache under unique inputs; no heap-exhaustion reproduction.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-9fw2-h3hf-293r — HIGH / CVSS v3 7.5

`org.springframework.data:spring-data-commons:3.5.10` · transitive · compile · production JAR確認済み · CVE-2026-41716。修正版候補: 3.5.12。
条件: Unbounded mapping/property cache retains attacker-supplied property paths. 到達性: Product and order services normalize allowed sort properties before repository use. Parsing happens before normalization; exact mapping-cache reachability remains unverified.
根拠: [GHSA](https://github.com/advisories/GHSA-9fw2-h3hf-293r) / [OSV](https://osv.dev/vulnerability/GHSA-9fw2-h3hf-293r)。ソース: `src/main/java/com/example/demo/service/ProductPageableNormalizer.java; src/main/java/com/example/demo/service/OrderPageableNormalizer.java`。未検証: Trace argument resolution and downstream mapping cache under unique inputs; no heap-exhaustion reproduction.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-cxp5-3px4-pw24 — HIGH / CVSS v3 7.5

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-91777。修正版候補: 2.21.7。
条件: JsonIdentityInfo unresolved references completed in reverse order cause quadratic work. 到達性: No JsonIdentityInfo found on application request types; controllers deserialize DTOs.
根拠: [GHSA](https://github.com/advisories/GHSA-cxp5-3px4-pw24) / [OSV](https://osv.dev/vulnerability/GHSA-cxp5-3px4-pw24)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Dependency-owned models and mapper customizations not exhaustively traced.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-fv25-8xcx-gqjc — HIGH / CVSS v3 7.3

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-42498。修正版候補: 10.1.55。
条件: Authenticated Tomcat WebSocket client follows cross-host redirect and sends credentials. 到達性: No WebSocket client or connection code found in application sources.
根拠: [GHSA](https://github.com/advisories/GHSA-fv25-8xcx-gqjc) / [OSV](https://osv.dev/vulnerability/GHSA-fv25-8xcx-gqjc)。ソース: `src/main/java/; pom.xml`。未検証: Third-party WebSocket client calls unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-g3pr-3p32-fp23 — HIGH / CVSS v3 7.5

`io.micrometer:micrometer-core:1.15.10` · transitive · compile · production JAR確認済み · CVE-2026-40984。修正版候補: 1.15.12。
条件: Arbitrary HTTP methods become unbounded metric tag values in affected Micrometer instrumentations. 到達性: Actuator/Micrometer are packaged, but upstream patch targets Micrometer servlet/Jersey/Jetty tag helpers. This app uses Spring MVC observation and Spring Security firewall; an affected helper call from a live request is not established.
根拠: [GHSA](https://github.com/advisories/GHSA-g3pr-3p32-fp23) / [OSV](https://osv.dev/vulnerability/GHSA-g3pr-3p32-fp23)。ソース: `pom.xml; src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: Verify Spring observation method normalization, firewall ordering and runtime meter growth; no OOM reproduced.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-gx5v-xp9w-j4cg — HIGH / CVSS v3 7.5

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-41284。修正版候補: 10.1.55。
条件: Unauthenticated WebDAV LOCK/PROPFIND body is read without a bound. 到達性: No WebdavServlet registration or WebDAV endpoints found.
根拠: [GHSA](https://github.com/advisories/GHSA-gx5v-xp9w-j4cg) / [OSV](https://osv.dev/vulnerability/GHSA-gx5v-xp9w-j4cg)。ソース: `src/main/java/`。未検証: External servlet registration and body limits unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-j3rv-43j4-c7qm — HIGH / CVSS v3 8.1

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-54512。修正版候補: 2.21.4。
条件: Polymorphic typing permits raw generic container while rejecting its generic component type incorrectly. 到達性: No enableDefaultTyping, activateDefaultTyping or JsonTypeInfo found in application sources.
根拠: [GHSA](https://github.com/advisories/GHSA-j3rv-43j4-c7qm) / [OSV](https://osv.dev/vulnerability/GHSA-j3rv-43j4-c7qm)。ソース: `src/main/java/`。未検証: All third-party mapper configurations and gadget prerequisites unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-p6pp-m3f8-5c89 — HIGH / CVSS v3 7.5

`com.fasterxml.jackson.core:jackson-core:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-89407。修正版候補: 2.21.7。
条件: Attacker string reaches NumberInput.looksLikeValidNumber; quadratic regex backtracking. 到達性: Numeric DTO inputs exist (ProductRequest.price/stock), but the precise coercion path into this regex is not proven for Long/Integer. No application DoS reproduced.
根拠: [GHSA](https://github.com/advisories/GHSA-p6pp-m3f8-5c89) / [OSV](https://osv.dev/vulnerability/GHSA-p6pp-m3f8-5c89)。ソース: `src/main/java/com/example/demo/dto/ProductRequest.java`。未検証: Actual coercion call stack, body limits and bounded runtime reproduction required.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-q4xh-88c3-wmh7 — HIGH / CVSS v3 7.5

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-68497。修正版候補: 2.21.6。
条件: Unbounded XML-Schema duration/calendar string converted to BigInteger/BigDecimal. 到達性: No XMLGregorianCalendar or javax.xml.datatype.Duration request field found.
根拠: [GHSA](https://github.com/advisories/GHSA-q4xh-88c3-wmh7) / [OSV](https://osv.dev/vulnerability/GHSA-q4xh-88c3-wmh7)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Indirect third-party binding not exhaustively traced.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-r5w3-xv2f-j59q — HIGH / CVSS v3 7.5

`org.springframework:spring-expression:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41850。修正版候補: 6.2.19。
条件: User-controlled SpEL expression causes excessive evaluation/cache growth or unsafe invocation. 到達性: Method security uses constant annotations; no request-controlled ExpressionParser invocation found.
根拠: [GHSA](https://github.com/advisories/GHSA-r5w3-xv2f-j59q) / [OSV](https://osv.dev/vulnerability/GHSA-r5w3-xv2f-j59q)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java; src/main/java/com/example/demo/controller/`。未検証: Third-party expression evaluation and deployed customization unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-r7wm-3cxj-wff9 — HIGH / CVSS v3 0.0

`com.fasterxml.jackson.core:jackson-core:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-68494。修正版候補: 2.21.4。
条件: Chunked digits supplied to Jackson nonblocking async parser bypass numeric length constraints. 到達性: Servlet MVC uses synchronous JSON input; no nonblocking parser creation found in application sources.
根拠: [GHSA](https://github.com/advisories/GHSA-r7wm-3cxj-wff9) / [OSV](https://osv.dev/vulnerability/GHSA-r7wm-3cxj-wff9)。ソース: `src/main/java/com/example/demo/controller/`。未検証: Third-party async parsing not traced; do not remove findings.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-rmj7-2vxq-3g9f — HIGH / CVSS v3 8.1

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-54513。修正版候補: 2.21.4。
条件: Polymorphic validator configured with allowIfSubTypeIsArray permits unapproved component classes. 到達性: No array subtype allowlist or polymorphic typing configuration found in application sources.
根拠: [GHSA](https://github.com/advisories/GHSA-rmj7-2vxq-3g9f) / [OSV](https://osv.dev/vulnerability/GHSA-rmj7-2vxq-3g9f)。ソース: `src/main/java/`。未検証: Dependency mapper configuration and gadget chain unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-rv64-5gf8-9qq8 — HIGH / CVSS v3 7.5

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-34483。修正版候補: 10.1.54。
条件: JsonAccessLogValve emits improperly escaped attacker-controlled values. 到達性: Application uses Spring structured console logging; no JsonAccessLogValve configured in sources.
根拠: [GHSA](https://github.com/advisories/GHSA-rv64-5gf8-9qq8) / [OSV](https://osv.dev/vulnerability/GHSA-rv64-5gf8-9qq8)。ソース: `src/main/resources/application-prod.properties; src/main/java/`。未検証: External container access-log valve configuration unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-w737-wx49-qj23 — HIGH / CVSS v3 7.5

`io.micrometer:micrometer-core:1.15.10` · transitive · compile · production JAR確認済み · CVE-2026-40983。修正版候補: 1.15.12。
条件: Crafted gRPC method names reach Micrometer gRPC server instrumentation. 到達性: No gRPC dependency/server/application call found.
根拠: [GHSA](https://github.com/advisories/GHSA-w737-wx49-qj23) / [OSV](https://osv.dev/vulnerability/GHSA-w737-wx49-qj23)。ソース: `pom.xml; src/main/java/`。未検証: External runtime additions not inspected.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-wv8q-qhhj-9h54 — HIGH / CVSS v3 7.5

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-91776。修正版候補: 2.21.7。
条件: JsonTypeInfo Id.NAME plus defaultImpl retains arbitrary unknown type IDs indefinitely. 到達性: No JsonTypeInfo/defaultImpl request model found in application sources.
根拠: [GHSA](https://github.com/advisories/GHSA-wv8q-qhhj-9h54) / [OSV](https://osv.dev/vulnerability/GHSA-wv8q-qhhj-9h54)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Third-party models and long-lived mapper caches unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-wwpq-f5c3-7hvx — HIGH / CVSS v3 7.0

`org.springframework.boot:spring-boot:3.5.12` · transitive · compile · production JAR確認済み · CVE-2026-40973。修正版候補: 3.5.14。
条件: Local attacker controls ApplicationTemp directory; persistent servlet sessions across restarts raise hijacking/RCE risk. 到達性: JWT security explicitly sets STATELESS; no persistent servlet-session configuration found. Temp-directory ownership exposure remains conditional and relates to nonroot work #76.
根拠: [GHSA](https://github.com/advisories/GHSA-wwpq-f5c3-7hvx) / [OSV](https://osv.dev/vulnerability/GHSA-wwpq-f5c3-7hvx)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java; Dockerfile`。未検証: Deployment user, temp ownership, external session persistence settings not inspected.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-x23c-287f-qqv5 — HIGH / CVSS v3 7.5

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41842。修正版候補: 6.2.19。
条件: Crafted requests traverse affected versioned/static-resource resolution or cache behavior. 到達性: Swagger/resource documentation routes are disabled in prod by UnavailableEndpointsFilter; no application VersionResourceResolver found. Framework default/static-resource path remains to be traced.
根拠: [GHSA](https://github.com/advisories/GHSA-x23c-287f-qqv5) / [OSV](https://osv.dev/vulnerability/GHSA-x23c-287f-qqv5)。ソース: `src/main/java/com/example/demo/security/UnavailableEndpointsFilter.java; src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: Default Boot resource mappings, proxy normalization and runtime cache effects unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-x4m4-345f-5h5g — HIGH / CVSS v3 7.5

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-34487。修正版候補: 10.1.54。
条件: Tomcat cluster cloud membership logs Kubernetes bearer token. 到達性: No Tomcat clustering/cloud membership/Kubernetes deployment configured in repository.
根拠: [GHSA](https://github.com/advisories/GHSA-x4m4-345f-5h5g) / [OSV](https://osv.dev/vulnerability/GHSA-x4m4-345f-5h5g)。ソース: `terraform/; docker-compose.yml; src/main/java/`。未検証: External cluster configuration unverified.
対応: P1、triage 2026-10-14、是正目標 2026-10-26、https://github.com/utl-flaxy/inventory-management-system/issues/86。互換更新と条件別回帰を別PRで検証。

### GHSA-293q-567p-wmwq — MODERATE / CVSS v3 6.8

`org.springframework.security:spring-security-web:6.5.9` · transitive · compile · production JAR確認済み · CVE-2026-47838。修正版候補: 6.5.11。
条件: X.509 client-certificate authentication satisfies advisory-specific impersonation prerequisites. 到達性: Application JWT authentication; no x509() configuration found.
根拠: [GHSA](https://github.com/advisories/GHSA-293q-567p-wmwq) / [OSV](https://osv.dev/vulnerability/GHSA-293q-567p-wmwq)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External certificate authentication/proxy behavior unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-3pjw-73gf-8qr5 — MODERATE / CVSS v3 6.5

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-59888。修正版候補: 2.21.4。
条件: Record naming/ignore annotations, case-insensitive binding, or unsafe polymorphic base type matches advisory-specific prerequisite. 到達性: No JsonIgnore/JsonIgnoreProperties, naming strategy or polymorphic typing configured on application request DTOs.
根拠: [GHSA](https://github.com/advisories/GHSA-3pjw-73gf-8qr5) / [OSV](https://osv.dev/vulnerability/GHSA-3pjw-73gf-8qr5)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Third-party mapper/models and exact annotation combinations unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-5jmj-h7xm-6q6v — MODERATE / CVSS v3 5.3

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-54515。修正版候補: 2.21.5。
条件: Record naming/ignore annotations, case-insensitive binding, or unsafe polymorphic base type matches advisory-specific prerequisite. 到達性: No JsonIgnore/JsonIgnoreProperties, naming strategy or polymorphic typing configured on application request DTOs.
根拠: [GHSA](https://github.com/advisories/GHSA-5jmj-h7xm-6q6v) / [OSV](https://osv.dev/vulnerability/GHSA-5jmj-h7xm-6q6v)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Third-party mapper/models and exact annotation combinations unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-5m4m-73w9-8433 — MODERATE / CVSS v3 5.9

`org.springframework.data:spring-data-commons:3.5.10` · transitive · compile · production JAR確認済み · CVE-2026-41721。修正版候補: 3.5.12。
条件: Untrusted data triggers excessive projection-binding allocation. 到達性: No exposed Spring Data REST projection binding endpoint found; repositories use typed API DTOs.
根拠: [GHSA](https://github.com/advisories/GHSA-5m4m-73w9-8433) / [OSV](https://osv.dev/vulnerability/GHSA-5m4m-73w9-8433)。ソース: `pom.xml; src/main/java/com/example/demo/controller/`。未検証: Third-party projection binders unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-5vpf-xvv7-c8vh — MODERATE / CVSS v3 5.9

`org.springframework.data:spring-data-commons:3.5.10` · transitive · compile · production JAR確認済み · CVE-2026-41711。修正版候補: 3.5.12。
条件: Crafted sort strings trigger recursive parsing/StackOverflowException before downstream normalization. 到達性: Pageable is bound from request parameters on ProductController; sort parsing path is reachable before the service allowlist. Specific StackOverflow exploit is not reproduced.
根拠: [GHSA](https://github.com/advisories/GHSA-5vpf-xvv7-c8vh) / [OSV](https://osv.dev/vulnerability/GHSA-5vpf-xvv7-c8vh)。ソース: `src/main/java/com/example/demo/controller/ProductController.java`。未検証: Bounded local regression and exact vulnerable parser stack required.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-6p4f-wcwh-5vvm — MODERATE / CVSS v3 5.3

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-22745。修正版候補: 6.2.18。
条件: Crafted requests traverse affected versioned/static-resource resolution or cache behavior. 到達性: Swagger/resource documentation routes are disabled in prod by UnavailableEndpointsFilter; no application VersionResourceResolver found. Framework default/static-resource path remains to be traced.
根拠: [GHSA](https://github.com/advisories/GHSA-6p4f-wcwh-5vvm) / [OSV](https://osv.dev/vulnerability/GHSA-6p4f-wcwh-5vvm)。ソース: `src/main/java/com/example/demo/security/UnavailableEndpointsFilter.java; src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: Default Boot resource mappings, proxy normalization and runtime cache effects unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-72hv-8253-57qq — MODERATE / CVSS v3 0.0

`com.fasterxml.jackson.core:jackson-core:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-18401。修正版候補: 2.21.1。
条件: Chunked digits supplied to Jackson nonblocking async parser bypass numeric length constraints. 到達性: Servlet MVC uses synchronous JSON input; no nonblocking parser creation found in application sources.
根拠: [GHSA](https://github.com/advisories/GHSA-72hv-8253-57qq) / [OSV](https://osv.dev/vulnerability/GHSA-72hv-8253-57qq)。ソース: `src/main/java/com/example/demo/controller/`。未検証: Third-party async parsing not traced; do not remove findings.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-72pg-x5f8-j25j — MODERATE / CVSS v3 5.9

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41843。修正版候補: 6.2.19。
条件: Crafted requests traverse affected versioned/static-resource resolution or cache behavior. 到達性: Swagger/resource documentation routes are disabled in prod by UnavailableEndpointsFilter; no application VersionResourceResolver found. Framework default/static-resource path remains to be traced.
根拠: [GHSA](https://github.com/advisories/GHSA-72pg-x5f8-j25j) / [OSV](https://osv.dev/vulnerability/GHSA-72pg-x5f8-j25j)。ソース: `src/main/java/com/example/demo/security/UnavailableEndpointsFilter.java; src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: Default Boot resource mappings, proxy normalization and runtime cache effects unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-7m2p-62gw-p8qq — MODERATE / CVSS v3 4.2

`org.springframework:spring-web:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41854。修正版候補: 6.2.19。
条件: Untrusted URL parsed by UriComponentsBuilder bypasses host validation before outbound request. 到達性: No application UriComponentsBuilder or outbound HTTP client call found.
根拠: [GHSA](https://github.com/advisories/GHSA-7m2p-62gw-p8qq) / [OSV](https://osv.dev/vulnerability/GHSA-7m2p-62gw-p8qq)。ソース: `src/main/java/`。未検証: Third-party URL validation and outbound calls unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-8mc5-53m5-3qj2 — MODERATE / CVSS v3 5.3

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-32990。修正版候補: 10.1.53。
条件: Affected Tomcat request validation under connector/proxy prerequisites; incomplete earlier validation fix. 到達性: Tomcat HTTP connector is on the production request path; exact invalid input and validation preconditions not reproduced.
根拠: [GHSA](https://github.com/advisories/GHSA-8mc5-53m5-3qj2) / [OSV](https://osv.dev/vulnerability/GHSA-8mc5-53m5-3qj2)。ソース: `pom.xml; terraform/caddy/Caddyfile.tmpl`。未検証: Connector/proxy options and bounded validation regression required.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-957g-f97v-vppc — MODERATE / CVSS v3 5.9

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41846。修正版候補: 6.2.19。
条件: JSP form tags render malicious values in affected escaping context. 到達性: No JSP templates/form-tag usage; REST API returns DTOs.
根拠: [GHSA](https://github.com/advisories/GHSA-957g-f97v-vppc) / [OSV](https://osv.dev/vulnerability/GHSA-957g-f97v-vppc)。ソース: `src/main/java/com/example/demo/controller/; pom.xml`。未検証: External view rendering unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-9m3c-qcxr-9x87 — MODERATE / CVSS v3 6.1

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-25854。修正版候補: 10.1.53。
条件: LoadBalancerDrainingValve generates attacker-influenced redirects. 到達性: No LoadBalancerDrainingValve configured in source.
根拠: [GHSA](https://github.com/advisories/GHSA-9m3c-qcxr-9x87) / [OSV](https://osv.dev/vulnerability/GHSA-9m3c-qcxr-9x87)。ソース: `src/main/java/; docker-compose.yml`。未検証: External Tomcat valve configuration unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-cjpg-rgq5-fr37 — MODERATE / CVSS v3 5.3

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41853。修正版候補: 6.2.19。
条件: Crafted multipart boundaries are interpreted inconsistently across parser/proxy boundaries. 到達性: No multipart upload handler exists, but Boot multipart parser may precede controller dispatch; live parse/proxy behavior not established.
根拠: [GHSA](https://github.com/advisories/GHSA-cjpg-rgq5-fr37) / [OSV](https://osv.dev/vulnerability/GHSA-cjpg-rgq5-fr37)。ソース: `src/main/java/com/example/demo/controller/`。未検証: Parser enablement, proxy interpretation and bounded request reproduction required.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-g9jj-cgmh-9f38 — MODERATE / CVSS v3 5.9

`org.mariadb.jdbc:mariadb-java-client:3.5.7` · direct · runtime · production JAR確認済み · CVE-2026-55856。修正版候補: 3.5.9。
条件: Malicious database server/MITM manipulates handshake or authentication causing credential disclosure. 到達性: Production JDBC connects to MariaDB and is a reachable network boundary; Compose uses an internal DB network. MITM and TLS authenticity have not been tested.
根拠: [GHSA](https://github.com/advisories/GHSA-g9jj-cgmh-9f38) / [OSV](https://osv.dev/vulnerability/GHSA-g9jj-cgmh-9f38)。ソース: `src/main/resources/application-prod.properties; docker-compose.yml`。未検証: Actual TLS mode, DB network isolation and deployed JDBC overrides unverified; never probe production credentials.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-ggg2-9786-hwc8 — MODERATE / CVSS v3 5.3

`org.springframework.boot:spring-boot-autoconfigure:3.5.12` · transitive · compile · production JAR確認済み · CVE-2026-41001。修正版候補: 3.5.15。
条件: Embedded Artemis autoconfiguration creates predictable temp directory available to local attacker. 到達性: No Artemis dependency or embedded broker configured.
根拠: [GHSA](https://github.com/advisories/GHSA-ggg2-9786-hwc8) / [OSV](https://osv.dev/vulnerability/GHSA-ggg2-9786-hwc8)。ソース: `pom.xml; src/main/java/`。未検証: External runtime broker additions unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-gx83-3vf8-gh7j — MODERATE / CVSS v3 5.6

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-83557。修正版候補: 2.21.6。
条件: Record naming/ignore annotations, case-insensitive binding, or unsafe polymorphic base type matches advisory-specific prerequisite. 到達性: No JsonIgnore/JsonIgnoreProperties, naming strategy or polymorphic typing configured on application request DTOs.
根拠: [GHSA](https://github.com/advisories/GHSA-gx83-3vf8-gh7j) / [OSV](https://osv.dev/vulnerability/GHSA-gx83-3vf8-gh7j)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Third-party mapper/models and exact annotation combinations unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-h3qp-gqrc-q736 — MODERATE / CVSS v3 4.2

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41844。修正版候補: 6.2.19。
条件: Attacker-controlled URL reaches affected redirect handling. 到達性: No application redirect view/controller found.
根拠: [GHSA](https://github.com/advisories/GHSA-h3qp-gqrc-q736) / [OSV](https://osv.dev/vulnerability/GHSA-h3qp-gqrc-q736)。ソース: `src/main/java/com/example/demo/controller/`。未検証: Framework resource redirects and external proxy rewrites unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-hgj6-7826-r7m5 — MODERATE / CVSS v3 5.3

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-54514。修正版候補: 2.21.4。
条件: Attacker strings are bound to InetSocketAddress, InetAddress or Path and cause DNS/provider operations. 到達性: No InetAddress/InetSocketAddress/Path application request DTO field found.
根拠: [GHSA](https://github.com/advisories/GHSA-hgj6-7826-r7m5) / [OSV](https://osv.dev/vulnerability/GHSA-hgj6-7826-r7m5)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Indirect framework/third-party binding unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-j288-q9x7-2f5v — MODERATE / CVSS v3 6.5

`org.apache.commons:commons-lang3:3.17.0` · transitive · compile · production JAR確認済み · CVE-2025-48924。修正版候補: 3.18.0。
条件: Long ClassUtils.getClass input triggers uncontrolled recursive processing. 到達性: Library is in production JAR; no application call to ClassUtils.getClass found.
根拠: [GHSA](https://github.com/advisories/GHSA-j288-q9x7-2f5v) / [OSV](https://osv.dev/vulnerability/GHSA-j288-q9x7-2f5v)。ソース: `src/main/java/`。未検証: Flyway/Hibernate/other transitive call paths unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-mq64-j8f9-9gcj — MODERATE / CVSS v3 5.9

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41841。修正版候補: 6.2.19。
条件: Crafted requests traverse affected versioned/static-resource resolution or cache behavior. 到達性: Swagger/resource documentation routes are disabled in prod by UnavailableEndpointsFilter; no application VersionResourceResolver found. Framework default/static-resource path remains to be traced.
根拠: [GHSA](https://github.com/advisories/GHSA-mq64-j8f9-9gcj) / [OSV](https://osv.dev/vulnerability/GHSA-mq64-j8f9-9gcj)。ソース: `src/main/java/com/example/demo/security/UnavailableEndpointsFilter.java; src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: Default Boot resource mappings, proxy normalization and runtime cache effects unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-qv9r-c865-cp47 — MODERATE / CVSS v3 0.0

`org.apache.logging.log4j:log4j-api:2.24.3` · transitive · compile · production JAR確認済み · CVE-2026-49844。修正版候補: 2.25.5。
条件: Log4j MapMessage JSON serialization receives non-finite floating-point values. 到達性: Default logging routes through Logback; no application MapMessage use found.
根拠: [GHSA](https://github.com/advisories/GHSA-qv9r-c865-cp47) / [OSV](https://osv.dev/vulnerability/GHSA-qv9r-c865-cp47)。ソース: `pom.xml; src/main/java/`。未検証: Third-party logging calls unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-qxvw-fvwx-5cp7 — MODERATE / CVSS v3 5.9

`org.mariadb.jdbc:mariadb-java-client:3.5.7` · direct · runtime · production JAR確認済み · CVE-2026-55857。修正版候補: 3.5.9。
条件: Malicious database server/MITM manipulates handshake or authentication causing credential disclosure. 到達性: Production JDBC connects to MariaDB and is a reachable network boundary; Compose uses an internal DB network. MITM and TLS authenticity have not been tested.
根拠: [GHSA](https://github.com/advisories/GHSA-qxvw-fvwx-5cp7) / [OSV](https://osv.dev/vulnerability/GHSA-qxvw-fvwx-5cp7)。ソース: `src/main/resources/application-prod.properties; docker-compose.yml`。未検証: Actual TLS mode, DB network isolation and deployed JDBC overrides unverified; never probe production credentials.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-vvgp-rfg2-7rr6 — MODERATE / CVSS v3 5.3

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-77310。修正版候補: 2.21.5。
条件: Attacker strings are bound to InetSocketAddress, InetAddress or Path and cause DNS/provider operations. 到達性: No InetAddress/InetSocketAddress/Path application request DTO field found.
根拠: [GHSA](https://github.com/advisories/GHSA-vvgp-rfg2-7rr6) / [OSV](https://osv.dev/vulnerability/GHSA-vvgp-rfg2-7rr6)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Indirect framework/third-party binding unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-wjgm-6hv5-3cvf — MODERATE / CVSS v3 5.3

`com.fasterxml.jackson.core:jackson-databind:2.19.4` · transitive · compile · production JAR確認済み · CVE-2026-19032。修正版候補: 2.21.6。
条件: Attacker strings are bound to InetSocketAddress, InetAddress or Path and cause DNS/provider operations. 到達性: No InetAddress/InetSocketAddress/Path application request DTO field found.
根拠: [GHSA](https://github.com/advisories/GHSA-wjgm-6hv5-3cvf) / [OSV](https://osv.dev/vulnerability/GHSA-wjgm-6hv5-3cvf)。ソース: `src/main/java/com/example/demo/dto/`。未検証: Indirect framework/third-party binding unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-wxpp-56q6-5pcg — MODERATE / CVSS v3 5.3

`org.springframework:spring-expression:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41851。修正版候補: 6.2.19。
条件: User-controlled SpEL expression causes excessive evaluation/cache growth or unsafe invocation. 到達性: Method security uses constant annotations; no request-controlled ExpressionParser invocation found.
根拠: [GHSA](https://github.com/advisories/GHSA-wxpp-56q6-5pcg) / [OSV](https://osv.dev/vulnerability/GHSA-wxpp-56q6-5pcg)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java; src/main/java/com/example/demo/controller/`。未検証: Third-party expression evaluation and deployed customization unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-x2r2-rvhq-2mqv — MODERATE / CVSS v3 6.1

`org.springframework.security:spring-security-web:6.5.9` · transitive · compile · production JAR確認済み · CVE-2026-41706。修正版候補: 6.5.11。
条件: CookieRequestCache stores unvalidated redirect URL used after login. 到達性: Stateless JWT application, formLogin disabled; no CookieRequestCache configured.
根拠: [GHSA](https://github.com/advisories/GHSA-x2r2-rvhq-2mqv) / [OSV](https://osv.dev/vulnerability/GHSA-x2r2-rvhq-2mqv)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External login/request-cache overrides unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-x2wq-9x2f-fhj7 — MODERATE / CVSS v3 4.8

`org.springframework.security:spring-security-core:6.5.9` · transitive · compile · production JAR確認済み · CVE-2026-22751。修正版候補: 6.5.10。
条件: JdbcOneTimeTokenService has a race under one-time-token login. 到達性: No one-time-token/JdbcOneTimeTokenService login configured.
根拠: [GHSA](https://github.com/advisories/GHSA-x2wq-9x2f-fhj7) / [OSV](https://osv.dev/vulnerability/GHSA-x2wq-9x2f-fhj7)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External login configuration unverified.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-xvr9-35cr-46v9 — MODERATE / CVSS v3 5.9

`org.mariadb.jdbc:mariadb-java-client:3.5.7` · direct · runtime · production JAR確認済み · CVE-2026-55858。修正版候補: 3.5.9。
条件: Crafted server data reaches affected connector output-encoding context. 到達性: JDBC runtime path exists; exploit preconditions require driver/server-specific investigation.
根拠: [GHSA](https://github.com/advisories/GHSA-xvr9-35cr-46v9) / [OSV](https://osv.dev/vulnerability/GHSA-xvr9-35cr-46v9)。ソース: `src/main/resources/application-prod.properties`。未検証: Exact output context and malicious server control not reproduced.
対応: P2、triage 2026-11-11、是正目標 2026-11-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-659m-px2c-25wj — LOW / CVSS v3 3.7

`org.springframework:spring-core:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41848。修正版候補: 6.2.19。
条件: Crafted patterns/input reach recursive or expensive AntPathMatcher matching. 到達性: Security route patterns are application constants; framework-managed attacker paths still require tracing.
根拠: [GHSA](https://github.com/advisories/GHSA-659m-px2c-25wj) / [OSV](https://osv.dev/vulnerability/GHSA-659m-px2c-25wj)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: Exact affected matcher invocation and bounded performance unverified.
対応: P3、triage 2026-12-11、是正目標 2026-12-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-9f52-rjqv-25qv — LOW / CVSS v3 3.7

`org.springframework:spring-expression:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-41852。修正版候補: 6.2.19。
条件: User-controlled SpEL expression causes excessive evaluation/cache growth or unsafe invocation. 到達性: Method security uses constant annotations; no request-controlled ExpressionParser invocation found.
根拠: [GHSA](https://github.com/advisories/GHSA-9f52-rjqv-25qv) / [OSV](https://osv.dev/vulnerability/GHSA-9f52-rjqv-25qv)。ソース: `src/main/java/com/example/demo/security/SecurityConfig.java; src/main/java/com/example/demo/controller/`。未検証: Third-party expression evaluation and deployed customization unverified.
対応: P3、triage 2026-12-11、是正目標 2026-12-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-9m89-8frq-c98c — LOW / CVSS v3 3.7

`org.apache.tomcat.embed:tomcat-embed-core:10.1.52` · transitive · compile · production JAR確認済み · CVE-2026-43514。修正版候補: 10.1.55。
条件: AJP secret timing observable to attacker on the same network. 到達性: No AJP connector configured in repository.
根拠: [GHSA](https://github.com/advisories/GHSA-9m89-8frq-c98c) / [OSV](https://osv.dev/vulnerability/GHSA-9m89-8frq-c98c)。ソース: `src/main/java/; docker-compose.yml`。未検証: External connector/network reachability unverified.
対応: P3、triage 2026-12-11、是正目標 2026-12-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-jhq6-gfmj-v8fx — LOW / CVSS v3 0.0

`ch.qos.logback:logback-core:1.5.32` · transitive · compile · production JAR確認済み · CVE-2026-10532。修正版候補: 1.5.34。
条件: Untrusted serialized data reaches affected Logback object-input receiver/deserialization component. 到達性: No socket/object receiver configured; application uses console structured logs.
根拠: [GHSA](https://github.com/advisories/GHSA-jhq6-gfmj-v8fx) / [OSV](https://osv.dev/vulnerability/GHSA-jhq6-gfmj-v8fx)。ソース: `src/main/resources/application-prod.properties`。未検証: Runtime logging configuration and custom appenders unverified.
対応: P3、triage 2026-12-11、是正目標 2026-12-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-p47f-322f-whfh — LOW / CVSS v3 0.0

`ch.qos.logback:logback-core:1.5.32` · transitive · compile · production JAR確認済み · CVE-2026-9828。修正版候補: 1.5.33。
条件: Untrusted serialized data reaches affected Logback object-input receiver/deserialization component. 到達性: No socket/object receiver configured; application uses console structured logs.
根拠: [GHSA](https://github.com/advisories/GHSA-p47f-322f-whfh) / [OSV](https://osv.dev/vulnerability/GHSA-p47f-322f-whfh)。ソース: `src/main/resources/application-prod.properties`。未検証: Runtime logging configuration and custom appenders unverified.
対応: P3、triage 2026-12-11、是正目標 2026-12-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-vxf7-qj7q-83fh — LOW / CVSS v3 3.7

`org.springframework.security:spring-security-core:6.5.9` · transitive · compile · production JAR確認済み · CVE-2026-22746。修正版候補: 6.5.10。
条件: DaoAuthenticationProvider behavior distinguishes user attributes. 到達性: AuthController delegates to application AuthService; default AuthenticationManager bean exists but application login does not use it.
根拠: [GHSA](https://github.com/advisories/GHSA-vxf7-qj7q-83fh) / [OSV](https://osv.dev/vulnerability/GHSA-vxf7-qj7q-83fh)。ソース: `src/main/java/com/example/demo/service/AuthService.java; src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: External authentication-provider routes unverified.
対応: P3、triage 2026-12-11、是正目標 2026-12-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-wg35-8jpf-2xv3 — LOW / CVSS v3 3.1

`org.springframework:spring-webmvc:6.2.17` · transitive · compile · production JAR確認済み · CVE-2026-22741。修正版候補: 6.2.18。
条件: Crafted requests traverse affected versioned/static-resource resolution or cache behavior. 到達性: Swagger/resource documentation routes are disabled in prod by UnavailableEndpointsFilter; no application VersionResourceResolver found. Framework default/static-resource path remains to be traced.
根拠: [GHSA](https://github.com/advisories/GHSA-wg35-8jpf-2xv3) / [OSV](https://osv.dev/vulnerability/GHSA-wg35-8jpf-2xv3)。ソース: `src/main/java/com/example/demo/security/UnavailableEndpointsFilter.java; src/main/java/com/example/demo/security/SecurityConfig.java`。未検証: Default Boot resource mappings, proxy normalization and runtime cache effects unverified.
対応: P3、triage 2026-12-11、是正目標 2026-12-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

### GHSA-wxmm-q36w-r9xj — LOW / CVSS v3 3.7

`org.mariadb.jdbc:mariadb-java-client:3.5.7` · direct · runtime · production JAR確認済み · CVE-2026-61700。修正版候補: 3.5.9。
条件: Malicious server sends LOCAL INFILE request despite allowLocalInfile=false. 到達性: JDBC server responses reach the driver; no application LOCAL INFILE use found.
根拠: [GHSA](https://github.com/advisories/GHSA-wxmm-q36w-r9xj) / [OSV](https://osv.dev/vulnerability/GHSA-wxmm-q36w-r9xj)。ソース: `src/main/resources/application-prod.properties`。未検証: Malicious-server precondition and deployed driver flags unverified.
対応: P3、triage 2026-12-11、是正目標 2026-12-11、https://github.com/utl-flaxy/inventory-management-system/issues/87。互換更新と条件別回帰を別PRで検証。

## 証跡の再生成

CIのdependency-audit artifactは137件全体のSBOM、抑制なしOSV JSON、件数summaryをhead SHA付きで30日保存する。GitHub step summaryにも既存findingを表示する。期限内に外部の監査保管場所へ保存する責任はmaintainerにある。
この台帳のローカルraw baseline証跡のSHA256はJSONに記録。元ファイルは破棄していない。OSV DB更新により将来件数は変動し得るため、66件を固定PASS条件にしない。
Maven plugin/build tool自身、OSイメージ、実際の到達性をこの137件のSBOM検査で網羅したとは主張しない。

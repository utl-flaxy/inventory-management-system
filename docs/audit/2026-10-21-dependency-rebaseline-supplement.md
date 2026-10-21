# 旧Java依存66件の再ベースライン追補（2026-10-21）

## 1. 対象・判定の境界

[Issue #86の2026-10-21指示](https://github.com/utl-flaxy/inventory-management-system/issues/86#issuecomment-6314759207)に対応する文書限定の追補。
基準mainは **`7d10cca53b02dafb313f7cf4d4318e65d3c4ba3e`**。
[main push CI 39580708049](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39580708049)をGitHub APIとartifactで独立確認し、completed/success・必須5ジョブSUCCESSだった。
本PR HEADのCIはPR本文に別記し、このmainのrunをPRの成功へ読み替えない。

**旧Java advisory66件は現mainで未検出。現OSVは169 components / advisory0。一方、Trivy OS31件（MEDIUM27/LOW4、library0）は未是正の残余である。**
Boot 4.1.1 / Framework 7.0.9の**ソース採用は承認・merge済み**。本番サーバーへの適用・復旧・異なる旧バイナリへのrollbackは未検証であり、**実利用者向け本番運用NO GOを継続**する。
本追補はIssue #86/#87/#89のclose、運用リスクの受容、商用契約、本番適用の承認を行わない。

元の[2026-10-12台帳MD](2026-10-12-dependency-advisories.md)・[全66件JSON](2026-10-12-dependency-advisories.json)、
[Tomcat比較](2026-10-12-issue86-tomcat.json)、[Boot BOM比較](2026-10-12-issue86-boot-bom.json)、
[Jackson比較](2026-10-12-issue86-jackson.json)、[Boot 4 PoC報告](2026-10-12-issue89-boot4.md)・[JSON](2026-10-12-issue89-boot4.json)は当時の記録として改変しない。
過去資料の「未是正」「採用判断待ち」「branch protection未設定」や古い件数を現在の状態と解釈しない。

## 2. 原カウントと是正経路

原基準main `8ba4e9a9e833565edef8daa379c9f843fe2161f7`、OSV Scanner2.6.0 / CycloneDX Maven2.9.3。
137解決components中17ライブラリが影響対象で、**66 unique advisory ID = CRITICAL8/HIGH22/MODERATE28/LOW8**。
元のproduction JAR確認は17ライブラリすべてで行われていた。Maven scopeはcompile62 finding / runtime4 findingで、test-only66件という意味ではない。

|対象|2026-10-12原カウント|現mainでの旧ID検出|未完了の別責務|
|---|---|---|---|
|Issue #86|30（C8/H22）|0/30|実配備artifact・本番到達性・復旧の確認、または独立レビュー済みの移管|
|Issue #87|36（M28/L8）|0/36|同上。旧予定日と実本番適用は別|
|Issue #89|上記#86に含まれるCritical2。追加加算しない|0/2|Boot 4ソース採用済み。本番導入・運用判断は未完了|
|現OSV全SBOM|169 components、全advisory0|旧66に限らず検出0|未知・後日追加advisory、実配備の差異は未保証|
|現Trivy image|OS31 finding instances / 24 unique CVE、library0|Java66と対象・数え方が違う|OS M27/L4の継続監査。Java0と合算・相殺しない|

|履歴段階|merge commit / 比較の一次資料|全advisory数|元66 IDの残数|増減の注意|
|---|---|---|---|---|
|原台帳|`8ba4e9a` / 上記JSON|66|66|137 components|
|[PR #90](https://github.com/utl-flaxy/inventory-management-system/pull/90) Tomcat10.1.60|`899f98b5d9919f5df1d23dae557d34e98693e993` / Tomcat比較|51|51|旧15消失、新規0|
|[PR #91](https://github.com/utl-flaxy/inventory-management-system/pull/91) Boot3.5.16|`502b9b64c08935e49dba84ad3c64c807b5334f94` / Boot BOM比較|19|17|旧34消失、新規2で差引32減|
|[PR #92](https://github.com/utl-flaxy/inventory-management-system/pull/92) Jackson2.21.7|`306a3c42d118e13e1cfebb7048284428e84f57c5` / Jackson比較|8|8|旧9＋上記新規2が消失、新規0|
|[PR #93](https://github.com/utl-flaxy/inventory-management-system/pull/93) Boot4.1.1とpatch overrides|`b66104f0ae5cc0ca1ff71fb4276e65a7aa0b78c8` / Boot4 JSON|0|0|169 components、最終版は無抑制0|
|本追補のmain|`7d10cca53b02dafb313f7cf4d4318e65d3c4ba3e` / CI39580708049|0|0|現在の解決版と全169件のscan coverageを照合|

PR #91の新規2 IDは`GHSA-5gvw-p9qm-jgwh`、`GHSA-mhm7-754m-9p8w`。原66へ混ぜない。
Boot4の**未調整BOMの試行では8→10件**となった履歴もBoot4 JSONに残る。最終版はTomcat11.0.26 / Jackson3.1.7・Jackson2.21.7等の明示patchを含み0件にした。
Boot4へmajor更新するだけで自動的に安全になるという説明はしない。

## 3. 17ライブラリの解決版対応表

[現POM](../../pom.xml)のparentはBoot4.1.1、Java17。main CIの解決済みSBOMを正として同一Maven group:artifactで対応付けた。
現SBOMのscope `required`はCycloneDXの分類であり、元Mavenのcompile/runtimeとは同じ列にしない。
原カウントはC/H/M/L順。L番号は次節66行と結合するキーで、各行の座標・旧版・scope・件数の正本は原台帳JSONである。

|キー / Maven coordinate|旧版|旧Maven scope|原件数（C/H/M/L）|現main解決版|現OSV検出|
|---|---|---|---|---|---|
|L01 `ch.qos.logback:logback-core`|1.5.32|compile|2（0/0/0/2）|1.5.38|0|
|L02 `com.fasterxml.jackson.core:jackson-core`|2.19.4|compile|4（0/3/1/0）|2.21.7|0|
|L03 `com.fasterxml.jackson.core:jackson-databind`|2.19.4|compile|11（0/5/6/0）|2.21.7|0|
|L04 `io.micrometer:micrometer-core`|1.15.10|compile|2（0/2/0/0）|1.17.1|0|
|L05 `org.apache.commons:commons-lang3`|3.17.0|compile|1（0/0/1/0）|3.20.0|0|
|L06 `org.apache.logging.log4j:log4j-api`|2.24.3|compile|1（0/0/1/0）|2.25.5|0|
|L07 `org.apache.tomcat.embed:tomcat-embed-core`|10.1.52|compile|15（6/6/2/1）|11.0.26|0|
|L08 `org.mariadb.jdbc:mariadb-java-client`|3.5.7|runtime|4（0/0/3/1）|3.5.10|0|
|L09 `org.springframework.boot:spring-boot`|3.5.12|compile|1（0/1/0/0）|4.1.1|0|
|L10 `org.springframework.boot:spring-boot-autoconfigure`|3.5.12|compile|1（0/0/1/0）|4.1.1|0|
|L11 `org.springframework.data:spring-data-commons`|3.5.10|compile|4（0/2/2/0）|4.1.1|0|
|L12 `org.springframework.security:spring-security-core`|6.5.9|compile|2（0/0/1/1）|7.1.1|0|
|L13 `org.springframework.security:spring-security-web`|6.5.9|compile|2（0/0/2/0）|7.1.1|0|
|L14 `org.springframework:spring-core`|6.2.17|compile|1（0/0/0/1）|7.0.9|0|
|L15 `org.springframework:spring-expression`|6.2.17|compile|3（0/1/1/1）|7.0.9|0|
|L16 `org.springframework:spring-web`|6.2.17|compile|1（0/0/1/0）|7.0.9|0|
|L17 `org.springframework:spring-webmvc`|6.2.17|compile|11（2/2/6/1）|7.0.9|0|

Jackson2の`com.fasterxml.jackson.core:*`はJJWT等に残り2.21.7。HTTP用Jackson3の`tools.jackson.core:*`3.1.7へ座標を勝手に置き換えて「削除済み」とは判定しない。
同じ現SBOMでSecurity7.1.1、Data JPA4.1.1 / Hibernate7.4.5.Final、Flyway core/mysql12.4.0、Testcontainers2.0.5を確認した。
137→169は依存構成・test依存等の変更を含み、同じ137件だけを再scanした比較ではない。

## 4. 元advisory全66 IDの追跡

各行のLキーは§3の**同一座標・旧scope・旧版**に対応。更新版を本表でも再掲する。
「初回消失」は過去比較JSONで最初にremaining_idsから外れたPRで、以後の全候補版に対する永久保証ではない。
修正版・脅威条件・原期限・担当未合意状態は元JSONの各IDに保存する。原severityを遡及変更しない。
「未検出」はmain CIの全169 componentsの無抑制OSV結果とのID照合であり、実本番への適用済み・実悪用不能を意味しない。

|元advisory ID / CVE|原severity / Issue|座標キー / 旧scope|初回消失PR|現main解決版|現OSV|
|---|---|---|---|---|---|
|[GHSA-jhq6-gfmj-v8fx](https://osv.dev/vulnerability/GHSA-jhq6-gfmj-v8fx) / CVE-2026-10532|LOW / #87|L01 / compile|#91|1.5.38|未検出|
|[GHSA-p47f-322f-whfh](https://osv.dev/vulnerability/GHSA-p47f-322f-whfh) / CVE-2026-9828|LOW / #87|L01 / compile|#91|1.5.38|未検出|
|[GHSA-72hv-8253-57qq](https://osv.dev/vulnerability/GHSA-72hv-8253-57qq) / CVE-2026-18401|MODERATE / #87|L02 / compile|#91|2.21.7|未検出|
|[GHSA-7hhh-6rmp-j9qf](https://osv.dev/vulnerability/GHSA-7hhh-6rmp-j9qf) / CVE-2026-89425|HIGH / #86|L02 / compile|#92|2.21.7|未検出|
|[GHSA-p6pp-m3f8-5c89](https://osv.dev/vulnerability/GHSA-p6pp-m3f8-5c89) / CVE-2026-89407|HIGH / #86|L02 / compile|#92|2.21.7|未検出|
|[GHSA-r7wm-3cxj-wff9](https://osv.dev/vulnerability/GHSA-r7wm-3cxj-wff9) / CVE-2026-68494|HIGH / #86|L02 / compile|#91|2.21.7|未検出|
|[GHSA-3pjw-73gf-8qr5](https://osv.dev/vulnerability/GHSA-3pjw-73gf-8qr5) / CVE-2026-59888|MODERATE / #87|L03 / compile|#91|2.21.7|未検出|
|[GHSA-5jmj-h7xm-6q6v](https://osv.dev/vulnerability/GHSA-5jmj-h7xm-6q6v) / CVE-2026-54515|MODERATE / #87|L03 / compile|#92|2.21.7|未検出|
|[GHSA-cxp5-3px4-pw24](https://osv.dev/vulnerability/GHSA-cxp5-3px4-pw24) / CVE-2026-91777|HIGH / #86|L03 / compile|#92|2.21.7|未検出|
|[GHSA-gx83-3vf8-gh7j](https://osv.dev/vulnerability/GHSA-gx83-3vf8-gh7j) / CVE-2026-83557|MODERATE / #87|L03 / compile|#92|2.21.7|未検出|
|[GHSA-hgj6-7826-r7m5](https://osv.dev/vulnerability/GHSA-hgj6-7826-r7m5) / CVE-2026-54514|MODERATE / #87|L03 / compile|#91|2.21.7|未検出|
|[GHSA-j3rv-43j4-c7qm](https://osv.dev/vulnerability/GHSA-j3rv-43j4-c7qm) / CVE-2026-54512|HIGH / #86|L03 / compile|#91|2.21.7|未検出|
|[GHSA-q4xh-88c3-wmh7](https://osv.dev/vulnerability/GHSA-q4xh-88c3-wmh7) / CVE-2026-68497|HIGH / #86|L03 / compile|#92|2.21.7|未検出|
|[GHSA-rmj7-2vxq-3g9f](https://osv.dev/vulnerability/GHSA-rmj7-2vxq-3g9f) / CVE-2026-54513|HIGH / #86|L03 / compile|#91|2.21.7|未検出|
|[GHSA-vvgp-rfg2-7rr6](https://osv.dev/vulnerability/GHSA-vvgp-rfg2-7rr6) / CVE-2026-77310|MODERATE / #87|L03 / compile|#92|2.21.7|未検出|
|[GHSA-wjgm-6hv5-3cvf](https://osv.dev/vulnerability/GHSA-wjgm-6hv5-3cvf) / CVE-2026-19032|MODERATE / #87|L03 / compile|#92|2.21.7|未検出|
|[GHSA-wv8q-qhhj-9h54](https://osv.dev/vulnerability/GHSA-wv8q-qhhj-9h54) / CVE-2026-91776|HIGH / #86|L03 / compile|#92|2.21.7|未検出|
|[GHSA-g3pr-3p32-fp23](https://osv.dev/vulnerability/GHSA-g3pr-3p32-fp23) / CVE-2026-40984|HIGH / #86|L04 / compile|#91|1.17.1|未検出|
|[GHSA-w737-wx49-qj23](https://osv.dev/vulnerability/GHSA-w737-wx49-qj23) / CVE-2026-40983|HIGH / #86|L04 / compile|#91|1.17.1|未検出|
|[GHSA-j288-q9x7-2f5v](https://osv.dev/vulnerability/GHSA-j288-q9x7-2f5v) / CVE-2025-48924|MODERATE / #87|L05 / compile|#93|3.20.0|未検出|
|[GHSA-qv9r-c865-cp47](https://osv.dev/vulnerability/GHSA-qv9r-c865-cp47) / CVE-2026-49844|MODERATE / #87|L06 / compile|#93|2.25.5|未検出|
|[GHSA-5m62-pw8w-7w9f](https://osv.dev/vulnerability/GHSA-5m62-pw8w-7w9f) / CVE-2026-43515|CRITICAL / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-5mp6-jrq3-r938](https://osv.dev/vulnerability/GHSA-5mp6-jrq3-r938) / CVE-2026-43513|HIGH / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-69cc-cv78-qc8g](https://osv.dev/vulnerability/GHSA-69cc-cv78-qc8g) / CVE-2026-29129|HIGH / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-8mc5-53m5-3qj2](https://osv.dev/vulnerability/GHSA-8mc5-53m5-3qj2) / CVE-2026-32990|MODERATE / #87|L07 / compile|#90|11.0.26|未検出|
|[GHSA-9m3c-qcxr-9x87](https://osv.dev/vulnerability/GHSA-9m3c-qcxr-9x87) / CVE-2026-25854|MODERATE / #87|L07 / compile|#90|11.0.26|未検出|
|[GHSA-9m89-8frq-c98c](https://osv.dev/vulnerability/GHSA-9m89-8frq-c98c) / CVE-2026-43514|LOW / #87|L07 / compile|#90|11.0.26|未検出|
|[GHSA-9xv2-5v5q-p794](https://osv.dev/vulnerability/GHSA-9xv2-5v5q-p794) / CVE-2026-65905|CRITICAL / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-fv25-8xcx-gqjc](https://osv.dev/vulnerability/GHSA-fv25-8xcx-gqjc) / CVE-2026-42498|HIGH / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-gcx9-497g-6cp6](https://osv.dev/vulnerability/GHSA-gcx9-497g-6cp6) / CVE-2026-65182|CRITICAL / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-gx5v-xp9w-j4cg](https://osv.dev/vulnerability/GHSA-gx5v-xp9w-j4cg) / CVE-2026-41284|HIGH / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-h3x4-894j-xpx5](https://osv.dev/vulnerability/GHSA-h3x4-894j-xpx5) / CVE-2026-68525|CRITICAL / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-h6fc-48rj-7qqh](https://osv.dev/vulnerability/GHSA-h6fc-48rj-7qqh) / CVE-2026-43512|CRITICAL / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-r29c-68gh-xp6x](https://osv.dev/vulnerability/GHSA-r29c-68gh-xp6x) / CVE-2026-41293|CRITICAL / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-rv64-5gf8-9qq8](https://osv.dev/vulnerability/GHSA-rv64-5gf8-9qq8) / CVE-2026-34483|HIGH / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-x4m4-345f-5h5g](https://osv.dev/vulnerability/GHSA-x4m4-345f-5h5g) / CVE-2026-34487|HIGH / #86|L07 / compile|#90|11.0.26|未検出|
|[GHSA-g9jj-cgmh-9f38](https://osv.dev/vulnerability/GHSA-g9jj-cgmh-9f38) / CVE-2026-55856|MODERATE / #87|L08 / runtime|#93|3.5.10|未検出|
|[GHSA-qxvw-fvwx-5cp7](https://osv.dev/vulnerability/GHSA-qxvw-fvwx-5cp7) / CVE-2026-55857|MODERATE / #87|L08 / runtime|#93|3.5.10|未検出|
|[GHSA-wxmm-q36w-r9xj](https://osv.dev/vulnerability/GHSA-wxmm-q36w-r9xj) / CVE-2026-61700|LOW / #87|L08 / runtime|#93|3.5.10|未検出|
|[GHSA-xvr9-35cr-46v9](https://osv.dev/vulnerability/GHSA-xvr9-35cr-46v9) / CVE-2026-55858|MODERATE / #87|L08 / runtime|#93|3.5.10|未検出|
|[GHSA-wwpq-f5c3-7hvx](https://osv.dev/vulnerability/GHSA-wwpq-f5c3-7hvx) / CVE-2026-40973|HIGH / #86|L09 / compile|#91|4.1.1|未検出|
|[GHSA-ggg2-9786-hwc8](https://osv.dev/vulnerability/GHSA-ggg2-9786-hwc8) / CVE-2026-41001|MODERATE / #87|L10 / compile|#91|4.1.1|未検出|
|[GHSA-5m4m-73w9-8433](https://osv.dev/vulnerability/GHSA-5m4m-73w9-8433) / CVE-2026-41721|MODERATE / #87|L11 / compile|#91|4.1.1|未検出|
|[GHSA-5vpf-xvv7-c8vh](https://osv.dev/vulnerability/GHSA-5vpf-xvv7-c8vh) / CVE-2026-41711|MODERATE / #87|L11 / compile|#91|4.1.1|未検出|
|[GHSA-88fw-v6x4-3f58](https://osv.dev/vulnerability/GHSA-88fw-v6x4-3f58) / CVE-2026-41695|HIGH / #86|L11 / compile|#91|4.1.1|未検出|
|[GHSA-9fw2-h3hf-293r](https://osv.dev/vulnerability/GHSA-9fw2-h3hf-293r) / CVE-2026-41716|HIGH / #86|L11 / compile|#91|4.1.1|未検出|
|[GHSA-vxf7-qj7q-83fh](https://osv.dev/vulnerability/GHSA-vxf7-qj7q-83fh) / CVE-2026-22746|LOW / #87|L12 / compile|#91|7.1.1|未検出|
|[GHSA-x2wq-9x2f-fhj7](https://osv.dev/vulnerability/GHSA-x2wq-9x2f-fhj7) / CVE-2026-22751|MODERATE / #87|L12 / compile|#91|7.1.1|未検出|
|[GHSA-293q-567p-wmwq](https://osv.dev/vulnerability/GHSA-293q-567p-wmwq) / CVE-2026-47838|MODERATE / #87|L13 / compile|#91|7.1.1|未検出|
|[GHSA-x2r2-rvhq-2mqv](https://osv.dev/vulnerability/GHSA-x2r2-rvhq-2mqv) / CVE-2026-41706|MODERATE / #87|L13 / compile|#91|7.1.1|未検出|
|[GHSA-659m-px2c-25wj](https://osv.dev/vulnerability/GHSA-659m-px2c-25wj) / CVE-2026-41848|LOW / #87|L14 / compile|#91|7.0.9|未検出|
|[GHSA-9f52-rjqv-25qv](https://osv.dev/vulnerability/GHSA-9f52-rjqv-25qv) / CVE-2026-41852|LOW / #87|L15 / compile|#91|7.0.9|未検出|
|[GHSA-r5w3-xv2f-j59q](https://osv.dev/vulnerability/GHSA-r5w3-xv2f-j59q) / CVE-2026-41850|HIGH / #86|L15 / compile|#91|7.0.9|未検出|
|[GHSA-wxpp-56q6-5pcg](https://osv.dev/vulnerability/GHSA-wxpp-56q6-5pcg) / CVE-2026-41851|MODERATE / #87|L15 / compile|#91|7.0.9|未検出|
|[GHSA-7m2p-62gw-p8qq](https://osv.dev/vulnerability/GHSA-7m2p-62gw-p8qq) / CVE-2026-41854|MODERATE / #87|L16 / compile|#91|7.0.9|未検出|
|[GHSA-3chg-m5w7-qfv5](https://osv.dev/vulnerability/GHSA-3chg-m5w7-qfv5) / CVE-2026-41845|HIGH / #86|L17 / compile|#91|7.0.9|未検出|
|[GHSA-6p4f-wcwh-5vvm](https://osv.dev/vulnerability/GHSA-6p4f-wcwh-5vvm) / CVE-2026-22745|MODERATE / #87|L17 / compile|#91|7.0.9|未検出|
|[GHSA-72pg-x5f8-j25j](https://osv.dev/vulnerability/GHSA-72pg-x5f8-j25j) / CVE-2026-41843|MODERATE / #87|L17 / compile|#91|7.0.9|未検出|
|[GHSA-957g-f97v-vppc](https://osv.dev/vulnerability/GHSA-957g-f97v-vppc) / CVE-2026-41846|MODERATE / #87|L17 / compile|#91|7.0.9|未検出|
|[GHSA-cjpg-rgq5-fr37](https://osv.dev/vulnerability/GHSA-cjpg-rgq5-fr37) / CVE-2026-41853|MODERATE / #87|L17 / compile|#91|7.0.9|未検出|
|[GHSA-h3qp-gqrc-q736](https://osv.dev/vulnerability/GHSA-h3qp-gqrc-q736) / CVE-2026-41844|MODERATE / #87|L17 / compile|#91|7.0.9|未検出|
|[GHSA-j9f9-w8pj-32f8](https://osv.dev/vulnerability/GHSA-j9f9-w8pj-32f8) / CVE-2026-47890|CRITICAL / #86|L17 / compile|#93|7.0.9|未検出|
|[GHSA-mq64-j8f9-9gcj](https://osv.dev/vulnerability/GHSA-mq64-j8f9-9gcj) / CVE-2026-41841|MODERATE / #87|L17 / compile|#91|7.0.9|未検出|
|[GHSA-pc63-qcmh-9cmg](https://osv.dev/vulnerability/GHSA-pc63-qcmh-9cmg) / CVE-2026-47884|CRITICAL / #86|L17 / compile|#93|7.0.9|未検出|
|[GHSA-wg35-8jpf-2xv3](https://osv.dev/vulnerability/GHSA-wg35-8jpf-2xv3) / CVE-2026-22741|LOW / #87|L17 / compile|#91|7.0.9|未検出|
|[GHSA-x23c-287f-qqv5](https://osv.dev/vulnerability/GHSA-x23c-287f-qqv5) / CVE-2026-41842|HIGH / #86|L17 / compile|#91|7.0.9|未検出|

原66の初回消失はPR90:15 / PR91:34 / PR92:9 / PR93:8、合計66。
この件数は各段階の全finding差分（66→51→19→8→0）と新規IDの有無を別々に計算したもの。

## 5. 到達条件と本番到達性を分ける

17ライブラリのproduction同梱は旧監査の事実。現mainも同一座標の更新版を含む。
原JSONの`exploit_condition`・`reachability`・`unverified`はIDごとの**当時のソース調査**で、今回のOSV未検出を理由に本番安全へ格上げしない。
非同期JSON parser/unsafe polymorphic binding、SpEL、projection binder、Logback receiver、Log4j MapMessage、Tomcat connector/servlet設定、Micrometer tags等はそれぞれ異なる前提を持つ。
「JAR同梱」「アプリで呼出条件を確認」「外部設定を含む実配備で到達」の3段階を区別する。第三者の内部呼出全経路と本番外部設定は未確認。

|旧Critical2（#89、#86の内数）|前提 / 既存検証|現在の判断と限界|
|---|---|---|
|GHSA-j9f9-w8pj-32f8 / CVE-2026-47890|SSE view fragments。アプリREST handlerとprod/local/default contextで条件を検査|Framework7.0.9、OSV未検出。アプリに該当使用は見つからないが、依存のview機能の存在と本番外部設定は別|
|GHSA-pc63-qcmh-9cmg / CVE-2026-47884|XsltView、implicit view name、MVC `/**` mapping。resolver bean/handlerを検査|同上。Securityの`/**` matcherや標準view infrastructureを脆弱なview controllerと同一視しない|

現在の[Controller](../../src/main/java/com/example/demo/controller)と
[ProductionProfilesIT](../../src/test/java/com/example/demo/security/ProductionProfilesIT.java)の既存assertを照合した。
テストはXsltView/Resolver bean不在、response-body、SSE/fragment/view返却・該当mapping不在を確認する。
元OSVのCRITICALと当時のvendor評価SSE LOW / XSLT MEDIUMは別の分類で、OSV値を軽く書き換えない。
本番の実利用が別途確認された場合は、Issue #89の緊急対応条件を再評価する。
MariaDB JDBCは実DBとのネットワーク境界にある。ライブラリ更新は本番TLS・接続先認証・ネットワーク隔離の実測を代替しない。

## 6. 2026-10-21 main CIの独立証跡

run39580708049はpush/main、開始09:55:29Z〜更新10:09:50Z。以下はこのSHAに固定した実測で、将来のmainやPR HEADの値ではない。

|job|実行結果|内容と保証限界|
|---|---|---|
|test|SUCCESS|Java17 Surefire1,384 + Failsafe986 = **2,370**、F/E/S0。Docker業務9段階＋B1/B2/B3、V1〜V9、non-root/read-only、同一image設定故障復帰PASS|
|terraform|SUCCESS|fmt/init(no backend)/validate、AWS mock27。AWSの配備・状態は検証しない|
|secret-scan|SUCCESS|Python29、Gitleaks8.30.1、tracked373、例外0・finding0・未解決0。実clean/異常fixtureでentry point失敗とredactionを検査|
|java-static-analysis|SUCCESS|SpotBugs plugin4.10.4.1、151 classes、built-in SECURITY全priority/Low閾値で違反0。実SQL異常fixtureとMaven/entry point失敗を検査。全カテゴリの網羅とは異なる|
|dependency-audit|SUCCESS|CycloneDX2.9.3 / OSV2.6.0、169/0、scanner exit0。audit-onlyであり、非ゼロfinding時も報告を成功と区別する|

無抑制の根拠は[quality_gates.py](../../scripts/ci/quality_gates.py)、[CI workflow](../../.github/workflows/ci.yml)、
[Gitleaks config](../../.github/security/gitleaks.toml)、[空の例外台帳](../../.github/security/exceptions.json)、
[SECURITYカテゴリ設定](../../.github/security/spotbugs-security.xml)。
Gitleaksはupstream標準rules、空ignoreファイル、`--ignore-gitleaks-allow`、`--redact=100`で全trackedファイルを実scanする。
SpotBugsは古いreportを消してから実行し、解析エラー/欠落classを成功扱いしない。
OSVは空の専用config、`--all-packages`、全SBOMとのartifact/version多重集合一致を検証し、脆弱性suppression/overrideなし。scanner不整合・実行エラーは失敗にする。
Gitleaks8.30.1 / OSV2.6.0の配布binaryは既存レビュー済みSHA256で固定。これを「現在入手可能な最新版」とは主張しない。

### 取得時点・DBとartifact

- Java SBOM生成: **2026-10-21T09:55:44Z**、外部OSV照会step: **09:55:45Z**。artifact作成09:55:46Z、ID `12336331219`。
  OSV結果はオンライン照会のsnapshotで、**OSVのDB更新時刻・不変DB版はreportに提供されず未確認**。この取得日をDB更新日と捏造しない。
- Trivy0.75.0 scan CreatedAt: **2026-10-21T10:09:41.693107248Z**、CI DB取得開始10:09:19Z。
  artifact作成10:09:48Z、ID `12336378574`。CI artifactはDB metadataそのものを保存していないため、**このrunのDB UpdatedAtは未確認**。
- [PR #144の同一DB比較](2026-10-21-os-package-remediation.md)・[比較JSON](../evidence/issue-143-os-comparison.json)では、OS DB UpdatedAt **2026-10-21T07:13:45.702547343Z** / Java DB **01:09:45.346257859Z**、各DBファイルhashを保存している。
  これは比較用に固定したDBであり、CI DBとの同一性を時刻だけで推定しない。そこでOS49→31（curl2 package、9 CVE×2 = LOW18減）、新規finding0を確認済み。
- main image `sha256:5df6cdf0fc87b1283d8e2637ab86aa7307aebfca30c345606f965bebaf8bf0b5` / Ubuntu24.04 linux/amd64。
  Trivy scanner digestは`sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa`。
  OS31はMEDIUM27/LOW4、24 unique CVE、library0、31件すべてFixedVersion空欄。空欄は将来も修正なしという意味ではない。
  severity/unfixed/ignore filterなしの全finding報告、audit-only exit0であり、脆弱性ゲートPASS/OS脆弱性0とは表現しない。

取得したmain artifact内ファイルのSHA-256（GitHub artifactは保存期間後に失効し得る）:

|ファイル|SHA-256|
|---|---|
|dependency/bom.json|`7cee9139987774c7e7856293b95e6e66fe3bdb3d915b0b0e26640db0bef4111b`|
|dependency/quality-gates/dependency-audit.json|`62449478ac10eef557946a7712835f2111c62b1ca098fa08e446dc7469ac314d`|
|docker/image-scan.json|`3b449a01338a992326871e18558e2a8e6cca1ced9c9bcaac703adaa266a79cd3`|

再評価時は対象SHAを固定し、[既存CIと同じ手順](../ci-quality-gates.md)で解決済みSBOMとOSVを再取得する。
元66 ID集合と各段階remaining_idsを突合し、現SBOMで17座標のversionを照合する。新規IDも除外せず数え、Trivyとは対象・severity・DB日時を別に報告する。
advisory公開・改訂、DB同期、scanner抽出範囲の限界があるため、今回の0を恒久的なゼロの保証としない。

## 7. ソース完了と本番運用未完了の二層判定

[PR #93](https://github.com/utl-flaxy/inventory-management-system/pull/93)の最終head `8d156e5bf7a2650cfed380fea218c7f0ba504526`、
[CI38212452410](https://github.com/utl-flaxy/inventory-management-system/actions/runs/38212452410)は当時Java1,790/OSV169・0。
[採用済みの記録](https://github.com/utl-flaxy/inventory-management-system/issues/89#issuecomment-6119264956)とmerge commit `b66104f0ae5cc0ca1ff71fb4276e65a7aa0b78c8`を確認した。
旧PoC文書の「最終採用待ち」は当時の記録であり、**現mainでは公開Boot4構成を正式なソース基準として採用済み**である。
Enterprise Framework6.2.20継続案は採用必須条件ではなく、正規契約・entitlement・費用・private repo認証は未確認、商用artifactは取得していない。
公開Boot4の採用は、将来の保守契約や実サーバー導入の確認を意味しない。

|条件|ソース/隔離環境で確認した範囲|実運用の未完了条件 / 移管候補（未承認）|
|---|---|---|
|Boot採用・依存patch|PR90〜93 merge、現POM/SBOM/CIで旧66未検出|実際に配備するimage/SBOM、外部configと本番到達性を運用者が別途照合。#86/#87/#89で追跡継続|
|商用修正版/契約|公開Boot4を採用。商用artifact取得なし|商用継続を再選択する場合のみ契約・認証・配布権限・費用の承認が必要。契約済みと仮定しない|
|secret/認証/接続先|外部入力と安全な失敗、使い捨て資格情報で回帰。B1/B2/B3実装済み|実secret、trusted proxy peer、DB TLS/権限、外部config未確認。[設定](../configuration.md)、[認証](../auth.md)、運用専用Issueへの移管案|
|DB/migration/backup|MariaDB12.2.2、V1〜V9、隔離backup/restoreとDB不変の検証|本番DB version/データ/backupの完全性、RPO/RTO、復元演習未確認。[DB運用](../db/README.md)、[復旧手順](../db/backup-restore.md)に基づく運用Issue案|
|rollback|同一image IDの設定故障→直前設定復帰、app単独/DB不変をデモで確認。Flyway旧新履歴の限定ITあり|**異なる旧バイナリ＋現schema/認証世代での復帰は未検証**。旧鍵/旧DBによるJWT再受理も別リスク。[配備](../deployment.md)、[JWT移行](../security/jwt-revocation-migration.md)の事前演習が必要|
|TLS/公開経路|使い捨てCaddy内部CA、loopback/非root/read-onlyのテスト|実DNS/公的TLS/EC2/proxy経路は未確認。[TLS](../tls.md)に基づく別作業。今回アクセスしない|
|負荷/容量/SLA|隔離同時注文・lock・待機予算・shutdownの回帰|実hostメモリ/継続負荷/SLA/監視/障害復旧は未保証。[メモリ設計](../docker-runtime.md)は本番容量の実測を代替しない|
|運用移管・close|本追補は現況と未達の整理のみ|担当・新期限・移管先の承認は未合意。独立レビューが#86/#87/#89のclose可否と移管先を判断|

元の10/14 triage・採用判断、10/26 remediation、Moderate11/11・Low12/11は**2026-10-12当時の提案期限**として保持する。
ソース更新日を本番適用日へ読み替えず、期限延長や担当者の合意を本追補で代行しない。未実施の運用条件は実利用開始前の必須ゲートであり、具体的担当・日付は運用者と独立レビューで決める。

## 8. 残余リスクと今回の完了条件

- **OS31件は未解消**。修正版の有無と実到達条件を継続評価し、将来のDB更新で件数が増減し得る。Java66の是正と混同しない。
- **Flyway/MariaDB WARNを保持**。main CIでは互換性WARN3（旧Flyway履歴roundtripを含む）。[FlywayUpgradeRollbackIT](../../src/test/java/com/example/demo/repository/FlywayUpgradeRollbackIT.java)の成功は全バージョン互換・本番復旧の保証ではない。
- **[Issue #145](https://github.com/utl-flaxy/inventory-management-system/issues/145)はOPEN**。Windows/Docker Desktop/WSL2のcertificate not yet valid、Wait strategy failure、Caddy TLS初回失敗、過去DB観測CLI3失敗を消さない。
  [2026-10-21診断](https://github.com/utl-flaxy/inventory-management-system/issues/145#issuecomment-6313914018)には証明書NotBeforeと時計差の部分観測があるが、全症状の根因確定・恒久是正ではない。今回の成功でも履歴は残す。#121/#122で是正済みのDBロック時間判定と混同しない。
- **[Issue #134](https://github.com/utl-flaxy/inventory-management-system/issues/134)のjti個別失効は保留**。[ADR](../design/jwt-individual-revocation.md)のみ。B1〜B3のユーザー単位失効と、選択した1 JWTだけの失効は別機能。
- 異版rollback・本番backup/restore・TLS・容量/SLA・実secretは未確認で、**本番運用NO GO**。Deployはdisabledを維持。
- [Draft PR #103](https://github.com/utl-flaxy/inventory-management-system/pull/103)は統合検証用でmerge/完成根拠の対象外。

本PRは追補とREADMEの参照だけを変更し、Java/POM/migration/runtime/Docker/Terraform/workflowを変更しない。
文書リンク・66 IDと集計・旧資料非改変を確認し、Java17 full verifyの実測（失敗時も含む）と**新PR HEADの必須5ジョブ**をPR本文へ記録する。
Issue #86/#87/#89はいずれもOPENを維持し、現mainコード完了/運用未完了を分けたコメント提案をPR本文へ添える。独立レビュー前のclose、PR merge/auto-mergeは行わない。

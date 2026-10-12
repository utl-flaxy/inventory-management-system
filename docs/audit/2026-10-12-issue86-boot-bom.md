# Issue #86 — boot-bom 是正・監査比較

一次資料は[Issue #80の全66件台帳](2026-10-12-dependency-advisories.md)。歴史台帳は上書きしない。調査mainは`2236ec3714f6d84a52b02d9709533c7792bfa5a6`。

Boot parent 3.5.12 → 最終公開3.5.16。BOMでMicrometer/Spring Data/Framework6.2.19/Security/Jackson2.21.4を整合更新。Tomcat10.1.60 overrideを維持。Flyway11.7.2・MariaDB server12.2を維持し、JDBC3.5.8のBOM patchはセキュリティ是正としてfull verifyで再検証する。

OSV2.6.0・CycloneDX2.9.3・suppressionなし。tomcat **51件 → boot-bom 19件**。今回消失34件、新規2件。残りseverity: {'MODERATE': 11, 'HIGH': 5, 'LOW': 1, 'CRITICAL': 2}。
スキャン実行成功と脆弱性ゼロを区別する。依存更新によるOSV消失は、productionでの悪用が過去に成立していたという証明ではない。
[機械可読比較・全30件のtriage](2026-10-12-issue86-boot-bom.json)にbefore/after ID、GAV、修正版、条件、到達性、互換性、期限、raw evidence SHA256を保存。

## 分割計画と優先順位

1. Tomcat10.1.60: Critical6/High6を含むTomcat findingを優先。10.1.58はrelease vote不成立で採用しない。10.1.55〜59のHTTP/2修正回帰も10.1.60で解消する。
2. Boot3.5.16の公式BOM更新: 基盤familyの組合せを維持して修正版へ進め、Tomcatを10.1.55へ戻さない。
3. Jackson2.21.7 BOM patch: 公式Boot3.5.16の2.21系を揃えて残りJackson advisoryを是正。
PRは順に積み重ね、各headでJava1751件以上/FES0、Terraform27、Secret/Java SECURITY、OSV監査の実CIを確認する。CI base filterにsecurity/issue-86-*を加え、main以外をbaseとするPRでも同じgateを省略しない。merge順はTomcat → Boot → Jackson。

## 公開版では解決できない残余

Spring MVC Critical2件: SSE view fragmentsとXsltView implicit viewの条件を現REST sourceで再確認したが該当利用は見つからない。productionの外部設定/第三者追加は未検証で、安全や無期限受容とは断定しない。https://github.com/utl-flaxy/inventory-management-system/issues/89で2026-10-14までに取得/移行方針、2026-10-26までに是正を決める。
修正版6.2.20はEnterprise Support Only。公開7.0.9だけを理由にFramework majorを変更しない。OSVのCRITICALを保持し、ベンダーのSSE LOW/XSLT MEDIUM評価も併記する。
Boot3.5.16は2026-06-25の最終OSS releaseで、3.5系の継続OSS supportがあるとは主張しない。商用継続か、別途承認するBoot4移行が残る。

## 今回の消失ID

- [GHSA-293q-567p-wmwq](https://github.com/advisories/GHSA-293q-567p-wmwq) (MODERATE)
- [GHSA-3chg-m5w7-qfv5](https://github.com/advisories/GHSA-3chg-m5w7-qfv5) (HIGH)
- [GHSA-3pjw-73gf-8qr5](https://github.com/advisories/GHSA-3pjw-73gf-8qr5) (MODERATE)
- [GHSA-5m4m-73w9-8433](https://github.com/advisories/GHSA-5m4m-73w9-8433) (MODERATE)
- [GHSA-5vpf-xvv7-c8vh](https://github.com/advisories/GHSA-5vpf-xvv7-c8vh) (MODERATE)
- [GHSA-659m-px2c-25wj](https://github.com/advisories/GHSA-659m-px2c-25wj) (LOW)
- [GHSA-6p4f-wcwh-5vvm](https://github.com/advisories/GHSA-6p4f-wcwh-5vvm) (MODERATE)
- [GHSA-72hv-8253-57qq](https://github.com/advisories/GHSA-72hv-8253-57qq) (MODERATE)
- [GHSA-72pg-x5f8-j25j](https://github.com/advisories/GHSA-72pg-x5f8-j25j) (MODERATE)
- [GHSA-7m2p-62gw-p8qq](https://github.com/advisories/GHSA-7m2p-62gw-p8qq) (MODERATE)
- [GHSA-88fw-v6x4-3f58](https://github.com/advisories/GHSA-88fw-v6x4-3f58) (HIGH)
- [GHSA-957g-f97v-vppc](https://github.com/advisories/GHSA-957g-f97v-vppc) (MODERATE)
- [GHSA-9f52-rjqv-25qv](https://github.com/advisories/GHSA-9f52-rjqv-25qv) (LOW)
- [GHSA-9fw2-h3hf-293r](https://github.com/advisories/GHSA-9fw2-h3hf-293r) (HIGH)
- [GHSA-cjpg-rgq5-fr37](https://github.com/advisories/GHSA-cjpg-rgq5-fr37) (MODERATE)
- [GHSA-g3pr-3p32-fp23](https://github.com/advisories/GHSA-g3pr-3p32-fp23) (HIGH)
- [GHSA-ggg2-9786-hwc8](https://github.com/advisories/GHSA-ggg2-9786-hwc8) (MODERATE)
- [GHSA-h3qp-gqrc-q736](https://github.com/advisories/GHSA-h3qp-gqrc-q736) (MODERATE)
- [GHSA-hgj6-7826-r7m5](https://github.com/advisories/GHSA-hgj6-7826-r7m5) (MODERATE)
- [GHSA-j3rv-43j4-c7qm](https://github.com/advisories/GHSA-j3rv-43j4-c7qm) (HIGH)
- [GHSA-jhq6-gfmj-v8fx](https://github.com/advisories/GHSA-jhq6-gfmj-v8fx) (LOW)
- [GHSA-mq64-j8f9-9gcj](https://github.com/advisories/GHSA-mq64-j8f9-9gcj) (MODERATE)
- [GHSA-p47f-322f-whfh](https://github.com/advisories/GHSA-p47f-322f-whfh) (LOW)
- [GHSA-r5w3-xv2f-j59q](https://github.com/advisories/GHSA-r5w3-xv2f-j59q) (HIGH)
- [GHSA-r7wm-3cxj-wff9](https://github.com/advisories/GHSA-r7wm-3cxj-wff9) (HIGH)
- [GHSA-rmj7-2vxq-3g9f](https://github.com/advisories/GHSA-rmj7-2vxq-3g9f) (HIGH)
- [GHSA-vxf7-qj7q-83fh](https://github.com/advisories/GHSA-vxf7-qj7q-83fh) (LOW)
- [GHSA-w737-wx49-qj23](https://github.com/advisories/GHSA-w737-wx49-qj23) (HIGH)
- [GHSA-wg35-8jpf-2xv3](https://github.com/advisories/GHSA-wg35-8jpf-2xv3) (LOW)
- [GHSA-wwpq-f5c3-7hvx](https://github.com/advisories/GHSA-wwpq-f5c3-7hvx) (HIGH)
- [GHSA-wxpp-56q6-5pcg](https://github.com/advisories/GHSA-wxpp-56q6-5pcg) (MODERATE)
- [GHSA-x23c-287f-qqv5](https://github.com/advisories/GHSA-x23c-287f-qqv5) (HIGH)
- [GHSA-x2r2-rvhq-2mqv](https://github.com/advisories/GHSA-x2r2-rvhq-2mqv) (MODERATE)
- [GHSA-x2wq-9x2f-fhj7](https://github.com/advisories/GHSA-x2wq-9x2f-fhj7) (MODERATE)

## Critical/High 30件の再評価

|ID / OSV severity|対象 / 実解決版|状態 / 修正版 / 対応family|
|---|---|---|
|[GHSA-7hhh-6rmp-j9qf](https://github.com/advisories/GHSA-7hhh-6rmp-j9qf) HIGH|`com.fasterxml.jackson.core:jackson-core:2.21.4`|REMAINS / 2.21.7 / jackson|
|[GHSA-p6pp-m3f8-5c89](https://github.com/advisories/GHSA-p6pp-m3f8-5c89) HIGH|`com.fasterxml.jackson.core:jackson-core:2.21.4`|REMAINS / 2.21.7 / jackson|
|[GHSA-r7wm-3cxj-wff9](https://github.com/advisories/GHSA-r7wm-3cxj-wff9) HIGH|`com.fasterxml.jackson.core:jackson-core:2.21.4`|REMOVED_FROM_UNSUPPRESSED_SCAN / 2.21.7 / jackson|
|[GHSA-cxp5-3px4-pw24](https://github.com/advisories/GHSA-cxp5-3px4-pw24) HIGH|`com.fasterxml.jackson.core:jackson-databind:2.21.4`|REMAINS / 2.21.7 / jackson|
|[GHSA-j3rv-43j4-c7qm](https://github.com/advisories/GHSA-j3rv-43j4-c7qm) HIGH|`com.fasterxml.jackson.core:jackson-databind:2.21.4`|REMOVED_FROM_UNSUPPRESSED_SCAN / 2.21.7 / jackson|
|[GHSA-q4xh-88c3-wmh7](https://github.com/advisories/GHSA-q4xh-88c3-wmh7) HIGH|`com.fasterxml.jackson.core:jackson-databind:2.21.4`|REMAINS / 2.21.7 / jackson|
|[GHSA-rmj7-2vxq-3g9f](https://github.com/advisories/GHSA-rmj7-2vxq-3g9f) HIGH|`com.fasterxml.jackson.core:jackson-databind:2.21.4`|REMOVED_FROM_UNSUPPRESSED_SCAN / 2.21.7 / jackson|
|[GHSA-wv8q-qhhj-9h54](https://github.com/advisories/GHSA-wv8q-qhhj-9h54) HIGH|`com.fasterxml.jackson.core:jackson-databind:2.21.4`|REMAINS / 2.21.7 / jackson|
|[GHSA-g3pr-3p32-fp23](https://github.com/advisories/GHSA-g3pr-3p32-fp23) HIGH|`io.micrometer:micrometer-core:1.15.12`|REMOVED_FROM_UNSUPPRESSED_SCAN / 1.15.12 / boot-bom|
|[GHSA-w737-wx49-qj23](https://github.com/advisories/GHSA-w737-wx49-qj23) HIGH|`io.micrometer:micrometer-core:1.15.12`|REMOVED_FROM_UNSUPPRESSED_SCAN / 1.15.12 / boot-bom|
|[GHSA-wwpq-f5c3-7hvx](https://github.com/advisories/GHSA-wwpq-f5c3-7hvx) HIGH|`org.springframework.boot:spring-boot:3.5.16`|REMOVED_FROM_UNSUPPRESSED_SCAN / 3.5.16 / boot-bom|
|[GHSA-88fw-v6x4-3f58](https://github.com/advisories/GHSA-88fw-v6x4-3f58) HIGH|`org.springframework.data:spring-data-commons:3.5.13`|REMOVED_FROM_UNSUPPRESSED_SCAN / 3.5.13 / boot-bom|
|[GHSA-9fw2-h3hf-293r](https://github.com/advisories/GHSA-9fw2-h3hf-293r) HIGH|`org.springframework.data:spring-data-commons:3.5.13`|REMOVED_FROM_UNSUPPRESSED_SCAN / 3.5.13 / boot-bom|
|[GHSA-r5w3-xv2f-j59q](https://github.com/advisories/GHSA-r5w3-xv2f-j59q) HIGH|`org.springframework:spring-expression:6.2.19`|REMOVED_FROM_UNSUPPRESSED_SCAN / 6.2.19 / boot-bom|
|[GHSA-3chg-m5w7-qfv5](https://github.com/advisories/GHSA-3chg-m5w7-qfv5) HIGH|`org.springframework:spring-webmvc:6.2.19`|REMOVED_FROM_UNSUPPRESSED_SCAN / 6.2.19 / boot-bom|
|[GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8) CRITICAL|`org.springframework:spring-webmvc:6.2.19`|REMAINS / 6.2.20 (Enterprise Support Only) / support-decision|
|[GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) CRITICAL|`org.springframework:spring-webmvc:6.2.19`|REMAINS / 6.2.20 (Enterprise Support Only) / support-decision|
|[GHSA-x23c-287f-qqv5](https://github.com/advisories/GHSA-x23c-287f-qqv5) HIGH|`org.springframework:spring-webmvc:6.2.19`|REMOVED_FROM_UNSUPPRESSED_SCAN / 6.2.19 / boot-bom|
|[GHSA-5m62-pw8w-7w9f](https://github.com/advisories/GHSA-5m62-pw8w-7w9f) CRITICAL|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-5mp6-jrq3-r938](https://github.com/advisories/GHSA-5mp6-jrq3-r938) HIGH|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-69cc-cv78-qc8g](https://github.com/advisories/GHSA-69cc-cv78-qc8g) HIGH|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-9xv2-5v5q-p794](https://github.com/advisories/GHSA-9xv2-5v5q-p794) CRITICAL|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-fv25-8xcx-gqjc](https://github.com/advisories/GHSA-fv25-8xcx-gqjc) HIGH|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-gcx9-497g-6cp6](https://github.com/advisories/GHSA-gcx9-497g-6cp6) CRITICAL|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-gx5v-xp9w-j4cg](https://github.com/advisories/GHSA-gx5v-xp9w-j4cg) HIGH|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-h3x4-894j-xpx5](https://github.com/advisories/GHSA-h3x4-894j-xpx5) CRITICAL|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-h6fc-48rj-7qqh](https://github.com/advisories/GHSA-h6fc-48rj-7qqh) CRITICAL|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-r29c-68gh-xp6x](https://github.com/advisories/GHSA-r29c-68gh-xp6x) CRITICAL|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-rv64-5gf8-9qq8](https://github.com/advisories/GHSA-rv64-5gf8-9qq8) HIGH|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|
|[GHSA-x4m4-345f-5h5g](https://github.com/advisories/GHSA-x4m4-345f-5h5g) HIGH|`org.apache.tomcat.embed:tomcat-embed-core:10.1.60`|REMOVED_FROM_UNSUPPRESSED_SCAN / 10.1.60 / tomcat|

各findingの利用条件・到達性・未検証事項は上のJSONに記録。アプリケーションsourceはIssue #80の調査から変わっておらず、今回も関係するAPI、JWT/STATELESS認証、Pageable allowlist、REST-only、HTTP/2/Realm/WebDAV/cluster設定の不在を照合する。未確認のlibrary callを到達不能と断定しない。

## 公式根拠

- [Boot3.5.16とOSS終了](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)、[Java/Servlet互換性](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[管理依存version](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)。
- [Apache Tomcat security](https://tomcat.apache.org/security-10.html)、[10.1.60公開配布](https://tomcat.apache.org/download-10.cgi)。
- [Jackson2.21 release/Java互換性](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21)、[2.21.7 BOM release](https://github.com/FasterXML/jackson-bom/releases/tag/jackson-bom-2.21.7)。
- [SSEの6.2.20商用修正](https://spring.io/security/cve-2026-47890/)、[XsltViewの6.2.20商用修正](https://spring.io/security/cve-2026-47884/)。

実verify件数、警告実測、head SHA、CI URL/job conclusionsはPR本文へ記録。AWS/本番DB/既存volume/Deploy設定/main保護設定を変更しない。

## BOM移行時の新規Moderate 2件

GHSA-5gvw-p9qm-jgwh（JsonView + JsonUnwrapped containerのview gate）とGHSA-mhm7-754m-9p8w（JsonView + external type-id creator gate）がJackson2.21.4で新たに検出された。修正版は2.21.5以降。現application request DTOに該当annotationはなく、実悪用は未確認。
これを消失件数に混ぜず、JSONのnew_idsへ保存する。続くJackson2.21.7 PRで修正・消失を確認するまで、BOM PRを単独の最終是正としてmerge/releaseしない。全stackのレビュー完了後、Tomcat→Boot→Jacksonの順に扱い、途中でDeployを有効化しない。
[JsonUnwrapped advisory](https://github.com/advisories/GHSA-5gvw-p9qm-jgwh)、[external creator advisory](https://github.com/advisories/GHSA-mhm7-754m-9p8w)。JDBC3.5.8へのBOM追従で既存JDBC4件が解消したとは主張せず、#87で追跡する。

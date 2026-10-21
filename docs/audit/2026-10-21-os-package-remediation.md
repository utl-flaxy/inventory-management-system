# Issue #143: Trivy OS49件とLOW18件の是正比較（2026-10-21）

## 対象と判定

基準mainは`96b423e2d17b801ac89e28d0320027832b3077a0`。[main CI 39549981382](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39549981382)を独立に確認し、completed/success・必須5ジョブSUCCESS。元artifactのscanは`2026-10-21T05:45:12.550506218Z`、Ubuntu24.04 / linux/amd64、OS49（MEDIUM27/LOW22）、library0、33 unique CVE。

同じDBで再scanした基準も49件。**採用案はbase digestを維持し、curlとlibcurl4t64のみ`8.5.0-2ubuntu10.15`から`8.5.0-2ubuntu10.16`へ完全版固定で更新する。OS31（MEDIUM27/LOW4）、library0、C/H0、24 unique CVEへ減少**。削減は18 finding instances / 9 CVE。残る31件は是正済みではない。

[Ubuntu USN-8932-1](https://ubuntu.com/security/notices/USN-8932-1)は以下9 CVEとNobleの同版を列挙する。公式`noble-security/main`と`noble-updates/main`のamd64 APT indexで、両packageのcandidate版を実測し、署名検証を無効化せず導入できた。`curl`は既存HEALTHCHECKにも必要で、削除による件数削減はしない。

実装差分は[Dockerfile](../../Dockerfile)のapt install引数1行のみ。既存libfreetype6/libpngの完全版固定、JDK/JRE digest、Java17、UID/GID10001、root所有jar、read-only Compose、capabilities、メモリ予算、readiness、revision、Deploy disabledは維持する。Java・DDL・JWT契約は変更しない。

## 同一条件の候補比較

scannerは`aquasec/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa`。専用cacheへDBを一度取得し、その後は`--skip-db-update --skip-java-db-update`で同じDBを使用。これはDB更新停止であり、脆弱性検査のskipではない。全scanで`--scanners vuln --format json --exit-code 0 --input /scan/image.tar`、severity/ignore/unfixedフィルタなし。image archiveのみread-only mountし、Docker socketを渡さない。DBファイルとmetadataのSHA-256が各候補scan前後で一致することをassertした。

OS DB UpdatedAt: `2026-10-21T07:13:45.702547343Z`、Java DB UpdatedAt: `2026-10-21T01:09:45.346257859Z`。過去のFixedVersion空欄から18件表示への変化はDB更新であり、image是正ではない。今回の49→31は同じDB内のpackage更新比較である。

|候補|OS / library|severity|修正版なし|unique CVE|新規finding|local engine image bytes|
|---|---|---|---|---|---|---|
|基準main|49 / 0|MEDIUM 27 / LOW 22|31|33|基準|509,237,635|
|採用: 既存digest + curl2固定|31 / 0|MEDIUM 27 / LOW 4|31|24|0|510,964,114|
|対照: 公式JRE新digest + 既存apt pins|31 / 0|MEDIUM 27 / LOW 4|31|24|0|498,158,107|

同一local engineのimage inspect Size比較であり、registry転送サイズやCIの別engineのSizeと同じ尺度とは限らない。採用案は+1,726,479 bytes（約0.34%）。3候補とも同一アプリjarを使用し、隔離候補は基準imageからjarをCOPYした。最終PR imageはこれと別にcommitのclean archiveからbuildし、動的回帰する。

公式更新候補は[Adoptium公式image](https://hub.docker.com/_/eclipse-temurin)の`17-jre-noble`を解決し、index `sha256:07fa0356cf354c5252947575f42ed42aa6fd921f36438b7bc77ad81c31c537dd` / amd64 manifest `sha256:e7ee15413d334217ca732978500b310e1a0bb8a5e54d9b6dd12509f636cda99e`で固定。registry annotationの[source commit](https://github.com/adoptium/containers/blob/511f9356dc4d50932a0a5f8cfb0f87ed1aef4f07/17/jre/ubuntu/noble/Dockerfile)とUbuntu24.04、JRE17.0.20.1+1を照合した。既存freetype/pngのpinsと競合・downgradeなし。curl2以外にlibaudit-common/libaudit1/tzdataが変わるが、同一DBでは削減数が同じなので今回は採用しない。候補にHIGH/CRITICAL・library新規・未修正件数増加はなかった。

DB fingerprint、候補image ID/scan日時・全package差分、49行の機械可読表は[比較JSON](../evidence/issue-143-os-comparison.json)。DB blobとimage archive自体の長期保存・bit再現性は保証せず、将来再取得したDBの差は別に記録する。

## 基準49行の全台帳

severityは全行`SeveritySource=ubuntu`、distroは`ubuntu-24.04`、sourceは[Ubuntu CVE Tracker](https://git.launchpad.net/ubuntu-cve-tracker)。NVDのscore/severityとは同一視しない。FixedVersionは元Trivy JSONの値。空欄「—」はこの時点で修正版を報告しない意味で、将来も修正版がないとの断定ではない。各リンクのUbuntu Noble source-package状態も独立取得した。

|package|CVE（Ubuntu一次資料）|severity|installed|FixedVersion|採用後|
|---|---|---|---|---|---|
|curl|[CVE-2026-86409](https://ubuntu.com/security/CVE-2026-86409)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|curl|[CVE-2026-86414](https://ubuntu.com/security/CVE-2026-86414)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|curl|[CVE-2026-87862](https://ubuntu.com/security/CVE-2026-87862)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|curl|[CVE-2026-87863](https://ubuntu.com/security/CVE-2026-87863)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|curl|[CVE-2026-87864](https://ubuntu.com/security/CVE-2026-87864)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|curl|[CVE-2026-92388](https://ubuntu.com/security/CVE-2026-92388)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|curl|[CVE-2026-92389](https://ubuntu.com/security/CVE-2026-92389)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|curl|[CVE-2026-92391](https://ubuntu.com/security/CVE-2026-92391)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|curl|[CVE-2026-94266](https://ubuntu.com/security/CVE-2026-94266)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|dash|[CVE-2026-102473](https://ubuntu.com/security/CVE-2026-102473)|MEDIUM|0.5.12-6ubuntu5|—|残存|
|dash|[CVE-2026-102474](https://ubuntu.com/security/CVE-2026-102474)|MEDIUM|0.5.12-6ubuntu5|—|残存|
|libc-bin|[CVE-2026-18374](https://ubuntu.com/security/CVE-2026-18374)|MEDIUM|2.39-0ubuntu8.9|—|残存|
|libc-bin|[CVE-2026-89092](https://ubuntu.com/security/CVE-2026-89092)|MEDIUM|2.39-0ubuntu8.9|—|残存|
|libc6|[CVE-2026-18374](https://ubuntu.com/security/CVE-2026-18374)|MEDIUM|2.39-0ubuntu8.9|—|残存|
|libc6|[CVE-2026-89092](https://ubuntu.com/security/CVE-2026-89092)|MEDIUM|2.39-0ubuntu8.9|—|残存|
|libcurl4t64|[CVE-2026-86409](https://ubuntu.com/security/CVE-2026-86409)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libcurl4t64|[CVE-2026-86414](https://ubuntu.com/security/CVE-2026-86414)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libcurl4t64|[CVE-2026-87862](https://ubuntu.com/security/CVE-2026-87862)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libcurl4t64|[CVE-2026-87863](https://ubuntu.com/security/CVE-2026-87863)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libcurl4t64|[CVE-2026-87864](https://ubuntu.com/security/CVE-2026-87864)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libcurl4t64|[CVE-2026-92388](https://ubuntu.com/security/CVE-2026-92388)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libcurl4t64|[CVE-2026-92389](https://ubuntu.com/security/CVE-2026-92389)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libcurl4t64|[CVE-2026-92391](https://ubuntu.com/security/CVE-2026-92391)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libcurl4t64|[CVE-2026-94266](https://ubuntu.com/security/CVE-2026-94266)|LOW|8.5.0-2ubuntu10.15|8.5.0-2ubuntu10.16|解消: 8.5.0-2ubuntu10.16|
|libexpat1|[CVE-2025-66382](https://ubuntu.com/security/CVE-2025-66382)|MEDIUM|2.6.1-2ubuntu0.6|—|残存|
|libgnutls30t64|[CVE-2026-67693](https://ubuntu.com/security/CVE-2026-67693)|MEDIUM|3.8.3-1.1ubuntu3.6|—|残存|
|libgnutls30t64|[CVE-2026-88647](https://ubuntu.com/security/CVE-2026-88647)|MEDIUM|3.8.3-1.1ubuntu3.6|—|残存|
|libgnutls30t64|[CVE-2026-95184](https://ubuntu.com/security/CVE-2026-95184)|MEDIUM|3.8.3-1.1ubuntu3.6|—|残存|
|libgnutls30t64|[CVE-2026-95209](https://ubuntu.com/security/CVE-2026-95209)|MEDIUM|3.8.3-1.1ubuntu3.6|—|残存|
|libgnutls30t64|[CVE-2026-95210](https://ubuntu.com/security/CVE-2026-95210)|MEDIUM|3.8.3-1.1ubuntu3.6|—|残存|
|libpcre2-8-0|[CVE-2026-103111](https://ubuntu.com/security/CVE-2026-103111)|MEDIUM|10.42-4ubuntu2.1|—|残存|
|libpcre2-8-0|[CVE-2026-86145](https://ubuntu.com/security/CVE-2026-86145)|MEDIUM|10.42-4ubuntu2.1|—|残存|
|libpcre2-8-0|[CVE-2026-89156](https://ubuntu.com/security/CVE-2026-89156)|MEDIUM|10.42-4ubuntu2.1|—|残存|
|libpcre2-8-0|[CVE-2026-89157](https://ubuntu.com/security/CVE-2026-89157)|MEDIUM|10.42-4ubuntu2.1|—|残存|
|libpcre2-8-0|[CVE-2026-89158](https://ubuntu.com/security/CVE-2026-89158)|MEDIUM|10.42-4ubuntu2.1|—|残存|
|libpcre2-8-0|[CVE-2026-89160](https://ubuntu.com/security/CVE-2026-89160)|MEDIUM|10.42-4ubuntu2.1|—|残存|
|libpcre2-8-0|[CVE-2026-89161](https://ubuntu.com/security/CVE-2026-89161)|MEDIUM|10.42-4ubuntu2.1|—|残存|
|libsasl2-2|[CVE-2026-107161](https://ubuntu.com/security/CVE-2026-107161)|MEDIUM|2.1.28+dfsg1-5ubuntu3.1|—|残存|
|libsasl2-modules-db|[CVE-2026-107161](https://ubuntu.com/security/CVE-2026-107161)|MEDIUM|2.1.28+dfsg1-5ubuntu3.1|—|残存|
|libsystemd0|[CVE-2026-40228](https://ubuntu.com/security/CVE-2026-40228)|LOW|255.4-1ubuntu8.17|—|残存|
|libudev1|[CVE-2026-40228](https://ubuntu.com/security/CVE-2026-40228)|LOW|255.4-1ubuntu8.17|—|残存|
|locales|[CVE-2026-18374](https://ubuntu.com/security/CVE-2026-18374)|MEDIUM|2.39-0ubuntu8.9|—|残存|
|locales|[CVE-2026-89092](https://ubuntu.com/security/CVE-2026-89092)|MEDIUM|2.39-0ubuntu8.9|—|残存|
|login|[CVE-2024-56433](https://ubuntu.com/security/CVE-2024-56433)|LOW|1:4.13+dfsg1-4ubuntu3.2|—|残存|
|passwd|[CVE-2024-56433](https://ubuntu.com/security/CVE-2024-56433)|LOW|1:4.13+dfsg1-4ubuntu3.2|—|残存|
|tar|[CVE-2026-18477](https://ubuntu.com/security/CVE-2026-18477)|MEDIUM|1.35+dfsg-3ubuntu0.4|—|残存|
|tar|[CVE-2026-18508](https://ubuntu.com/security/CVE-2026-18508)|MEDIUM|1.35+dfsg-3ubuntu0.4|—|残存|
|wget|[CVE-2021-31879](https://ubuntu.com/security/CVE-2021-31879)|MEDIUM|1.21.4-1ubuntu4.5|—|残存|
|zlib1g|[CVE-2026-85091](https://ubuntu.com/security/CVE-2026-85091)|MEDIUM|1:1.3.dfsg-3.1ubuntu2.2|—|残存|

## 残り31件と配布確認

残31件は24 CVE。UbuntuのNoble source-package表は`Vulnerable`または`Vulnerable, fix deferred`で、固定修正版を確認できなかった。APT indexも各binary packageについて確認した。systemdのlibsystemd0/libudev1には`255.4-1ubuntu8.18`が配布されるが、CVE-2026-40228の修正版とは公式trackerもTrivyも記載していない。単に新しい版があることを是正根拠にせず、今回は導入しない。

|source package|残finding数|Noble公式状態|
|---|---|---|
|cyrus-sasl2|2|Vulnerable, fix deferred|
|dash|2|Vulnerable, fix deferred|
|expat|1|Vulnerable, fix deferred|
|glibc|6|Vulnerable|
|gnutls28|5|Vulnerable, fix deferred|
|pcre2|7|Vulnerable|
|shadow|2|Vulnerable, fix deferred|
|systemd|2|Vulnerable, fix deferred|
|tar|2|Vulnerable|
|wget|1|Vulnerable, fix deferred|
|zlib|1|Vulnerable|

修正可否と実到達性は別問題。curlの複雑なプロトコル機能の実利用を確認していないことを「安全」の根拠にせず、公式修正版を採用する。未修正OS機能についても一律到達不能・安全とは扱わず、更新候補を継続監査する。

## 回帰とレビュー証跡の読み方

最終PR本文とそのHEADのCI runを受入証跡とする。Java17 full verify（Surefire1,384 + Failsafe986 =2,370以上、F/E/S=0）、Python29、Terraform mock27、実Gitleaks正常/異常・例外0、SpotBugs SECURITY正常/異常、OSV169/0を維持する。Dockerはclean archive build、元の業務9段階、B1 logout-all/B2 password/B3 ADMIN再認証・強制/自己失効、V1〜V9、readiness/JRE/non-root/read-only、DB ID・起動時刻・注文在庫不変を実測する。localとCIを別の実行として記録する。最終imageのpackage版とTrivy全findingを再照合し、比較用DBとCI取得DBの時刻を混同しない。

Trivyは既存audit-onlyを維持し、処理成功は脆弱性ゼロを意味しない。scanner/取得/解析エラーは成功扱いしない。Javaテスト・CI workflow・pool/timeout・secret例外・既存migrationは変更しない。初回隔離buildで調査helperの生成Dockerfileに余分な`+`が入りaptが失敗したが、helperを修正して再buildした。これはpackage互換性の失敗ではなく、本番Dockerfile/検査の緩和は行っていない。

## 残余リスク・運用境界

- OS31件（MEDIUM27/LOW4）を残す。OSV0とTrivy0を混同しない。監査対象はapp imageであり、host/Caddy/MariaDB image全体を網羅しない。
- 既存Flyway/MariaDB互換性WARN、Windows DatabaseTimeoutITの過去の初回DB観測CLI3失敗（根因未確定）、MariaDB/Caddy証明書時刻事象の過去観測を保持する。今回成功しても根因解決とはしない。
- rollbackの実証は同一image IDの設定故障からのapp-only復帰。異なる旧binary・旧鍵・DB backupへのrollbackの互換性と失効境界は未保証。
- exact pinがmirrorから取得不能になればbuildを失敗させ、旧版fallbackや無署名取得はしない。DBと配布状況は時間変化するため、採用時にも実scanを確認する。
- JWT個別失効のIssue #134は保留。B1〜B3の世代失効を維持し、jtiは導入しない。
- AWS/Deploy/実secret/本番DB/既存volumeへ操作なし。PR #103は対象外。PRは未マージ・auto-mergeなしで独立レビューを待つ。**PRODUCTION NO GO**。

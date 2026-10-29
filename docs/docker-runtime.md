# Docker runtime・再現性・メモリ設計 — Issue #76

## CVE-2026-111497の限定是正（2026-10-29 / Issue #171）

既存の公式JRE17/Noble digestと他の固定package版を維持し、libbrotli1をUbuntu公式backport修正版`1.1.0-2ubuntu0.1`へ限定更新する。
OS比較用imageではOS33（HIGH1/MEDIUM28/LOW4）から32（HIGH0/MEDIUM28/LOW4）、新規finding0。
公式JRE更新案との比較、全finding表、実アプリ/CIとの証拠の区別は[是正記録](audit/cve-2026-111497-runtime-remediation-2026-10-29.md)へ。
過去のOS31/49や以下の監査記録は当時のsnapshotとして保存する。P2解除・正式GOは独立レビュー後、本番NO GOを維持する。

## Issue #143のOS package更新（2026-10-21）

main `96b423e2d17b801ac89e28d0320027832b3077a0`のOS49件を全件照合した。
既存JRE17/Nobleのdigestとfreetype/png固定版を維持し、curl/libcurl4t64だけを
`8.5.0-2ubuntu10.16`へ完全版固定で更新する。同一Trivy scanner/DBで
**OS49（MEDIUM27/LOW22）→31（MEDIUM27/LOW4）、library0、新規finding0**。
全台帳、公式配布根拠、採用しなかった公式JRE候補とDB hashは[比較監査](audit/2026-10-21-os-package-remediation.md)を参照。
以下のOS49や旧snapshotは過去の証跡として保持する。新HEADの回帰結果は当該PR/CIで別に確認する。
Trivyはaudit-onlyを維持し、残31件・Flyway WARN・同一image設定復帰に限るrollback・本番NO GOを保持する。

## B3の動的検証境界

[harness](../scripts/docker/verify_image.py)はV1〜V9、元の9業務段階とB1/B2に加えADMIN再認証付き強制失効・自己失効を検証する。
対象世代3/ADMIN世代1、監査4行、旧JWT401と再loginを確認し、同一image設定故障復帰後にも保持を照合する。
Dockerfile/Compose/メモリ/非root/read-only rootfs/Deploy契約は変更しない。別binary rollback・本番容量/SLA/実AWS/実TLSは保証しない。

Issue #128時点の監査snapshot: main **`186aad8cb41fa7d7227304aeed4a4c3d6436ad69`**、Java17 / **Spring Boot4.1.1**。
[当時のmain CI](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39123950859): Java2,176、必須5ジョブSUCCESS。
OSV169 components/0 advisory、Trivy **OS49（MEDIUM27/LOW22）/ library0**。
現在のmain CIは[live確認先](release-status.md#現在のmain-cilive)で確認し、上の固定記録と区別する。
監査時点の値と実証限界は[監査証跡](release-status.md)、再現コマンドは[隔離デモ](demo.md)を参照。

**履歴:** Issue76導入時はmain `74e8d953f8c132c5c0ebf79accc0f784cd75af1e`、Boot3.5.16だった。
[当時のimage/容量/旧JDK対照/Boot4 PoC](audits/issue-76-validation.json)はそのSHAの記録として保存し、現行Boot4正式採用後の結果と混同しない。
2026年6月のEC2はt3.micro・2 vCPU・1 GiBだったという過去の確認値がある。
現在の稼働instance type・実使用可能RAMは未確認。Terraformの既定値もt3.microだが、
実host容量の証明にはならない。AWSへのアクセス、instance変更、本番操作は行わない。

## イメージと移行

Issue #136のB1ではruntime/Composeの権限・メモリ設定を変えず、Java V7とUUID/世代JWTを追加する。
Issue #138のB2も同設定を維持し、V8の監査action拡張と本人password変更を追加する。
従来9段階とB1に続き、新passwordで再login・両旧世代JWT401・更新hash/世代/監査の同一image再起動後の保持を検証する。
旧validatorの停止/drainと再ログインが必要。[専用runbook](security/jwt-revocation-migration.md)を参照。
Dockerデモのrollbackは同じB1 imageの設定復帰だけで、世代を読まない旧binaryへ戻す許可ではない。
上記v1.0監査snapshotは歴史的な値を保持し、B1の再計測は当該PR/新HEADのCIで確認する。

旧構成はhost Mavenで生成したjarを、単一stageのTemurin JDK 17にCOPYしroot実行する。
新構成は公式Temurin JDK 17のbuild stageと、JRE 17のruntime stageに分離する。
JDK/JREはUbuntu 24.04 Noble・17.0.20.1+1の公式multi-architecture index digestへ固定。
Dockerfileの完全digestをregistry manifestと公式image sourceで確認した
([Temurin公式image](https://hub.docker.com/_/eclipse-temurin))。
動的検証対象はlinux/amd64で、他architectureの実行を検証済みとはしない。
digest更新は正式release、Java patch、OS packages、image scan、全回帰のレビューPRで行う。
可変tagの自動追従や無検証のapt upgradeは使用しない。
初回image scanでbase由来の修正可能なMedium 2件を確認し、Ubuntu公式の公開済み修正版だけを
version指定で導入する。APTは公式repositoryの署名・package hashを検証し、
無署名許可や一括upgrade、未公開versionの採用は行わない。

| CVE / 一次資料 | package | 固定修正版 |
| --- | --- | --- |
| [CVE-2026-95512](https://ubuntu.com/security/CVE-2026-95512) | libfreetype6 | 2.13.2+dfsg-1ubuntu0.2 |
| [CVE-2026-46675](https://ubuntu.com/security/CVE-2026-46675) | libpng16-16t64 | 1.6.43-5ubuntu0.7 |

修正版がmirrorで取得不能ならbuildを失敗させ、古い版へfallbackしない。
package配布物の保存・APT timestamp・BuildKit version等までの完全なbit再現を主張せず、
source/base digest/package version/jar timestampを固定したclean buildを検証する。

ビルド順序はwrapper/POM → dependency cache → production source → executable jar。
BuildKitの `/root/.m2` cache mountを2つのMaven処理で共有し、runtimeにはCOPYしない
([Docker公式のcache説明](https://docs.docker.com/build/cache/optimize/))。
contextはDockerfile、POM、wrapper properties、mvnw、src/mainだけを許可する。
host target、Git、tests、Terraform、.deploy、.env、鍵、Maven settingsは送信しない。
runtimeのjarはroot所有0444、/appはroot所有0755、UID/GIDは固定10001:10001。
JDK/compiler、Maven、jcmd、builder cache、build secretsをruntimeへ持ち込まない。
Composeはread-only rootfs、ALL capabilities削除、no-new-privileges、PID上限256、
書込み先を32 MiBの/tmp tmpfs（noexec/nosuid）だけに制限する。

新Dockerfileは `java -jar /app/app.jar` を使うためBoot固有launcher class名へ依存しない。
Docker healthcheckも既存の/readyzを使い、公開health詳細を増やさない。
health-path labelとOCI revision labelを保持する。Dockerのunhealthy状態だけでは自動restartしない。
DeployはHTTP 200かつstatus UPだけを確認し、DB利用不能時にcutoverを成功扱いにしない。

clean checkoutでの例（production secretは不要）:

```bash
docker build --build-arg REVISION="$(git rev-parse HEAD)" \
  --build-arg SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)" \
  -t inventory-management-system-app:local .
```

SOURCE_DATE_EPOCHをMavenのproject.build.outputTimestampへ渡し、jar entry timestampを固定する。
これはMaven Central等の取得可能性や配布物の信頼を保証するものではない。
assemblyの `-DskipTests` は旧host package処理をDocker内へ移したもので、
CIのmandatory `./mvnw verify` を置換しない。full verifyのskipは0を要求する。

APP_MEMORY_LIMITは空のままの.env.exampleへ追加した。容量確認後にhostの.envと
GitHub Repository Variableへ明示する。未設定/形式不正ならSSH・切替前に失敗し、
既存app/DBを変更しない。本Issueで本番値を設定したとは扱わない。
DB serviceと既存volumeの定義を変更しない。appだけのupではDB resource変更は適用されない。

## JVMとcontainerの予算

JVM設定は[Java 17公式仕様](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html)に基づく。
Javaのcontainer認識を実際の1 GiB制約で検証し、MaxHeapSizeを記録する。
heapはcontainer RAMの25%、初期heapは10%。heap上限だけではRSSを制限できない。

| 項目 | runtimeの境界 / 計画値 |
| --- | --- |
| Java heap | MaxRAMPercentage=25、InitialRAMPercentage=10 |
| Metaspace | MaxMetaspaceSize=160 MiB |
| NIO direct memory | MaxDirectMemorySize=32 MiB |
| JIT code cache | ReservedCodeCacheSize=64 MiB |
| Java thread stack | Xss=512 KiB。PID上限256に対して最大約128 MiBを計画 |
| その他native / VM / allocator / library | 128 MiBの計画余裕。厳密な上限ではない |
| /tmp | tmpfs上限32 MiB。使用分はcontainer memoryに含まれる |
| container limit | APP_MEMORY_LIMITで明示、memswap_limitも同値としてswap依存を避ける |

1 GiB app containerではheap256 + metaspace160 + direct32 + code64 + stacks128 +
その他native128 + tmp32 = **800 MiB**、残り224 MiBをimage page cache等の余裕にする。
各領域は通常すべて最大までcommitされないが、平常時RSSだけを容量設計に使わない。
非JavaのVM thread stackやcompiler、socket buffer等はnative側の余裕へ含める。
大量並行接続、CSV、DB result、classloading、GCが増える実負荷は別途計測する。
PID上限は過剰threadの無制限増加を防ぐが、高負荷時のthread生成失敗もあり得る。

| host容量 | app設定例 | MariaDB計画 | OS/Docker等 | 判断・build時の余裕 |
| --- | --- | --- | --- | --- |
| 1 GiB | 安全な本番値を提示しない | DBだけで数百MiB必要 | OS/kernel/Docker・page cacheも必要 | appのnative/metaspaceとDBを同居させる安定余裕が不足。host上Maven buildも不成立。採用非推奨 |
| 2 GiB | APP_MEMORY_LIMIT=1g（heap256 MiB） | 512 MiB、buffer pool128 MiB程度・max connections50から評価 | 512 MiB計画 | runtimeだけでも合計2 GiB。低負荷PoC向けで、同時host buildの追加約1 GiBを確保できない |
| 4 GiB | APP_MEMORY_LIMIT=1g | 768 MiB計画、buffer pool256〜384 MiB程度から実測 | 768 MiB計画 | build約1 GiB + 共通余裕512 MiB。現行host build方式の推奨開始点 |
| 8 GiB以上 | APP_MEMORY_LIMIT=2g（heap512 MiB） | 2 GiB計画、buffer pool1 GiB程度から実測 | 1 GiB計画 | build1 GiBに加え約2 GiB余裕。負荷と接続数に応じて再計測 |

MariaDBの数値は容量計画の例であり、本番設定を変更していない。
buffer poolだけでなくconnection/thread、sort/join/temp table、InnoDB log、page cacheを見積もる。
DB memory limitを既存containerへ新規に適用する操作は行わない。
4 GiB以上への増強が必要な場合の理由は、常駐app+DB+OSに加え、
旧appを止めずに行うJDK/Maven buildとimage unpackの同時メモリが必要なため。
2 GiBでbuildを外部化するには別のimage配布・認証・provenance設計が必要で、本Issueで導入しない。
既存1 GiB hostで新Deployを有効化する判断は、容量確認と増強/独立build設計のレビュー後とする。
Terraform instance_type既定値は勝手に変更しない。

## OOMと運用

Java heap/metaspaceのOOMはExitOnOutOfMemoryErrorで非0終了する。
秘密を含み得るheap dumpは有効化せず、rootfsへ出力しない。
kernel/cgroup OOMならSIGKILL/exit137の可能性があり、Javaのshutdown処理は保証されない。
既存restart=unless-stoppedはOOMを治すものではなく、容量不足ではrestart loopになる。
readiness失敗時は既存Deployのapp-only rollbackで旧imageへ復元する。
運用者はcontainer OOMKilled/restart count、cgroup peak、RSS、thread数、DB使用量、
host free/available memoryとswapを確認し、必要なら配備を止めて容量を見直す。
本番監視endpointやheap dump公開を追加しない。

## 動的検証・証跡とrollback

`python3 scripts/docker/verify_image.py --scan` はGit archiveのclean sourceからbuildし、
毎回固有名のnetwork/containersを作成する。MariaDBのデータは新規tmpfsに置き、
既存volumeを使用しない。新規resourceはnonce label確認後にだけcleanupする。
全体のdown、volume削除、Dockerのglobal cleanupは行わない。
credentials/JWTは使い捨てrandom値をprivate temp fileで渡し、報告・ログでは値を除去する。
ADMIN fixtureは使い捨てDBの専用最小権限userと、明示offline provisioning CLIで作成する。
公開register・通常起動・migrationからADMINを作らない。production user管理の変更ではない。

検証はnon-root、JRE/tool不在、read-only権限、bounded memory、readiness、JWT署名、
USER/ADMIN認可、注文/在庫/取消・冪等性、headers、errors、V1〜V6、実app-only rollbackを含む。
DB接続不能なtargetのreadiness失敗後に直前設定を復元し、DB ID/起動時刻とbusiness dataの不変を確認する。
**標準CI/デモは--baseline-image未指定でcurrentとprevious_image_idが同一**。
同一imageの設定故障からのapp-only復帰を実証し、異なる旧バイナリとの互換rollbackを保証しない。
Issue76当時のlocal拡張検証（旧JDK/root image、384/512MiB起動、heap OOM、PR #93のBoot4 runtime PoC）は上記履歴JSONへ分離する。
小容量でREADYになっても継続負荷・OS同居の安全を証明したとは扱わない。

Trivy 0.75.0の公式scanner imageもdigest固定する
([公式利用説明](https://trivy.dev/docs/latest/getting-started/))。
scannerへDocker socketを渡さず、docker saveした対象imageだけをread-onlyで渡す。
OS/Javaの全findingをJSON保存し、severity/filter/ignoreによる隠蔽はしない。
findingがある監査処理成功と脆弱性ゼロを区別し、tool/DB取得/解析エラーはCIを失敗させる。
Issue76当時のOSV8件は履歴JSONに保持。現行mainはOSV0件、Trivy OS49件であり、同一対象の改善件数として合算しない。

CIは既存5ジョブを維持し、test jobでfull verify後にDocker build/API/rollback/image auditを実行する。
test timeoutはDocker検証分を含め20分に拡張。skipやcontinue-on-errorは追加しない。
Docker summary、redacted logs、raw image scanは30日artifactへ保存する。
localとCIの実測はPR本文で区別する。GitHubのrunnerとEC2が同じRAMだとは仮定しない。

移行は旧appのimage IDと解決済みComposeを保存してから新sourceをbuildし、
新image ID・revision・/readyzを確認してappのみ切り替える。
失敗時は保存済み旧image/Composeへ戻す設計である。旧JDK/rootの対照実験はIssue76当時の証拠に限定し、将来すべての旧imageと新schemaの互換性を保証しない。
新しいread-only/user/memory設定を旧snapshotへ強制しない。image pruneやDB migrationの書換えは行わない。
Deployはdisabledを維持し、本番採用・容量決定・PR mergeはユーザーのレビューを待つ。

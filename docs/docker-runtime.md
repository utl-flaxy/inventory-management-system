# Docker runtime・再現性・メモリ設計 — Issue #76

基準main: `74e8d953f8c132c5c0ebf79accc0f784cd75af1e`。Java 17 / Spring Boot 3.5.16を維持する。
2026年6月のEC2はt3.micro・2 vCPU・1 GiBだったという過去の確認値がある。
現在の稼働instance type・実使用可能RAMは未確認。Terraformの既定値もt3.microだが、
実host容量の証明にはならない。AWSへのアクセス、instance変更、本番操作は行わない。

## イメージと移行

旧構成はhost Mavenで生成したjarを、単一stageのTemurin JDK 17にCOPYしroot実行する。
新構成は公式Temurin JDK 17のbuild stageと、JRE 17のruntime stageに分離する。
JDK/JREはUbuntu 24.04 Noble・17.0.20.1+1の公式multi-architecture index digestへ固定。
Dockerfileの完全digestをregistry manifestと公式image sourceで確認した
([Temurin公式image](https://hub.docker.com/_/eclipse-temurin))。
動的検証対象はlinux/amd64で、他architectureの実行を検証済みとはしない。
digest更新は正式release、Java patch、OS packages、image scan、全回帰のレビューPRで行う。
可変tagの自動追従や無検証のapt upgradeは使用しない。

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
ADMIN fixtureの付与は使い捨てDBだけのSQL操作であり、production user管理の変更ではない。

検証はnon-root、JRE/tool不在、read-only権限、bounded memory、readiness、JWT署名、
USER/ADMIN認可、注文/在庫/取消・冪等性、headers、errors、V1〜V6、実app-only rollbackを含む。
DB接続不能なtargetのreadiness失敗後に旧imageを復元し、DB ID/起動時刻とbusiness dataの不変を確認する。
localの拡張検証は旧JDK/root imageとのrollback、384/512 MiBの起動試験、意図的heap OOM、
PR #93の既存jarを使うBoot 4 runtime互換PoCを別途記録する。
小容量でREADYになっても継続負荷・OS同居の安全を証明したとは扱わない。

Trivy 0.75.0の公式scanner imageもdigest固定する
([公式利用説明](https://trivy.dev/docs/latest/getting-started/))。
scannerへDocker socketを渡さず、docker saveした対象imageだけをread-onlyで渡す。
OS/Javaの全findingをJSON保存し、severity/filter/ignoreによる隠蔽はしない。
findingがある監査処理成功と脆弱性ゼロを区別し、tool/DB取得/解析エラーはCIを失敗させる。
OSVの既存8 advisoryについても別途再監査する。

CIは既存5ジョブを維持し、test jobでfull verify後にDocker build/API/rollback/image auditを実行する。
test timeoutはDocker検証分を含め20分に拡張。skipやcontinue-on-errorは追加しない。
Docker summary、redacted logs、raw image scanは30日artifactへ保存する。
localとCIの実測はPR本文で区別する。GitHubのrunnerとEC2が同じRAMだとは仮定しない。

移行は旧appのimage IDと解決済みComposeを保存してから新sourceをbuildし、
新image ID・revision・/readyzを確認してappのみ切り替える。
失敗時は保存済み旧image/Composeへ戻し、旧imageがJDK/rootでも既存契約で復旧できる。
新しいread-only/user/memory設定を旧snapshotへ強制しない。image pruneやDB migrationの書換えは行わない。
Deployはdisabledを維持し、本番採用・容量決定・PR mergeはユーザーのレビューを待つ。

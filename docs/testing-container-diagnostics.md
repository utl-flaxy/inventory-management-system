# Testcontainers失敗時の安全な診断（Issue #145 / 2026-10-26）

目的は再発時の観測性であり、Windows/WSL2の時計差・certificate not yet validの根因修正ではない。
[Issue #145](https://github.com/utl-flaxy/inventory-management-system/issues/145)はOPEN、
[#166本番受入ゲート](https://github.com/utl-flaxy/inventory-management-system/issues/166)のBLOCKED/PRODUCTION NO GOは変わらない。

## 対象と実行

[ContainerDiagnostics](../src/test/java/com/example/demo/support/ContainerDiagnostics.java)はtest scopeのみ。
DatabaseTimeoutIT、InventoryConcurrencyMariaDbIT、CaddyProxyITへ明示的に統合した。他の全ITへ自動適用してはいない。

通常は失敗時だけCONTAINER_DIAGNOSTICという1行JSONを標準エラーへ出す。
正常時に追加のDocker inspect/exec/certificate取得は行わない。テスト/操作の時刻markだけを保持する。
明示モードは次の通り。追加観測がタイミングへ影響し得るため、通常の性能/再現率と区別する。

    ./mvnw -Dtest.container.diagnostics=always -Dit.test=CaddyProxyIT,DatabaseTimeoutIT verify

Windows PowerShellではmvnw.cmdを用い、ドットを含む-D引数全体を引用する。
この例は対象ITでありfull verifyではない。通常の全回帰は ./mvnw verify。
診断対象はテストが新規作成したcontainerだけ。既存のDocker volume・本番DB・AWSへ接続しない。

## 取得する値と限界

|値|意味・取得不能の扱い|
|---|---|
|startedUtc / observedUtc / elapsedNanos|UTCの操作/テスト開始と失敗観測、単調時計の経過時間。実際のTLS内部検証瞬間の計測ではない|
|Java/OS/TC/Docker版|数値版とOS種別だけ。任意のsystem property・環境変数・docker info全文を出さない|
|containerState / containerExit / oomKilled|inspectから選択した値のみ。state.error、health出力、image名・ID・環境・mount・commandは出さない|
|certificates|公開証明書のnotBefore/notAfterをUTC化。CN/DN/SAN/PEM/鍵は出さない。fileの日時は失敗handshakeそのものの証明書と同一とは限らない|
|MariaDB証明書|固定server-cert.pemを試し、不在なら認証情報なしのsocket TLSメタデータprobe。verify_return_errorを維持し、失敗終了コードも記録。自己署名拒否等をTLS接続成功とはしない。取れなければUNAVAILABLE|
|Caddy証明書|このITの固定local root/intermediate/leafだけを読む。client-diagnosticは先頭4KiBを既知分類に変換し、本文は捨てる|
|clock|dateのepochとhost前後UTC、観測の単調経過。guest-host差は [guest-hostAfter, guest-hostBefore] の区間。逆行wall clockは無効区間。どちらが正確なUTCかは断定しない|
|exceptionClasses / sqlStates|causeとSQLException nextを最大16ノード、循環防止で処理。許可したクラス/SQLSTATE形式だけ。message/stack/SQL/URL/資格情報は出さない|
|cliExit / cliClass / clientErrorClass|CLI結果を変更せず記録。既知TLS分類以外はOTHER。CLI stdout/body/headers/argvは記録しない|
|phase / waitClass|WAIT、STARTUP、TEST、CLIを分ける。wait失敗を証明書原因と自動断定しない|
|collectionStatus|COMPLETEは収集処理完了であり全項目取得成功ではない。TIME_BUDGET、INTERRUPTED、FAILED_OR_BUSYや個別UNAVAILABLEを許容する|

証明書の解析対象は最大64KiB、Caddy stderrの読取は4KiBに制限（Docker/exec受信バッファ全体の上限保証ではない）。秘密鍵ファイルは読まない。
認証情報、JWT、password/hash、username、raw IP、cert本文、ログ全文、inspect全文を出力しない。
CaddyのclientErrorClassは固定診断ファイルの最終内容の分類であり、別の後続assert/HTTP要求の失敗原因と同一とは断定しない。
TLSクライアントの検証・CA/hostname検証や既存assertは変更していない。
この保証は診断JSONの出力に対するもの。上流Testcontainers/Maven等のログ全文を安全にするDLPではなく、生ログ全体を無加工で共有しない。

## cleanupと失敗保持

- MariaDB/Caddyのwaitを例外透過のhookで囲み、TCが失敗containerを除去する前に収集する。
- wait以前のプロセス終了はcontainerIsStoppingで収集する。元のstartup failureとTCのcleanupは維持する。
- CLI非zeroは既存assertの前に収集し、同じExecResultを返す。IOException等も同じ例外を再throwする。
- JUnitのテスト/BeforeAll/BeforeEach失敗はOnFailureで観測して同じThrowableを再throwする。失敗を成功へ変更しない。
- 診断側の例外や出力失敗は元の失敗を置き換えず、機密値を含み得るsuppressed exceptionも追加しない。
- Docker収集は最大1 daemon worker・待ち行列なし、呼出側の待機は3秒。停止不能なDocker I/Oが残ると後続はFAILED_OR_BUSYになり得る。interruptがDocker I/Oを必ず中止する保証はない。
- 診断時間は元のテスト/CLIのelapsed値へ加算せず別に観測する。元のtimeout・retry・pool・DB待機判定は変えない。
- container除去済み、JVM強制終了、Docker API停止、paused container等では一部/全証拠を取得できない。実host/WSL VM時計そのものや失敗時の完全peer chainは保証しない。

## 回帰と失敗履歴

[unit](../src/test/java/com/example/demo/support/ContainerDiagnosticsTest.java)はUTC/区間/単調時計、例外循環と秘密canary、CLI結果・例外同一性、診断/出力故障、収集期限を検証。
[実container IT](../src/test/java/com/example/demo/support/ContainerDiagnosticsIT.java)はMariaDB CLI exit23、意図的なWait失敗、起動exit17を使い捨て環境で注入し、cleanup前の値と失敗/cleanupの維持を確認する。
故障を期待してassertするテストであり、業務テストの失敗を握りつぶす処理ではない。

2026-10-26 Windows初回対象検証ではCaddy5件中1失敗（CLI exit1）を観測し、status・証明書期間・clockをcleanup前に取得した。初期のMariaDB診断fixtureも証明書file不在のassertで1失敗し、メタデータ取得経路を補った。後続成功でこの履歴を消さない。
最初のCaddy失敗ではstderr分類の収集前だったため原因は未確定。過去の2026-10-25 Windows TLS拒否、DB初期化/Wait/Caddy/CLI初回失敗と同根因だとはしない。
修正後の件数・取得率とWindows/Linux CIの比較はPRのexact-head検証結果に記録する。

Issue #121 / PR #122のnative DB lock判定、409/rollback/同key retry/pool0は変更しない。
Linux CI成功やWindowsでの後続成功を根因修復の証明としない。OS32、Flyway WARN、#75 TF-C BLOCKED、#134 jti保留、異binary rollback未検証を維持する。

# C05: CloudTrail digest検証とaudit IAMのオフライン設計審査

2026-10-23 / [Issue #152](https://github.com/utl-flaxy/inventory-management-system/issues/152)、親[Issue #75](https://github.com/utl-flaxy/inventory-management-system/issues/75)。
基準main **`999fad4f0f75ad5268822d30854a94114402f26c`**。
[TF-A ADR](../design/terraform-remote-state.md)、[TF-C0審査](../operations/terraform-remote-state-preflight-2026-10-21.md)、[TF-C準備](../../terraform-sandbox-tests/README.md)の**追補**であり、過去の判定や測定値は変更しない。

**結論: source上の不整合を確認。C05は未解消、TF-C実行BLOCKED、実AWS受入0件。**
Prefixを維持するB案を次の技術検証候補とするが、ツールの採用・実IAM変更は未承認。
固定CLI版への権限追加だけでは、backfill・欠落・時間範囲を含むT14受入を完了できない。
B01〜B14は全BLOCKED、T01〜T17は全NOT_RUN、C01/C02もBLOCKED。Issue #75はOPEN、**PRODUCTION NO GO**を継続する。

## 1. 調査の境界と再現可能な正本

公開GitHubソースとAWS公式文書を取得し、ソースを読解・AST解析した。AWS CLIのinstall/import/実行、AWS CLI/SDK/API呼出し、SSO/STS/IAM simulation、credential/configの閲覧は行っていない。
remote init、実plan/apply、既存state・tfvars・secret・本番DB・既存volumeにもアクセスしていない。
この「オフライン」は**AWS実環境に無接続**の意味。公開資料の取得と通常のCI依存取得までネットワーク不使用だったとは主張しない。

比較対象はAWS CLI **2.31.18**。最新・推奨版の認定ではない。
[公式tag](https://github.com/aws/aws-cli/tree/2.31.18)のannotated tag object `a0dda1d7449a81d2be6f1a6a669de30452487169`を辿り、commit **`f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc`**と一致させた。GitHub Releaseの存在を前提としない。
以下のblob IDは取得bytesからGit blob形式でも再計算し、公式GitHubのcontents応答と照合した。

|固定source|Git blob ID|取得bytesのSHA-256|
|---|---|---|
|[validation.py](https://github.com/aws/aws-cli/blob/f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc/awscli/customizations/cloudtrail/validation.py)|`f4229bade4512ec5ec32085e293a6e35fb5d7356`|`8e9544ecf2cddd241cc23e9910f61726ee8958c5e23c5148515b5cfdf09a3dc0`|
|[utils.py](https://github.com/aws/aws-cli/blob/f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc/awscli/customizations/cloudtrail/utils.py)|`8273a5278ac871b99f007a720e0ede0bc8df8c9e`|`83087b282c7f25fce9969895f71c0895f2585f1c8b2220c9d2b7257faa63d4ab`|
|[command登録](https://github.com/aws/aws-cli/blob/f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc/awscli/customizations/cloudtrail/__init__.py)|`fda5a0e29cf6acd7a781d74963f41662c27d7a5a`|`55eaf828b332d5d12e318357f4d8e5fb2165141c0b65fcd9d7206fe63ad66200`|
|[上流test_validation.py](https://github.com/aws/aws-cli/blob/f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc/tests/unit/customizations/cloudtrail/test_validation.py)|`6c9a28599994188dc92fc08b6af09c7a2fe41604`|`f44daf72b5fb97397381fd4b811ddb2d00e7eed133c23f9fe835d94b77e77b09`|

上流は[Apache-2.0](https://github.com/aws/aws-cli/blob/f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc/LICENSE.txt)。取得sourceは作業用の私有資料に留め、丸ごとのvendor/実行依存を追加しない。
今回は文書のみとし、上流fixtureを常設CIへ取り込まない。約1,000行のvalidatorと依存を継続管理する変更は本設計の範囲を超えるためであり、単なる模造fixtureで実装を検証したことにもしない。
後続のツールPRで、ライセンス表示・由来・固定hashを含む最小fixtureと否定系をレビューする。

読解と独立した、ネットワークを使わない`ast.parse`による15項目の確認も実施した。
API call名/keyword、Prefix不在、date付きListPublicKeys、previous bucket追従、gap時のcounter、終了条件、RSA方式、backfill/previous hash比較の明示処理不在、CLI固有7引数を確認。
例として`DigestProvider.load_digest_keys_in_range`内の`paginate`のkeyword集合は**`{Bucket, Marker}`**。上流の`test_calls_list_objects_correctly`も同じ引数を期待している。
これはソース構造の確認であり、上流テストの実行・暗号検証・IAM認可の成功件数ではない。

## 2. API / IAM対応表（この固定版・非organization trail）

分類の「必須」は通常の対象digest/logを検証するcall path上の意味。実効許可を取得済みという意味ではない。
正本は[TF-B iam.tf](../../terraform-bootstrap/iam.tf)と[main.tf](../../terraform-bootstrap/main.tf)。実roleのtrust/他policy/SCP等は不明。

|処理・source箇所|API / IAM action|区分|現在のaudit設定と不整合|
|---|---|---|---|
|`S3ClientProvider._get_bucket_region` (L196–203)|S3 GetBucketLocation / `s3:GetBucketLocation`|必須。bucketごとにcache|直接grantなし。GetObjectやListBucketでは代替しない。bucket ARNへの別許可が必要|
|`DigestProvider.load_digest_keys_in_range` (L265–298)|S3 ListObjects v1 / `s3:ListBucket`|必須。paginatorによる全page、Markerのみ|`ListAudit`は`s3:prefix`のStringLike付き。Prefixなしrequestはこのallow条件と整合しない|
|`fetch_digest` (L300–325)|S3 GetObject / `s3:GetObject`|必須。本文と署名metadataを同時取得|`ReadAudit`は`state-audit/AWSLogs/<account>/`以下のみ。元のbucket/keyと一致するdigestが対象|
|`CloudTrailValidateLogs._download_log` (L910–939)|S3 GetObject / `s3:GetObject`|参照logがある場合に必須|同じReadAudit境界。digestが指す別bucket/prefixを自動で許可しない|
|`PublicKeyProvider.get_public_keys` ([utils L58–73](https://github.com/aws/aws-cli/blob/f4f66e65e70f23f1bf232ecb4a6d8696f4dda3bc/awscli/customizations/cloudtrail/utils.py#L58-L73))|CloudTrail ListPublicKeys / `cloudtrail:ListPublicKeys`|必須。指定期間と検証regionの署名公開鍵|CloudTrail actionの直接grantなし。KMS鍵の公開鍵取得では代替不可|
|`create_digest_traverser` (L84–175) → `get_trail_by_arn`|CloudTrail DescribeTrails / `cloudtrail:DescribeTrails`|bucket未指定時のみ|grantなし。引数なしで取得後にARNで絞る。bucketとprefixを承認済みmanifestで明示する案なら、このcallを避けられる|
|同functionの`IsOrganizationTrail`分岐|Organizations DescribeOrganization / `organizations:DescribeOrganization`|bucket自動解決かつorganization trailのみ|TF-Bは通常trail。今回不要。organization対応を主張せず、判明したら別審査|
|SSE-KMS objectのGetに伴うS3側処理|KMS Decrypt / `kms:Decrypt`|暗号化objectの読取に必要。validatorからの直接KMS callは0|`DecryptAudit`とaudit keyの`AuditRead`あり。指定CMK/S3 ViaService/trail context限定。実context一致・復号は未実証|
|version取得、bucket一覧、書込、管理|ListObjectVersions/GetObjectVersion、ListBuckets、Put/Delete、KMS Encrypt/GenerateDataKey/Sign/Verify等|このvalidatorの読取pathには不要|既存version読取権限は復旧/証拠審査と分離して評価。digest検証を理由に追加しない。CloudTrail配送側のGenerateDataKey/DescribeKeyとは主体が異なる|

[S3 ListObjects仕様](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjects.html)ではMarkerとPrefixは別parameter。
Markerは列挙開始位置であって認可prefixではない。SDKのpage取得後の正規表現filterでもIAM requestは変わらない。
[IAM条件仕様](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_condition_operators.html)では不在keyに対する正のStringLikeは一致しない。
従ってTF-Bのこのallowは当該requestを許可する根拠にならない。**実AWSでAccessDeniedを再現したとの主張ではない**。

`--s3-prefix`は配置prefixをmarker/regexへ渡す機能で、ListObjectsのPrefixを送るoptionではない。架空の`--prefix`等を案に含めない。
TF-Bの配置prefixは`state-audit`、IAM namespaceは`state-audit/AWSLogs/<account>/`であり、CLIへ両者を同じ値として渡すとpathが二重になる。
bucket指定時はtrail lookup全体を迂回するため、prefixも自動補完されると仮定せず、組をmanifestで検証する。

[ListPublicKeys API](https://docs.aws.amazon.com/awscloudtrail/latest/APIReference/API_ListPublicKeys.html)はregionごとの公開鍵を返す。
[CloudTrail authorization表](https://docs.aws.amazon.com/service-authorization/latest/reference/list_cloudtrail.html)の同actionはresource型を持たず、IAM Resourceは`*`が必要。trail ARNに限定できると書かない。
将来の案はactionをこれだけに限定し、承認した検証regionへの`aws:RequestedRegion`条件も別途審査する。署名公開鍵は秘密鍵ではないが、取得権限を無関係なCloudTrail読取権限へ広げない。

[SSE-KMS仕様](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/encrypting-cloudtrail-log-files-with-aws-kms.html)と[KMS連携仕様](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/how-kms-works-with-cloudtrail.html)ではS3が読取者のために復号する。
S3読取許可とKMS復号許可は別境界。署名鍵と暗号化CMKも別物。
TF-Bのaudit CMKはbucketと同region、Bucket Keys無効、ViaServiceは**S3 bucket region**、trail contextのregion部分だけwildcardでaccount/trail名を固定する。
これはmulti-region trailの検証regionと混同しない。過去の異鍵object・context不一致・KMS停止は未実証で、fallbackや広い復号権限で回避しない。

## 3. 暗号検証と「完全に検証した」の境界

|観点|固定sourceから確認した範囲|未実証・後続の受入条件|
|---|---|---|
|原位置とdigest署名|`_load_and_validate_digest` L573–605でbucket/key一致とfingerprintを確認。`Sha256RSADigestValidator` L626–678で膨張後bytesのSHA-256を含む署名対象とRSA PKCS#1 v1.5 SHA-256を使用|本物の署名・改ざん・移動・metadata欠落による拒否は未実行。JSONの再serializeやgzip bytes自体のhashと取り違えない|
|log完全性|`_download_log`はgzip展開後のlogをstream hashし、digest内のhashValueと比較。不存在・不正format/hashをinvalidに数える|対象は**digestが参照したlogだけ**。未配送eventやtrail開始前/停止中のevent全件を証明しない|
|連鎖と鍵rotate|previousDigestS3Bucket/Objectを遡り各digestを検証。公開鍵は指定期間のfingerprint辞書。署名対象はpreviousDigestSignatureを含む|previousDigestHashValueの明示比較、子が持つprevious signatureと取得親metadata署名の明示突合は当該sourceに見つからない。仕様に基づく連鎖受入fixtureで確認が必要。暗号破綻を実証したとは言わない|
|公開鍵期間|呼ぶのは`get_public_keys(start,end)`。`get_public_key(...+20days)`はvalidate-logs経路から呼ばない|APIのNextTokenは公式仕様上reserved。固定版は1応答を辞書化する。将来paginationが必要なら再審査。終了後1時間のspilloverや期間外の親に必要な鍵が欠けた場合も勝手に有効扱いしない|
|regionと境界越え|CLI global regionをsource region/公開鍵clientに使う。trail ARN regionはhome。previous bucketが変わるとLocation/Listを再取得。logのbucketも参照する|multi-regionの各regionを明示。別bucket/prefix/region/organizationは承認allowlist外なら停止。自動権限拡張しない|
|時間・gap・空集合|start/end、終了+1時間の列挙、後方走査を実装。missing/invalidはcounter増分後に走査継続。gapは通知だけ|CLIのexit 0をT14 PASSと同一視しない。指定窓全体、連続性、全region、期待log/digestの存在を別判定する|
|backfill|固定版にはbackfill専用の列挙/分離処理を確認できない。`extract_digest_key_date` L56–63は末尾の固定slice、regexは通常名を前提とする|現行公式手順のbackfill別chain処理を**この版が保証するとしない**。backfill fixtureを含む対応版または改修を先に実証する|

[公式検証手順](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-log-file-validation-cli.html)にはbackfill別連鎖の説明があるが、**更新される手順を固定版実装の証拠に置換しない**。
[digest構造](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-log-file-validation-digest-file-structure.html)の`_backfill`名は通常名と異なる。
上記はソースの制約の特定であり、CLIを実行して本物のbackfillを拒否/受理させた結果ではない。

終了判定の重要な制約は`CloudTrailValidateLogs._run_main` L815–821と`_on_digest_gap` L992–999で再確認できる。
前者はinvalid digest/logが正なら1、それ以外0。後者はstderrへgapを通知するがinvalid counterを増やさない。
公開鍵が取得でき、その先でdigestが0件なら、`No digests found`のsummaryでも終了0に至る経路がある。
欠落した親へのNoSuchKeyはinvalidに数えるので、「全missingを成功扱い」と一般化もしない。
有効digestの範囲summaryだけでも中間gapの不存在や全eventの配送完了を証明できない。

## 4. A案: 公式CLIのrequestを変えない読取権限案

**未採択・HCL無変更**。仮に専用の非organization trailと監査bucketを使う場合の、追加範囲の設計表。

|対象|案・副作用|制限|
|---|---|---|
|監査専用bucketのList|当該bucket ARNに`s3:ListBucket`をPrefixなしで許可する必要がある。requestのMarker以降で他prefixのkey名・サイズ等のmetadataを取得し得る|bucket全域Listという権限拡張を所有者が明示承認。state bucketやListAllMyBucketsまで広げない。既存条件をIfExistsへ置換して黙って緩めない|
|同bucketのLocation|`s3:GetBucketLocation`を別statement、当該bucket ARNだけ|object/prefix条件をこのbucket操作へ流用しない。bucket regionのmetadata取得を承認する|
|署名公開鍵|`cloudtrail:ListPublicKeys`だけ、Resource `*`、承認region制約を審査|trail ARNへresource限定できない。別trailの管理/ログ検索権限を一括で付けない|
|内容Get / KMS|現在のnamespace限定GetObject、正確なaudit CMKとViaService/trail contextを維持する|**List拡大はGet内容の拡大を意味しない**。別bucket/過去の暗号鍵/別prefixが必要なら停止して再審査|
|自動解決|承認済みbucket/prefix明示でDescribeTrailsを避ける|bucket未指定運用を採る場合は追加read権限と取得metadataの別評価が必要。organization trailは別設計|

監査roleは既に`AuditReadersOnly`の対象。新しい検証roleを安易に足すとbucket/KMS側にも変更が波及する。
既存audit roleを使う場合でも、trust/SSO/MFA/session/他inline・managed policy/boundary/SCP/RCP/endpoint policy/KMS grantsとの合成は**不明**。
[IAM評価仕様](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_evaluation-logic.html)上のexplicit denyを追加allowで回避できるとはしない。
元のList prefixはallow条件であり、外部の無条件allowまで封じるexplicit denyではない。現roleが既に広い/狭いとは断定しない。
読取専用でもlog内容・key名の露出、S3/KMS request費用、検証期間延長の負担がある。

**A案の判断:** 公式CLI維持は保守面で有利だが、2.31.18へ権限だけ追加する案はT14用に採択しない。
backfill/範囲/gapの受入を満たす固定CLI版の再評価と、結果をfail-closedに判定する仕組みが先行条件。
独立したaudit専用bucket全域Listのリスクを承認でき、これらを満たした場合に再検討できる。

## 5. B案: Prefix限定の取得と検証を分ける

第一候補は**既存の公式validatorを根拠にした、レビュー済みの限定改修**。
全pageのListObjectsに承認された`state-audit/AWSLogs/<account>/CloudTrail-Digest/<region>/`等のPrefixを必ず渡す。
通常/別chainのbackfillを列挙し、TF-Bの`state-audit/AWSLogs/<account>/*`条件と一致させる。
MarkerはPrefixの代用にしない。別prefixやcross-bucketの親を発見したら追加探索前に停止する。
ListObjectsV2への切替も可能性はあるが、pagination/順序/全件性の差を同時に検証する別変更であり、1行追加で完了とは扱わない。

GetBucketLocationとListPublicKeysは**Prefixを追加しても必要**。B案でもこれらの限定grant審査は残る。
regionを私有manifestで固定してLocationを省略する独自client案は、実bucket regionとの照合方法を別途証明するまで採用しない。
SSE-KMS Getには同じ復号境界が必要であり、B案でもCMK権限を不要にはできない。

暗号の新規実装は避け、次を全て満たすfixture/比較試験と独立レビューを**採用前**に要求する。

1. 公式[custom validation仕様](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-log-file-custom-validation.html)に従う原位置・署名metadata・fingerprint/鍵rotate・非圧縮bytes・署名連鎖。親とのsignature/hash整合、未知algorithm/formatを明示拒否する。
2. 正常、署名/本文/metadata改ざん、異鍵、欠落、moved object、鍵期間境界、gzip不正、backfill別chain、region違い、停止/再開gap、0件、途中取得エラー。全pageを取得し、重複/循環pointer・過大入力/解凍量・回数/時間上限で停止する。
3. 権限を要求する前にbucket/prefix/region/timeのallowlistを照合し、別trailの情報を自動取得しない。raw keyや内容を公開ログへ出さない。
4. cryptographic VALIDと、期待event/期間のcoverage COMPLETEを別結果にする。完全性不明ならT14はNOT_RUN/BLOCKEDまたはFAILで、missing/gapをsuccessへ変換しない。

別候補は、承認されたcollectorでPrefix付き取得を行い、**署名metadataと公開鍵も保全**して完全オフラインvalidatorへ渡す方式。
公式custom validation仕様が示す方式だが、取得元と鍵の真正性・最新digestの署名・path対応・chain coverageの責任をcollectorが負う。
手元のlogファイルだけ、S3 ETag/checksumだけ、CloudTrailのvalidation有効flagだけでは代替できない。
Lake query結果の検証をtrail log/digest連鎖の検証と同一視もしない。
公式CLIの`validate-logs`自体はonlineで原位置から読む契約であり、このcollector方式にそのまま流用できるとしない。

## 6. 採否と後続PRの分割

|案|暗号検証・coverage|最小権限|保守性/運用負担|判断|
|---|---|---|---|---|
|A: 固定2.31.18にgrant追加だけ|標準digest署名/log hashのsourceは確認。gap/空集合/backfillの受入不足|専用bucket全域List、Location、resource型なしPublicKeysが必要|上流利用は容易だが終了0だけでは不足|**今回は不採択**|
|A再選定: 対応CLI版＋coverage判定|新しい固定版でbackfill等を再検証する必要あり|全域Listを維持するなら明示承認が必要|上流追従は比較的容易、結果format変化を管理|条件付きの代替候補、未検証|
|B: Prefix付きの限定改修|既存cryptoを維持してもchain/backfill/coverageの追加実証が必要|List prefix維持。Location/PublicKeys/CMKは残る|fork差分・CRT/SDK依存・上流変更の継続審査が必要|**次の技術検証候補。採用は未確定**|
|B: collector＋独立offline validator|署名metadata/真正な鍵/原位置/全chainの保全を自分で証明|取得側Prefix維持。保管側の機密境界が増える|実装/運用責任が最も大きい|B限定改修が成立しない時の再設計候補|

進める場合も、次の順序で小さく審査する。本書やCI成功は実行承認ではない。

|次の単位|許される成果・受入|開始してはいけないこと|
|---|---|---|
|C05-tool（AWS無接続）|固定upstream/ライセンスと最小差分、Stub/公開synthetic fixtureの正常/否定/改ざん試験、request PrefixとAPI集合の回帰、coverage判定を独立レビュー|本物のdigest PASSやAWS allow/denyと報告しない。現preflightをREADYにしない|
|C05-policy（別の最小HCL PR）|選定toolに必要なLocation/PublicKeys/必要な場合だけList変更、限定したbucket/region/CMK、合成policyと拒否の静的試験|tool未選定で全域Listを先行付与しない。今回は実施しない|
|C05-runbook（実行前審査）|region/時間/chain manifest、取得/試行上限、停止/再承認、private証跡保管、独立承認者を確定|C01 cleanup、C02独立KMS復旧、B01〜B14の承認を省略しない|
|TF-C T13/T14（別途明示許可後）|限定sandboxで配送照合と本物のdigest/改ざん/拒否を計測。原位置、KMS、署名、gap/期間、全regionを確認|owner/account/role/trust/MFA/SCP/費用/cleanup未確定のまま開始しない。TF-Dや本番の許可に広げない|

## 7. fail-closed受入と証跡

将来の判定は「command exit 0」単独で作らない。以下のどれかを満たせなければ止める。

- source/version/hash、承認manifest、全region・期間が一致しない。未知のbucket/prefix/key、別trail、organization、想定外redirect/endpointを発見した。
- access deny、KMS復号不能/鍵不明、API/JSON/gzip/formatエラー、pagination未完、制限時間/費用/取得数の超過。別credentialや広い権限への自動fallbackは禁止。
- digest0件、署名/参照log不正・欠落、期待chain/期間の不足、未承認gap、backfill未検証。ログが0件でも、署名済みempty digestによるcoverageとは区別する。
- 公開鍵/fingerprint/region/timeの整合不明、metadata欠落、chain切断/循環、coverage判定不能。キーを動的な不明ソースから補完しない。

有効なempty digestを常に失敗とするものではない。意図した管理/data eventをT13で発生させる試験では、その期待eventがない場合は別途不合格とする。
無イベント時間のempty digestを含む検証でも、配送待ち窓/停止期間/遅延backfillの受入を事前に定め、待機の無制限延長で成功へ寄せない。
coverageを確認しても**CloudTrailへ一度も配送されていないeventの不存在までは証明しない**。

公開証跡はsource SHA/版、試験ID、期待/実結果分類、検証件数、coverage可否、相対時間、opaque私有参照だけ。
raw CLI stdout/stderrにはbucket/key/ARNが含まれるためCI artifactへそのまま載せない。digest/log本文、公開鍵と主体の対応、request ID、実時刻、version ID、state/secretは私有管理する。
B12の暗号化・閲覧・保持・破棄と二者確認が未承認なら取得を開始しない。
CLIの更新、parser/output形式、SDK/CRT、List API、trail/bucket/prefix/region、暗号化方式、鍵やIAMの変更時は本API表と全fixtureを再審査する。

## 8. このPRの検証範囲と未解消事項

このPRは本書とTF-C準備READMEへの導線だけ。Java/POM/migration/runtime/HCL/IAM/backend/CI/workflowは変更しない。
リンク・固定hash/API/引数・既存preflight全BLOCKEDとの整合を静的に確認する。
Java 17 full verify、Python68件、Terraform host27＋bootstrap14＋synthetic8＋mutation拒否3、Secret例外0、SECURITY解析、OSV、使い捨てDocker9段階＋B1/B2/B3、Trivyは**当該PR HEADの実測をPR本文/CIへ記録**する。見込み値を成功記録にしない。
通常の品質ゲート成功はAWS認可・配送・暗号検証の受入を代替しない。

Issue #145のWindows起動/証明書/Wait Strategy/DB CLIの根因未確定、#134 jti保留、#86/#87/#89の本番運用条件未達を維持。
Trivyの過去基準OS31（MEDIUM27/LOW4）は保存する。今回独立確認した基準mainの[CI run 39930161386](https://github.com/utl-flaxy/inventory-management-system/actions/runs/39930161386)は全5ジョブ成功だが、Trivyは**OS32（MEDIUM28/LOW4）、library0**。直前のPR #151のCI証跡との差分は`libgnutls30t64` `3.8.3-1.1ubuntu3.6`の`CVE-2026-88648`（MEDIUM、修正版表示なし）1件追加、消失0件だった。同じ定義でも監査DBの検出は時点で変わるため31へ固定して報告しない。最終PR HEADの再測定はPR本文に分離する。
Flyway/MariaDB互換性WARN、異版binary rollback・本番復旧・容量/SLA未検証も残る。
同image IDによる設定故障復帰を旧binary rollbackの実証にしない。
PR #103はDraft/OPEN・対象外、Deploy disabled、Issue #75 OPEN、TF-C BLOCKED、**本番運用NO GO**を変更しない。

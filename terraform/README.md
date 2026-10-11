# Production host definition (Issue #59)

Terraform **1.15.6** と AWS provider **6.68.0** (`~> 6.0`) を使用する。
`.terraform.lock.hcl` は Linux/Windows amd64 のchecksumを含めてcommitする。
通常CIは同じCLIでfmt/init/validateとmock testを実行し、AWS credentialsを必要としない。
このIssueでは **apply・real plan・AWS/EC2操作は未実施**。Deploy workflowはdisabledのまま。

## 入力と検証

Terraform 1.15.6をインストールし、repository rootで実行する。

```bash
terraform -chdir=terraform init -backend=false -input=false -lockfile=readonly
```

運用者が `terraform/terraform.tfvars` を私有ファイルとして用意する（Git管理外）。
実際の接続元IPv4 CIDRと**公開鍵**へのpathを指定し、秘密鍵を指定しない。

```hcl
region            = "ap-northeast-1"
instance_type     = "t3.micro"
ssh_ingress_cidr  = "203.0.113.10/32" # 文書用アドレス。実際の運用者IPへ置換する。
key_name          = "inventory-production-operator" # 対象regionで未使用の名前
public_key_path   = "~/.ssh/id_ed25519.pub"
vpc_cidr          = "10.20.0.0/16"
public_subnet_cidr = "10.20.1.0/24"
```

```bash
terraform fmt -check -recursive terraform
terraform -chdir=terraform validate
terraform -chdir=terraform test -no-color
```

最後のcommandは `mock_provider "aws"` と `command = plan` だけを使用し、
AWS data sourceもresourceも実際には呼ばない。test fixtureは公開鍵だけを含む。
ローカルに既存のtfvars/stateがある場合は、Git管理下のmodule・lockfile・testsだけを
私有の別directoryへコピーして検証する。既存state/tfvarsを検証用にコピー・表示しない。

lock更新時だけ、変更内容をレビューして次を実行しcommitする。

```bash
terraform -chdir=terraform providers lock -platform=linux_amd64 -platform=windows_amd64
```

実環境の **plan/applyは運用者の手動作業**。AWS credentialsを持つ運用者が変更計画・費用・
CIDR重複・SSH接続元・bootstrap結果を確認する。既存PoCのstateをそのまま適用せず、
replacementや破壊的変更を確認し、必要なimport/state移行は別途設計する。
AMIはAWS公式SSM public parameterの現行値を解決するため、後日のplanでAMIが変わる場合がある。
運用時には保存したplanをレビューし、そのplanを適用する。package patch versionやGit mainは
時点で変わり得る。CLI/provider/Composeを固定しており、完全なimmutable imageではない。
stateはローカルのままであり、remote backend/state lockingは別Issue。

## Network / EC2

- 専用VPC `10.20.0.0/16` にpublic subnet `10.20.1.0/24` を1つ作成する。
  subnetはVPC内のcanonical IPv4 CIDRであることを検証する。他ネットワークとの重複は運用者が確認する。
- Internet Gateway、default route、route table associationを明示し、default VPCへ依存しない。
- regionは入力（既定Tokyo）。利用可能な標準AZ名をsortし先頭を選ぶ。account固有AZ IDは使用しない。
- EC2だけpublic IPv4を明示付与する。SSHは必須 `ssh_ingress_cidr` のみで、`/0` とIPv6を拒否する。
  最小の運用者 `/32` を推奨する。8080 ingressはなく、80/443も開けない。
- egressはpackage/GitHub/Maven/Docker取得のためIPv4の全通信を許可する。
- AWS公式 `/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64` からAL2023を解決する。
  instance typeはx86_64のT3 micro〜2xlargeだけを許可する。
- IMDSv2必須、hop limit 1。root EBSは暗号化gp3/30 GiB、termination時削除。
  build/image容量を考慮した初期容量で、DB backupを保証する設定ではない。
- tagsをサポートするAWS resourceにName、Project、ManagedByを付与する。SGはname_prefixで衝突を避ける。
  host用AWS API権限は不要なのでIAM roleを追加しない。
- `public_ip` / `instance_id` のみを出力する。秘密情報のinput/outputは作らない。

## Bootstrapと配備の境界

userdataはstrict mode/umask 077で、Git、Corretto 17 JDK、Docker Engine、curl-minimal、
util-linux/flock、coreutilsをAL2023 packageから導入する。
JAVA_HOMEはdeploy scriptの `/usr/lib/jvm/java-17-amazon-corretto` と一致させる。
Docker Compose **v2.40.3** のLinux x86_64 assetをHTTPSで取得し、固定SHA-256
`dba9d98e1ba5bfe11d88c99b9bd32fc4a0624a30fafe68eea34d61a3e42fd372` を検証してからinstallする。
取得失敗・checksum不一致・version不一致で停止する。Docker serviceをenable/startし、
ec2-userだけをdocker groupへ追加する（Docker権限はroot相当なので配備ユーザーに限定）。

公開repositoryのmainを `/home/ec2-user/inventory-management-system` へec2-user所有でcloneする。
userdataにはDB/JWT/SSH private key/GitHub tokenを含めず、**app・DB起動、volume操作、restoreは実行しない**。
初回bootstrapは新規host向けで、既存checkoutへの再実行を想定しない。

bootstrap後、運用者がDBとsecretを [配備手順](../docs/deployment.md)・[DB手順](../docs/db/README.md)
に従って別途準備する。配備はCI検証済みSHAを使用する既存 `scripts/deploy.sh` で行い、
readiness/rollback/lock/redactionの契約を維持する。Deploy workflowはまだ無効で、再有効化は今回行わない。
SGの8080が閉じていてもhost localhostの `/readyz` 確認に支障はない。
HTTPS/TLS/domain/proxy/80・443公開は次Issue。現在、このhost定義だけでは公開applicationへ接続できない。

公式資料: [AL2023 AMI public parameter](https://docs.aws.amazon.com/systems-manager/latest/userguide/parameter-store-public-parameters-ami.html)、
[Corretto 17 package](https://docs.aws.amazon.com/corretto/latest/corretto-17-ug/amazon-linux-install.html)、
[AL2023 curl-minimal](https://docs.aws.amazon.com/linux/al2023/ug/curl-minimal.html)、
[Compose v2.40.3](https://github.com/docker/compose/releases/tag/v2.40.3)。

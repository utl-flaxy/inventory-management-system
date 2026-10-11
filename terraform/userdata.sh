#!/bin/bash
set -euo pipefail
umask 077

# This bootstrap supports the AL2023 x86_64/T3 contract only.
[[ $(uname -m) == x86_64 ]] || { echo 'Unsupported host architecture' >&2; exit 1; }
# EIP association follows instance creation; wait boundedly before package downloads.
# The official AL2023 base image already includes curl-minimal.
curl --fail --head --silent --show-error --proto '=https' --tlsv1.2 \
  --retry 20 --retry-all-errors --retry-delay 2 --retry-max-time 120 \
  --connect-timeout 3 --max-time 5 https://github.com >/dev/null
dnf install -y git java-17-amazon-corretto-devel docker curl-minimal util-linux coreutils tar gzip shadow-utils

# Corretto's architecture-specific JDK must match deploy.sh's default JAVA_HOME.
export JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto
[[ -x "$JAVA_HOME/bin/javac" ]] || ln -sfnT /usr/lib/jvm/java-17-amazon-corretto.x86_64 "$JAVA_HOME"
[[ -x "$JAVA_HOME/bin/javac" ]]
export PATH="$JAVA_HOME/bin:$PATH"
java_version=$(java -version 2>&1)
[[ "$java_version" == *'version "17.'* ]] || { echo 'Java 17 is required' >&2; exit 1; }
for command in git javac docker curl flock sha256sum; do command -v "$command" >/dev/null; done

# Official v2 release and x86_64 asset digest; verify before installing executable code.
# https://github.com/docker/compose/releases/tag/v2.40.3
readonly compose_version=v2.40.3
readonly compose_sha256=dba9d98e1ba5bfe11d88c99b9bd32fc4a0624a30fafe68eea34d61a3e42fd372
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 \
  --retry 3 --connect-timeout 10 --max-time 180 \
  "https://github.com/docker/compose/releases/download/$compose_version/docker-compose-linux-x86_64" \
  --output "$work/docker-compose"
printf '%s  %s\n' "$compose_sha256" "$work/docker-compose" | sha256sum --check --status
install -d -m 0755 /usr/local/lib/docker/cli-plugins
install -m 0755 "$work/docker-compose" /usr/local/lib/docker/cli-plugins/docker-compose
[[ $(docker compose version --short) == "${compose_version#v}" ]]

# Caddy sources are embedded by Terraform from the reviewed repository, not downloaded main.
printf '%s' '__CADDYFILE_BASE64__' | base64 --decode >"$work/Caddyfile.tmpl"
printf '%s' '__CADDY_SERVICE_BASE64__' | base64 --decode >"$work/caddy.service"
printf '%s' '__CADDY_INSTALL_BASE64__' | base64 --decode >"$work/install-caddy.sh"
bash "$work/install-caddy.sh" '__APP_DOMAIN__' "$work/Caddyfile.tmpl" "$work/caddy.service"

systemctl enable --now docker
usermod -aG docker ec2-user
# Git writes as the deploy user, so the checkout and .git have the correct owner.
runuser -u ec2-user -- git clone --branch main --single-branch \
  https://github.com/utl-flaxy/inventory-management-system.git \
  /home/ec2-user/inventory-management-system

# Stop after host preparation. Operators prepare secrets and the DB separately.
echo 'Host bootstrap complete; database and application startup require operator preparation.'

#!/usr/bin/env bash
# Executes only mocked host commands in a disposable directory. No AWS or host mutation.
set -euo pipefail
script=$(realpath "$1")
scenario=$2
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/bin" "$work/jdk/bin"
export TEST_WORK="$work" TEST_SCENARIO="$scenario"
export PATH="$work/bin:$PATH"
cat >"$work/bin/mock" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
name=${0##*/}
printf '%s %s\n' "$name" "$*" >>"$TEST_WORK/calls"
case "$name" in
  uname) [[ "$TEST_SCENARIO" != wrong-arch ]] && echo x86_64 || echo aarch64 ;;
  dnf) [[ "$TEST_SCENARIO" != package-failure ]] ;;
  java) [[ "$TEST_SCENARIO" != wrong-java ]] && echo 'openjdk version "17.0.18"' || echo 'openjdk version "21.0.1"' ;;
  curl)
    [[ "$TEST_SCENARIO" != download-failure ]] || exit 1
    [[ "$*" != *caddy_* || "$TEST_SCENARIO" != caddy-download-failure ]] || exit 1
    while (( $# )); do
      if [[ "$1" == --output ]]; then printf 'test-binary' >"$2"; break; fi
      shift
    done ;;
  sha256sum)
    input=$(cat)
    printf '%s\n' "$input" >>"$TEST_WORK/checksum-input"
    [[ "$TEST_SCENARIO" != checksum-mismatch ]] || exit 1
    [[ "$input" != *caddy.tar.gz || "$TEST_SCENARIO" != caddy-checksum-mismatch ]] ;;
  docker)
    [[ "$*" == 'compose version --short' ]] || exit 1
    [[ "$TEST_SCENARIO" != wrong-compose ]] && echo 2.40.3 || echo 2.40.2 ;;
  tar)
    while [[ "$1" != -C ]]; do shift; done
    cat >"$2/caddy" <<'CADDY'
#!/usr/bin/env bash
printf 'caddy %s\n' "$*" >>"$TEST_WORK/calls"
[[ "$TEST_SCENARIO" != wrong-caddy ]] && echo 'v2.11.7 mock' || echo 'v2.11.6 mock'
CADDY
    chmod +x "$2/caddy" ;;
  systemctl)
    [[ "$TEST_SCENARIO" != service-failure || "$*" != 'enable --now docker' ]] || exit 1
    [[ "$TEST_SCENARIO" != caddy-systemd-failure || "$*" != daemon-reload ]] ;;
  runuser)
    [[ "$TEST_SCENARIO" != clone-failure || "$*" != *'git clone'* ]] || exit 1
    [[ "$TEST_SCENARIO" != caddy-config-failure || "$*" != *'caddy validate'* ]] ;;
  getent|id) exit 1 ;;
  install|usermod|git|javac|flock|groupadd|useradd) ;;
  *) exit 1 ;;
esac
MOCK
chmod +x "$work/bin/mock"
# Copies avoid Windows symlink privileges and keep the actual production script unmodified.
for name in uname dnf java curl sha256sum docker systemctl runuser install usermod git javac flock tar getent id groupadd useradd; do
  cp "$work/bin/mock" "$work/bin/$name"
done
cp "$work/bin/mock" "$work/jdk/bin/javac"
# Redirect only absolute install/checkout paths in the disposable test copy.
sed -e "s|/usr/lib/jvm/java-17-amazon-corretto|$work/jdk|g" \
    -e "s|/usr/local/lib/docker/cli-plugins|$work/plugins|g" \
    -e "s|/home/ec2-user/inventory-management-system|$work/checkout|g" \
    -e "s|__APP_DOMAIN__|api.example.com|g" \
    -e "s|__CADDYFILE_BASE64__|$(base64 <terraform/caddy/Caddyfile.tmpl | tr -d '\n\r')|g" \
    -e "s|__CADDY_SERVICE_BASE64__|$(base64 <terraform/caddy/caddy.service | tr -d '\n\r')|g" \
    -e "s|__CADDY_INSTALL_BASE64__|$(base64 <scripts/tls/install-caddy.sh | tr -d '\n\r')|g" "$script" >"$work/userdata.sh"
result=0
bash "$work/userdata.sh" >"$work/output" 2>&1 || result=$?
if [[ "$scenario" == success && "$result" != 0 ]]; then
  # These files contain mock commands only, never a real host environment.
  cat "$work/output" "$work/calls" >&2
fi
case "$scenario" in
  success)
    [[ "$result" == 0 ]]
    [[ $(grep -c '^install ' "$work/calls") == 7 ]]
    grep -q '^systemctl enable --now docker$' "$work/calls"
    grep -q '^usermod -aG docker ec2-user$' "$work/calls"
    grep -q '^runuser -u ec2-user -- git clone --branch main --single-branch ' "$work/calls"
    [[ $(grep -n '^sha256sum ' "$work/calls" | head -1 | cut -d: -f1) -lt $(grep -n '^install -m ' "$work/calls" | cut -d: -f1) ]]
    [[ $(grep -n '^sha256sum ' "$work/calls" | tail -1 | cut -d: -f1) -lt $(grep -n '^tar ' "$work/calls" | cut -d: -f1) ]]
    [[ $(grep -n '^caddy version$' "$work/calls" | cut -d: -f1) -lt $(grep -n '^install .*caddy /usr/local/bin/caddy$' "$work/calls" | cut -d: -f1) ]] ;;
  wrong-arch|package-failure|wrong-java|download-failure|checksum-mismatch)
    [[ "$result" != 0 ]]
    ! grep -qE '^(install|systemctl|usermod|runuser) ' "$work/calls" ;;
  wrong-compose|service-failure)
    [[ "$result" != 0 ]]
    ! grep -q '^runuser ' "$work/calls" ;;
  clone-failure) [[ "$result" != 0 ]] ;;
  caddy-download-failure|caddy-checksum-mismatch)
    [[ "$result" != 0 ]]
    ! grep -qE '^(tar|caddy|runuser|systemctl) ' "$work/calls" ;;
  wrong-caddy)
    [[ "$result" != 0 ]]
    ! grep -qE '^(runuser|systemctl|groupadd|useradd) ' "$work/calls"
    ! grep -q '^install .*caddy' "$work/calls" ;;
  caddy-config-failure|caddy-systemd-failure)
    [[ "$result" != 0 ]]
    ! grep -q '^systemctl enable' "$work/calls"
    ! grep -q '^runuser .*git clone' "$work/calls" ;;
  *) exit 1 ;;
esac
! grep -q '^systemctl .*caddy' "$work/calls"
! grep -qE 'docker .* (up|down)|volume (rm|create)|set -x' "$work/calls"
echo "PASS $scenario"

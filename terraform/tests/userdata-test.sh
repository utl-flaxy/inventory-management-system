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
    while (( $# )); do
      if [[ "$1" == --output ]]; then printf 'test-binary' >"$2"; break; fi
      shift
    done ;;
  sha256sum)
    cat >"$TEST_WORK/checksum-input"
    [[ "$TEST_SCENARIO" != checksum-mismatch ]] ;;
  docker)
    [[ "$*" == 'compose version --short' ]] || exit 1
    [[ "$TEST_SCENARIO" != wrong-compose ]] && echo 2.40.3 || echo 2.40.2 ;;
  systemctl) [[ "$TEST_SCENARIO" != service-failure ]] ;;
  runuser) [[ "$TEST_SCENARIO" != clone-failure ]] ;;
  install|usermod|git|javac|flock) ;;
  *) exit 1 ;;
esac
MOCK
chmod +x "$work/bin/mock"
# Copies avoid Windows symlink privileges and keep the actual production script unmodified.
for name in uname dnf java curl sha256sum docker systemctl runuser install usermod git javac flock; do
  cp "$work/bin/mock" "$work/bin/$name"
done
cp "$work/bin/mock" "$work/jdk/bin/javac"
# Redirect only absolute install/checkout paths in the disposable test copy.
sed -e "s|/usr/lib/jvm/java-17-amazon-corretto|$work/jdk|g" \
    -e "s|/usr/local/lib/docker/cli-plugins|$work/plugins|g" \
    -e "s|/home/ec2-user/inventory-management-system|$work/checkout|g" "$script" >"$work/userdata.sh"
result=0
bash "$work/userdata.sh" >"$work/output" 2>&1 || result=$?
if [[ "$scenario" == success && "$result" != 0 ]]; then
  # These files contain mock commands only, never a real host environment.
  cat "$work/output" "$work/calls" >&2
fi
case "$scenario" in
  success)
    [[ "$result" == 0 ]]
    [[ $(grep -c '^install ' "$work/calls") == 2 ]]
    grep -q '^systemctl enable --now docker$' "$work/calls"
    grep -q '^usermod -aG docker ec2-user$' "$work/calls"
    grep -q '^runuser -u ec2-user -- git clone --branch main --single-branch ' "$work/calls"
    [[ $(grep -n '^sha256sum ' "$work/calls" | cut -d: -f1) -lt $(grep -n '^install -m ' "$work/calls" | cut -d: -f1) ]] ;;
  wrong-arch|package-failure|wrong-java|download-failure|checksum-mismatch)
    [[ "$result" != 0 ]]
    ! grep -qE '^(install|systemctl|usermod|runuser) ' "$work/calls" ;;
  wrong-compose|service-failure)
    [[ "$result" != 0 ]]
    ! grep -q '^runuser ' "$work/calls" ;;
  clone-failure) [[ "$result" != 0 ]] ;;
  *) exit 1 ;;
esac
! grep -qE 'docker .* (up|down)|volume (rm|create)|set -x' "$work/calls"
echo "PASS $scenario"

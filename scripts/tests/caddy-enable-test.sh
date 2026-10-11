#!/usr/bin/env bash
set -euo pipefail
script=$(realpath "$1")
scenario=$2
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/bin"
export TEST_WORK="$work" TEST_SCENARIO="$scenario"
export PATH="$work/bin:$PATH"
printf 'api.example.com {\n}\n' >"$work/Caddyfile"
cat >"$work/bin/mock" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
name=${0##*/}
echo "$name $*" >>"$TEST_WORK/calls"
case "$name" in
  getent)
    [[ "$TEST_SCENARIO" != dns-failure ]] || exit 1
    if [[ "$TEST_SCENARIO" == dns-mismatch ]]; then echo '203.0.113.99 STREAM';
    elif [[ "$TEST_SCENARIO" == mixed-dns ]]; then printf '203.0.113.20 STREAM\n203.0.113.99 STREAM\n';
    else echo '203.0.113.20 STREAM'; fi ;;
  runuser) [[ "$TEST_SCENARIO" != config-failure ]] ;;
  systemctl) [[ "$TEST_SCENARIO" != systemd-failure ]] ;;
  *) exit 1 ;;
esac
MOCK
chmod +x "$work/bin/mock"
for name in getent runuser systemctl; do cp "$work/bin/mock" "$work/bin/$name"; done
sed "s|/etc/caddy/Caddyfile|$work/Caddyfile|g" "$script" >"$work/enable.sh"
domain=api.example.com
ip=203.0.113.20
case "$scenario" in
  bad-domain) domain='https://api.example.com' ;;
  ip-domain) domain=127.0.0.1 ;;
  missing-domain) domain='' ;;
  bad-ip) ip=999.0.0.1 ;;
  wrong-domain) domain=other.example.com ;;
esac
result=0
bash "$work/enable.sh" "$domain" "$ip" >"$work/output" 2>&1 || result=$?
touch "$work/calls"
if [[ "$scenario" == success ]]; then
  [[ "$result" == 0 ]]
  grep -q '^systemctl enable --now caddy$' "$work/calls"
  [[ $(grep -n '^runuser .*caddy validate' "$work/calls" | cut -d: -f1) -lt $(grep -n '^systemctl ' "$work/calls" | cut -d: -f1) ]]
else
  [[ "$result" != 0 ]]
  [[ "$scenario" == systemd-failure ]] || ! grep -q '^systemctl ' "$work/calls"
fi
echo "PASS $scenario"

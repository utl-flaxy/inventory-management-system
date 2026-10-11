#!/usr/bin/env bash
# Run as root only after app readiness and public DNS A -> EIP are confirmed.
set -euo pipefail
umask 077
domain=${1:?app_domain is required}
expected_ip=${2:?Terraform EIP is required}
[[ ${#domain} -le 253 && "$domain" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$ && ${domain,,} != *.localhost ]] || { echo 'Invalid application domain' >&2; exit 1; }
[[ "$expected_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { echo 'Invalid EIP' >&2; exit 1; }
IFS=. read -r -a octets <<<"$expected_ip"
for octet in "${octets[@]}"; do (( 10#$octet <= 255 )) || { echo 'Invalid EIP' >&2; exit 1; }; done
grep -Fxq "$domain {" /etc/caddy/Caddyfile || { echo 'Domain does not match installed config' >&2; exit 1; }
# Reject stale/mixed A records rather than starting against a partially propagated DNS answer.
addresses=$(getent ahostsv4 "$domain" | awk '{print $1}' | sort -u)
[[ "$addresses" == "$expected_ip" ]] || { echo 'DNS A record does not match the EIP' >&2; exit 1; }
runuser -u caddy -- /usr/local/bin/caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl enable --now caddy
echo 'Caddy enabled; verify public HTTPS, certificate renewal and localhost readiness.'

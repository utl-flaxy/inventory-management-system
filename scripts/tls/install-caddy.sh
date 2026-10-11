#!/usr/bin/env bash
# Fresh-host preparation only. DNS must be configured before the separate enable step.
set -euo pipefail
umask 077
domain=${1:?app_domain is required}
template=${2:?Caddyfile template is required}
unit=${3:?systemd unit is required}
[[ ${#domain} -le 253 && "$domain" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$ && ${domain,,} != *.localhost ]] || { echo 'Invalid application domain' >&2; exit 1; }
[[ $(uname -m) == x86_64 ]] || { echo 'Unsupported host architecture' >&2; exit 1; }
# Official GitHub release asset digest (SHA-256, not the SHA-512 checksums.txt).
# https://github.com/caddyserver/caddy/releases/tag/v2.11.7
readonly caddy_version=2.11.7
readonly caddy_sha256=727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 \
  --retry 3 --connect-timeout 10 --max-time 180 \
  "https://github.com/caddyserver/caddy/releases/download/v$caddy_version/caddy_${caddy_version}_linux_amd64.tar.gz" \
  --output "$work/caddy.tar.gz"
printf '%s  %s\n' "$caddy_sha256" "$work/caddy.tar.gz" | sha256sum --check --status
tar -xzf "$work/caddy.tar.gz" -C "$work" caddy
[[ $("$work/caddy" version) == "v$caddy_version "* ]] || { echo 'Unexpected Caddy version' >&2; exit 1; }
# A dedicated non-login identity, private persistent state, and root-owned config/binary.
getent group caddy >/dev/null || groupadd --system caddy
id -u caddy >/dev/null 2>&1 || useradd --system --gid caddy --home-dir /var/lib/caddy --shell /sbin/nologin caddy
install -d -o caddy -g caddy -m 0700 /var/lib/caddy
install -d -o root -g caddy -m 0750 /etc/caddy
sed "s/__APP_DOMAIN__/$domain/g" "$template" >"$work/Caddyfile"
install -o root -g caddy -m 0640 "$work/Caddyfile" /etc/caddy/Caddyfile
install -o root -g root -m 0755 "$work/caddy" /usr/local/bin/caddy
runuser -u caddy -- /usr/local/bin/caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
install -o root -g root -m 0644 "$unit" /etc/systemd/system/caddy.service
systemctl daemon-reload
# Deliberately do not enable/start: no ACME activity until DNS/operator readiness.
echo 'Caddy prepared; configure DNS and run scripts/tls/enable-caddy.sh before public traffic.'

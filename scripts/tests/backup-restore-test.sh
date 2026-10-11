#!/usr/bin/env bash
# Mock Dockerのみ。秘密値を出さず、実scriptの安全性・失敗時副作用を検証する。
set -euo pipefail
umask 077
scripts=$(cd -- "$1" && pwd -P)
export SCENARIO="$2"
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
export TRACE="$fixture/trace" MOCK_ROOT="$fixture" BACKUP_ROOT="$fixture/backups"
export DB_CONTAINER=db-test57 MOCK_DATABASE=asset_management
export REAL_GZIP=$(command -v gzip) REAL_SHA=$(command -v sha256sum) REAL_MV=$(command -v mv)
mkdir -p "$fixture/bin" "$fixture/bundle"
: >"$TRACE"
printf 'private-dump-canary57 hash-canary57 idempotency-canary57\n' | "$REAL_GZIP" -n >"$fixture/bundle/dump.sql.gz"
(cd -- "$fixture/bundle" && "$REAL_SHA" dump.sql.gz >dump.sha256)
printf 'format_version=1\nsource_database=asset_management\ncreated_at_utc=2026-10-11T00:00:00Z\nmariadb_server_version=12.2.2-MariaDB\ndump_tool_version=12.2.2-MariaDB\n' >"$fixture/bundle/manifest.properties"
cat >"$fixture/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$TRACE"
case "$1" in
  inspect)
    case "$SCENARIO" in app-running|app-lookup-failure|production-stopped) echo /inventory-db ;; *) echo /db-test57 ;; esac ;;
  ps)
    [[ "$SCENARIO" != app-lookup-failure ]] || exit 1
    [[ "$SCENARIO" != app-running ]] || echo inventory-app ;;
  exec)
    [[ "$SCENARIO" != backup-unavailable ]] || { echo 'root-secret-canary57' >&2; exit 1; }
    case "$*" in
      *MARIADB_DATABASE*) printf '%s' "$MOCK_DATABASE" ;;
      *'mariadb-dump --version'*) echo 'mariadb-dump from 12.2.2-MariaDB, client 10.19' ;;
      *'exec mariadb-dump'*)
        echo 'private-dump-canary57 hash-canary57 idempotency-canary57'
        [[ "$SCENARIO" != backup-dump-failure ]] || { echo 'root-secret-canary57 app-secret-canary57 jwt-secret-canary57' >&2; exit 1; } ;;
      *'SELECT VERSION()'*) echo 12.2.2-MariaDB ;;
      *'ENGINE <>'*) [[ "$SCENARIO" == backup-unsupported-schema ]] && echo 1 || echo 0 ;;
      *'information_schema.SCHEMATA'*) [[ "$SCENARIO" == missing-db ]] && echo 0 || echo 1 ;;
      *'information_schema.TABLES'*)
        [[ "$SCENARIO" != query-failure ]] || { echo 'root-secret-canary57' >&2; exit 1; }
        case "$SCENARIO" in non-empty|replace|production-stopped) echo 1 ;; invalid-count) echo invalid ;; *) echo 0 ;; esac ;;
      *'DROP DATABASE'*) touch "$MOCK_ROOT/replaced" ;;
      *'SELECT 1'*) [[ "$SCENARIO" == ping-failure ]] && echo 0 || echo 1 ;;
      *)
        cat >/dev/null
        touch "$MOCK_ROOT/imported"
        [[ "$SCENARIO" != import-failure ]] || { echo 'private-dump-canary57 hash-canary57' >&2; exit 1; } ;;
    esac ;;
  *) exit 97 ;;
esac
MOCK
cat >"$fixture/bin/gzip" <<'MOCK'
#!/usr/bin/env bash
[[ "$SCENARIO" != backup-gzip-failure || "$1" != -n ]] || exit 1
exec "$REAL_GZIP" "$@"
MOCK
cat >"$fixture/bin/sha256sum" <<'MOCK'
#!/usr/bin/env bash
[[ "$SCENARIO" != backup-checksum-failure || "$*" != *dump.sql.gz* ]] || exit 1
[[ "$SCENARIO" != backup-manifest-failure || "$*" != *dump.sql.gz* ]] || mkdir manifest.properties
exec "$REAL_SHA" "$@"
MOCK
cat >"$fixture/bin/mv" <<'MOCK'
#!/usr/bin/env bash
[[ "$SCENARIO" != backup-publish-failure ]] || exit 1
exec "$REAL_MV" "$@"
MOCK
chmod +x "$fixture/bin/"*
export PATH="$fixture/bin:$PATH"
check() { "$@" || { grep '^ERROR: ' "$fixture/output" >&2 || true; echo 'Mock backup/restore safety assertion failed' >&2; exit 1; }; }
check_not() { if "$@"; then echo 'Mock backup/restore negative assertion failed' >&2; exit 1; fi; }

if [[ "$SCENARIO" == backup-* ]]; then
  case "$SCENARIO" in
    backup-invalid-db) MOCK_DATABASE='bad; DROP DATABASE mysql' ;;
    backup-system-db) MOCK_DATABASE=mysql ;;
    backup-default) unset DB_CONTAINER ;;
  esac
  set +e
  bash "$scripts/backup.sh" >"$fixture/output" 2>&1
  result=$?
  set -e
  if [[ "$SCENARIO" == backup-success || "$SCENARIO" == backup-default ]]; then
    check test "$result" -eq 0
    bundles=("$BACKUP_ROOT/"*)
    check test "${#bundles[@]}" -eq 1
    check test -f "${bundles[0]}/dump.sql.gz"
    check test -f "${bundles[0]}/manifest.properties"
    (cd -- "${bundles[0]}" && "$REAL_SHA" --check --status dump.sha256)
    check grep -q '^Backup completed: ' "$fixture/output"
    if [[ "$SCENARIO" == backup-default ]]; then check grep -q 'exec inventory-db ' "$TRACE"; fi
  else
    check test "$result" -ne 0
    check test -z "$(find "$BACKUP_ROOT" -mindepth 1 -print -quit)"
    check_not grep -q 'Backup completed:' "$fixture/output"
  fi
  check_not grep -Eqi 'DROP DATABASE|CREATE DATABASE|LOCK TABLES|--lock-tables|--all-databases|--databases' "$TRACE"
else
  args=(--bundle "$fixture/bundle" --container db-test57 --database asset_management)
  case "$SCENARIO" in
    tamper) printf x >>"$fixture/bundle/dump.sql.gz" ;;
    checksum-path) printf '%064d  ../dump.sql.gz\n' 0 >"$fixture/bundle/dump.sha256" ;;
    invalid-gzip) printf invalid >"$fixture/bundle/dump.sql.gz"; (cd -- "$fixture/bundle" && "$REAL_SHA" dump.sql.gz >dump.sha256) ;;
    unknown-version) sed -i 's/format_version=1/format_version=2/' "$fixture/bundle/manifest.properties" ;;
    unknown-field) echo 'unknown=value' >>"$fixture/bundle/manifest.properties" ;;
    duplicate-field) echo 'format_version=1' >>"$fixture/bundle/manifest.properties" ;;
    invalid-source) sed -i 's/source_database=.*/source_database=bad; DROP DATABASE mysql/' "$fixture/bundle/manifest.properties" ;;
    invalid-target) args=(--bundle "$fixture/bundle" --container db-test57 --database 'bad; DROP DATABASE mysql') ;;
    system-target) args=(--bundle "$fixture/bundle" --container db-test57 --database mysql) ;;
    name-mismatch) args=(--bundle "$fixture/bundle" --container db-test57 --database renamed) ;;
    wrong-confirm) args+=(--replace --confirm-db asset_managemenT) ;;
    missing-confirm) args+=(--replace) ;;
    confirm-without-replace) args+=(--confirm-db asset_management) ;;
    replace|app-running|app-lookup-failure|production-stopped) args+=(--replace --confirm-db asset_management) ;;
    artifact-symlink) mv "$fixture/bundle/dump.sql.gz" "$fixture/outside.gz"; MSYS=winsymlinks:sys ln -s "$fixture/outside.gz" "$fixture/bundle/dump.sql.gz" ;;
    missing-arguments) args=() ;;
  esac
  set +e
  bash "$scripts/restore.sh" "${args[@]}" >"$fixture/output" 2>&1
  result=$?
  set -e
  if [[ "$SCENARIO" == restore-success || "$SCENARIO" == replace || "$SCENARIO" == production-stopped ]]; then
    check test "$result" -eq 0
    check test -f "$fixture/imported"
    check grep -q '^Restore completed: asset_management' "$fixture/output"
    if [[ "$SCENARIO" != restore-success ]]; then check test -f "$fixture/replaced"; fi
  else
    check test "$result" -ne 0
    check_not grep -q 'Restore completed:' "$fixture/output"
    if [[ "$SCENARIO" != import-failure && "$SCENARIO" != ping-failure ]]; then
      check test ! -f "$fixture/imported"
      check test ! -f "$fixture/replaced"
    fi
  fi
  case "$SCENARIO" in
    tamper|checksum-path|invalid-gzip|unknown-version|unknown-field|duplicate-field|invalid-source|invalid-target|system-target|name-mismatch|wrong-confirm|missing-confirm|confirm-without-replace|artifact-symlink|missing-arguments)
      check test ! -s "$TRACE" ;;
  esac
  check_not grep -Eq 'volume rm|down -v' "$TRACE"
fi
for secret in root-secret-canary57 app-secret-canary57 jwt-secret-canary57 hash-canary57 idempotency-canary57 private-dump-canary57; do
  check_not grep -Fq "$secret" "$fixture/output"
  check_not grep -Fq "$secret" "$TRACE"
done

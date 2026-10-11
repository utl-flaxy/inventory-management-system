#!/usr/bin/env bash
set -euo pipefail
umask 077
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

db_container="${DB_CONTAINER:-inventory-db}"
backup_root="${BACKUP_ROOT:-$HOME/inventory-management-system-backups}"
[[ $# -eq 0 ]] || fail 'Use DB_CONTAINER and BACKUP_ROOT to select source/output'
valid_container "$db_container"
require_tools docker gzip sha256sum mktemp date chmod mv
[[ ! -L "$backup_root" ]] || fail 'Backup root must not be a symlink'
mkdir -p -- "$backup_root"
chmod 0700 -- "$backup_root"
backup_root=$(cd -- "$backup_root" && pwd -P)
partial=$(mktemp -d "$backup_root/.partial.XXXXXXXX")
cleanup() {
  local result=$?
  trap - EXIT
  if [[ -n "$partial" && "$partial" == "$backup_root"/.partial.* ]]; then rm -rf -- "$partial"; fi
  (( result == 0 )) || echo 'ERROR: Backup failed; no completed bundle was published' >&2
  exit "$result"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
chmod 0700 -- "$partial"

source_database=$(docker exec "$db_container" sh -c 'printf "%s" "$MARIADB_DATABASE"' 2>/dev/null) || fail 'Cannot read source database name'
valid_database "$source_database"
# 現行schema向けのInnoDB snapshot。将来routines/eventsを追加した場合は黙って欠落させない。
unsupported=$(db_query "SELECT (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$source_database' AND TABLE_TYPE='BASE TABLE' AND ENGINE <> 'InnoDB') + (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='$source_database') + (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA='$source_database')") || fail 'Cannot inspect source schema'
[[ "$unsupported" == 0 ]] || fail 'Backup requires InnoDB tables and no stored routines/events'
server_version=$(db_query 'SELECT VERSION()') || fail 'Cannot read server version'
tool_output=$(docker exec "$db_container" mariadb-dump --version 2>/dev/null) || fail 'mariadb-dump is unavailable'
[[ "$server_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9_.+~-]*$ ]] || fail 'Invalid server version metadata'
[[ "$tool_output" =~ ([0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9_.+~-]*) ]] || fail 'Invalid dump version metadata'
tool_version="${BASH_REMATCH[1]}"
created=$(date -u +%Y-%m-%dT%H:%M:%SZ)
stamp=$(date -u +%Y%m%dT%H%M%SZ)

# DB名を単独指定し、CREATE DATABASE/USEを埋め込まない。sourceへのDDL/書き込み/LOCKは行わない。
if ! docker exec "$db_container" sh -c '
    test -n "$MARIADB_ROOT_PASSWORD" || exit 1
    export MYSQL_PWD="$MARIADB_ROOT_PASSWORD"
    exec mariadb-dump --protocol=socket --user=root --single-transaction --quick \
      --skip-lock-tables --skip-add-locks --default-character-set=utf8mb4 \
      --triggers --no-create-db --skip-comments "$1"
  ' sh "$source_database" 2>/dev/null | gzip -n >"$partial/dump.sql.gz"; then
  fail 'Dump/compression failed'
fi
gzip -t "$partial/dump.sql.gz" 2>/dev/null || fail 'Compressed dump is invalid'
(cd -- "$partial" && sha256sum --binary dump.sql.gz >dump.sha256)
printf 'format_version=1\nsource_database=%s\ncreated_at_utc=%s\nmariadb_server_version=%s\ndump_tool_version=%s\n' \
  "$source_database" "$created" "$server_version" "$tool_version" >"$partial/manifest.properties"
chmod 0600 -- "$partial/dump.sql.gz" "$partial/dump.sha256" "$partial/manifest.properties"
final="$backup_root/${stamp}_${source_database}_${partial##*.}"
[[ ! -e "$final" ]] || fail 'Bundle destination already exists'
mv -T -- "$partial" "$final"
partial=''
printf 'Backup completed: %s\n' "$final"

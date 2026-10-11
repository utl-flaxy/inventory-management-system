#!/usr/bin/env bash
set -euo pipefail
umask 077
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

bundle='' db_container='' target_database='' confirmation='' replace=false
while (( $# )); do
  case "$1" in
    --bundle|--container|--database|--confirm-db)
      [[ $# -ge 2 && -n "$2" ]] || fail 'Option value is required'
      case "$1" in
        --bundle) bundle="$2" ;; --container) db_container="$2" ;;
        --database) target_database="$2" ;; --confirm-db) confirmation="$2" ;;
      esac
      shift 2 ;;
    --replace) replace=true; shift ;;
    *) fail 'Unknown restore option' ;;
  esac
done
[[ -n "$bundle" && -n "$db_container" && -n "$target_database" ]] || fail 'Explicit bundle, container and database are required'
require_tools docker gzip sha256sum mktemp chmod cp
[[ -d "$bundle" && ! -L "$bundle" ]] || fail 'A regular backup bundle directory is required'
for name in dump.sql.gz dump.sha256 manifest.properties; do
  [[ -f "$bundle/$name" && ! -L "$bundle/$name" ]] || fail 'Bundle artifact is missing or is a symlink'
done
# 検証したcompressed bytesをimportする。plaintext dumpは作らない。
temp_root=$(cd -- "${TMPDIR:-/tmp}" && pwd -P)
work=$(mktemp -d "$temp_root/inventory-restore.XXXXXXXX")
cleanup() {
  local result=$?
  trap - EXIT
  [[ "$work" != "$temp_root"/inventory-restore.* ]] || rm -rf -- "$work"
  (( result == 0 )) || echo 'ERROR: Restore failed; keep writes frozen and inspect the target privately' >&2
  exit "$result"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
chmod 0700 -- "$work"
cp -- "$bundle/dump.sql.gz" "$bundle/dump.sha256" "$bundle/manifest.properties" "$work/"
chmod 0600 -- "$work/"*
mapfile -t checksum <"$work/dump.sha256"
checksum_pattern='^[0-9a-f]{64} [ *]dump\.sql\.gz$'
[[ ${#checksum[@]} -eq 1 && "${checksum[0]}" =~ $checksum_pattern ]] || fail 'Invalid checksum sidecar'
(cd -- "$work" && sha256sum --check --status dump.sha256) 2>/dev/null || fail 'Backup checksum mismatch'
gzip -t "$work/dump.sql.gz" 2>/dev/null || fail 'Invalid gzip backup'

declare -A metadata=()
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ "$line" =~ ^([a-z_]+)=(.*)$ ]] || fail 'Invalid manifest'
  key="${BASH_REMATCH[1]}" value="${BASH_REMATCH[2]}"
  case "$key" in format_version|source_database|created_at_utc|mariadb_server_version|dump_tool_version) ;; *) fail 'Unknown manifest field' ;; esac
  [[ ! -v "metadata[$key]" ]] || fail 'Duplicate manifest field'
  metadata[$key]="$value"
done <"$work/manifest.properties"
[[ ${#metadata[@]} -eq 5 && "${metadata[format_version]:-}" == 1 ]] || fail 'Unsupported manifest version/fields'
valid_database "${metadata[source_database]}"
[[ "${metadata[created_at_utc]}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || fail 'Invalid manifest timestamp'
for key in mariadb_server_version dump_tool_version; do
  [[ "${metadata[$key]}" =~ ^[0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9_.+~-]*$ ]] || fail 'Invalid manifest version metadata'
done
valid_container "$db_container"
valid_database "$target_database"
[[ "$target_database" == "${metadata[source_database]}" ]] || fail 'Source and target database names must match'
if [[ "$replace" == true ]]; then
  [[ "$confirmation" == "$target_database" ]] || fail 'Replace requires exact --confirm-db'
else
  [[ -z "$confirmation" ]] || fail 'Confirmation requires --replace'
fi

container_name=$(docker inspect --format '{{.Name}}' "$db_container" 2>/dev/null) || fail 'Cannot inspect target container'
if [[ "$container_name" == /inventory-db ]]; then
  running_app=$(docker ps --filter 'name=^/inventory-app$' --format '{{.Names}}' 2>/dev/null) || fail 'Cannot check production app state'
  [[ -z "$running_app" ]] || fail 'Stop inventory-app and freeze all writers before production restore'
fi
exists=$(db_query "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$target_database'") || fail 'Cannot inspect target database'
[[ "$exists" == 1 ]] || fail 'Target database must already exist'
objects=$(db_query "SELECT (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$target_database') + (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='$target_database') + (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA='$target_database')") || fail 'Cannot inspect target contents'
[[ "$objects" =~ ^[0-9]+$ ]] || fail 'Invalid target object count'
if [[ "$replace" == true ]]; then
  db_query "DROP DATABASE \`$target_database\`; CREATE DATABASE \`$target_database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" >/dev/null || fail 'Target replacement failed'
else
  [[ "$objects" == 0 ]] || fail 'Non-empty target requires --replace and exact --confirm-db'
fi
if ! gzip -dc "$work/dump.sql.gz" | db_client "--database=$target_database" >/dev/null; then
  fail 'Import failed; restore is incomplete'
fi
[[ $(db_client "--database=$target_database" --batch --skip-column-names --execute='SELECT 1') == 1 ]] || fail 'Post-restore connection check failed'
printf 'Restore completed: %s\n' "$target_database"

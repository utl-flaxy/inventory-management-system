#!/usr/bin/env bash
# backup/restore専用。呼び出し元がstrict modeとprivate umaskを設定する。
fail() { echo "ERROR: $1" >&2; exit 1; }
valid_database() {
  [[ "$1" =~ ^[A-Za-z][A-Za-z0-9_]*$ && ${#1} -le 64 ]] || fail 'Invalid database identifier'
  case "${1,,}" in mysql|sys|information_schema|performance_schema) fail 'System database is forbidden' ;; esac
}
valid_container() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || fail 'Invalid container identifier'
}
require_tools() {
  local tool
  for tool in "$@"; do command -v "$tool" >/dev/null || fail 'Required command is unavailable'; done
}
db_client() {
  # このsingle-quoted shellはcontainer内でのみ展開。host argvに実secretを含めない。
  docker exec -i "$db_container" sh -c '
    test -n "$MARIADB_ROOT_PASSWORD" || exit 1
    export MYSQL_PWD="$MARIADB_ROOT_PASSWORD"
    exec mariadb --protocol=socket --user=root --default-character-set=utf8mb4 "$@"
  ' sh "$@" 2>/dev/null
}
db_query() { db_client --batch --skip-column-names --execute="$1"; }

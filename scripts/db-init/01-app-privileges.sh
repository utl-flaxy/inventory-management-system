#!/usr/bin/env bash
# Official MariaDB image runs this only while initializing an empty volume.
# A subshell also keeps options/variables private when the image sources this file.
(
  set -euo pipefail
  db_name="${MARIADB_DATABASE:?MARIADB_DATABASE is required}"
  db_user="${MARIADB_USER:?MARIADB_USER is required}"
  db_root_secret="${MARIADB_ROOT_PASSWORD:?MARIADB_ROOT_PASSWORD is required}"
  [[ "$db_name" =~ ^[A-Za-z][A-Za-z0-9_]*$ ]] || { echo 'Invalid DB name' >&2; exit 1; }
  [[ "$db_user" =~ ^[A-Za-z][A-Za-z0-9_]*$ && "${db_user,,}" != root ]] ||
    { echo 'A dedicated DB user is required' >&2; exit 1; }
  # Escape privilege-pattern underscores: grants must apply to this DB only.
  db_grant_name="${db_name//_/\\_}"
  # Keep the password out of argv/output. No global privileges or GRANT OPTION.
  MYSQL_PWD="$db_root_secret" mariadb --protocol=socket --user=root <<SQL
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$db_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON \`$db_grant_name\`.* TO '$db_user'@'%';
SQL
)

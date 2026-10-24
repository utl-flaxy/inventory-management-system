#!/usr/bin/env bash
# ssh-action sends script_path as shell commands; explicitly select Bash remotely.
bash <<'SAFE_DEPLOY'
set -euo pipefail
umask 077

fail() { echo "ERROR: $*" >&2; exit 1; }
[[ -n "${DEPLOY_SHA:-}" ]] || fail "DEPLOY_SHA is required"
[[ "$DEPLOY_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "DEPLOY_SHA must be a full lowercase commit SHA"
[[ -n "${JWT_SECRET:-}" ]] || fail "JWT_SECRET is required"
[[ $(printf '%s' "$JWT_SECRET" | wc -c) -ge 32 ]] || fail "JWT_SECRET must be at least 32 bytes"
export JWT_SECRET
for name in DB_NAME DB_USERNAME DB_PASSWORD DB_ROOT_PASSWORD; do
  [[ -n "${!name:-}" ]] || fail "$name is required"
done
[[ "$DB_NAME" =~ ^[A-Za-z][A-Za-z0-9_]*$ ]] || fail "DB_NAME must be a simple database identifier"
[[ "$DB_USERNAME" =~ ^[A-Za-z][A-Za-z0-9_]*$ ]] || fail "DB_USERNAME must be a simple user identifier"
[[ "${DB_USERNAME,,}" != root ]] || fail "DB_USERNAME must not be root"
export DB_NAME DB_USERNAME DB_PASSWORD DB_ROOT_PASSWORD
[[ "${APP_MEMORY_LIMIT:-}" =~ ^[1-9][0-9]*(m|g)$ ]] || fail "APP_MEMORY_LIMIT must be an explicit positive m/g budget"
export APP_MEMORY_LIMIT
export SPRING_PROFILES_ACTIVE=prod

cd "${DEPLOY_DIR:-$HOME/inventory-management-system}"
[[ -d .git ]] || fail "Deployment directory must be a Git checkout"
mkdir -p .deploy
chmod 700 .deploy
# Covers manual invocations too, including checkout and build.
exec 9>.deploy/lock
flock -n 9 || fail "Another deployment holds the host lock"

echo "Preparing verified commit $DEPLOY_SHA"
git fetch origin
git cat-file -e "${DEPLOY_SHA}^{commit}"
docker info >/dev/null

project=$(docker inspect inventory-db --format '{{index .Config.Labels "com.docker.compose.project"}}')
[[ -n "$project" && "$project" != '<no value>' ]] || fail "Existing Compose DB project is required"
[[ $(docker inspect inventory-db --format '{{.State.Running}}') == true ]] || fail "DB must already be running"
compose=(docker compose -p "$project")
work=$(mktemp -d "$PWD/.deploy/pending.XXXXXX")
previous_image=''
previous_sha='unknown'
previous_health='/v3/api-docs'
cutover=false
secret_values=("$JWT_SECRET" "$DB_PASSWORD" "$DB_ROOT_PASSWORD")

remember_secrets() {
  local entry="$1" key value
  key=${entry%%=*}
  value=${entry#*=}
  case "$key" in
    *PASSWORD*|*SECRET*|*TOKEN*) [[ -z "$value" ]] || secret_values+=("$value") ;;
  esac
}

redact_logs() {
  local content value
  # The input is bounded to 100 log lines; redact entire values across line boundaries.
  # A sentinel prevents command substitution from stripping secret-ending newlines.
  content=$(cat; printf '.')
  content=${content%.}
  for value in "${secret_values[@]}"; do
    content=${content//"$value"/[REDACTED]}
  done
  printf '%s\n' "$content"
}

app_logs() {
  local entry
  if docker inspect inventory-app --format '{{range .Config.Env}}{{printf "%s%c" . 0}}{{end}}' \
      >"$work/log-environment" 2>/dev/null; then
    while IFS= read -r -d '' entry; do remember_secrets "$entry"; done <"$work/log-environment"
  fi
  docker logs --tail 100 inventory-app 2>&1 | redact_logs || true
}

health() {
  local attempt body status path="${1:-/readyz}"
  # capabilityは既知の経路だけを許可し、label由来の任意URLを使用しない。
  case "$path" in
    /readyz|/actuator/health|/v3/api-docs) ;;
    *) return 1 ;;
  esac
  local endpoint="http://127.0.0.1:8080$path"
  for ((attempt=1; attempt<=30; attempt++)); do
    if status=$(curl --fail --silent --output "$work/health-response" --write-out '%{http_code}' \
        --connect-timeout 2 --max-time 5 "$endpoint") && [[ "$status" == 200 ]]; then
      [[ "$path" != /v3/api-docs ]] || return 0
      body=$(cat "$work/health-response")
      # health detailsを公開しない新appはstatusのみ。200/DOWNも成功扱いしない。
      [[ "$body" =~ ^[[:space:]]*\{[[:space:]]*\"status\"[[:space:]]*:[[:space:]]*\"UP\"[[:space:]]*\}[[:space:]]*$ ]] && return 0
    fi
    sleep 2
  done
  return 1
}

finish() {
  local result=$?
  trap - EXIT
  # Machine-readable outcome: DEPLOYED / SKIPPED (exit 0, nothing changed) / FAILED.
  (( result == 0 )) || echo "DEPLOY_RESULT=FAILED"
  if (( result != 0 )); then
    if [[ "$cutover" == true ]]; then
      echo "Target $DEPLOY_SHA failed; collecting redacted app logs" >&2
      app_logs
      if [[ -n "$previous_image" ]]; then
        echo "Restoring previous app image $previous_image (revision $previous_sha)" >&2
        if "${compose[@]}" -f "$work/previous-compose.yml" -f "$work/previous-image.yml" \
            up -d --no-deps --no-build --pull never --force-recreate app && \
            [[ $(docker inspect inventory-app --format '{{.Image}}') == "$previous_image" ]] && health "$previous_health"; then
          echo "Previous app is healthy; deployment failed and current is unchanged" >&2
        else
          echo "ERROR: App rollback failed. DB was not rolled back; operator recovery is required." >&2
          app_logs
        fi
      else
        echo "ERROR: Initial deployment has no previous app image; preserving DB and failed app for investigation." >&2
      fi
    else
      echo "Preparation failed before cutover; existing app was not changed" >&2
    fi
  fi
  if (( result != 0 )) && [[ "$cutover" == true ]]; then
    echo "Private recovery snapshots retained at $work" >&2
  else
    rm -rf -- "$work"
  fi
  exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Snapshot before changing source files. Resolved Compose contains secrets, so keep it private.
if previous_image=$(docker inspect inventory-app --format '{{.Image}}' 2>/dev/null); then
  [[ "$previous_image" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Invalid running app image ID"
  [[ $(docker inspect inventory-app --format '{{index .Config.Labels "com.docker.compose.project"}}') == "$project" ]] \
      || fail "App and DB must belong to the same Compose project"
  docker image inspect "$previous_image" >/dev/null
  previous_sha=$(docker image inspect "$previous_image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
  [[ "$previous_sha" =~ ^[0-9a-f]{40}$ ]] || previous_sha=unknown
  # 新imageはDockerfile labelでcapabilityを固定。欠落した旧imageだけlegacy扱い。
  previous_health=$(docker image inspect "$previous_image" --format '{{index .Config.Labels "inventory-management-system.health-path"}}')
  case "$previous_health" in
    /readyz|/actuator/health) ;;
    ''|'<no value>') previous_health='/v3/api-docs' ;;
    *) fail "Unsupported previous health endpoint" ;;
  esac
  # NUL preserves embedded newlines/quotes in secrets and the previous JWT environment.
  docker inspect inventory-app --format '{{range .Config.Env}}{{printf "%s%c" . 0}}{{end}}' >"$work/previous-environment"
  old_secret="$JWT_SECRET"
  while IFS= read -r -d '' entry; do
    remember_secrets "$entry"
    [[ "$entry" != JWT_SECRET=* ]] || old_secret=${entry#JWT_SECRET=}
  done <"$work/previous-environment"
  if [[ -f .deploy/current-image && -f .deploy/current-compose.yml && \
        $(cat .deploy/current-image) == "$previous_image" ]]; then
    cp .deploy/current-compose.yml "$work/previous-compose.yml"
  else
    # Adopt a pre-Issue-17 app using its checkout and actual JWT environment.
    JWT_SECRET="$old_secret" "${compose[@]}" -f docker-compose.yml config >"$work/previous-compose.yml"
  fi
  printf 'services:\n  app:\n    image: %s\n' "$previous_image" >"$work/previous-image.yml"
  printf '%s\n' "$previous_sha" >"$work/previous"
  printf '%s\n' "$previous_image" >"$work/previous-image"
else
  previous_image=''
fi

# Issue #157: CI completion order is not commit order, and `concurrency` only serializes runs.
# Under the host lock and after fetch, deploy only forward along origin/main from the verified running
# revision. Older targets are skipped visibly; unknown/inconsistent state fails for operator diagnosis.
# Rolling back to an older binary is never done implicitly by this automatic path.
main_ref=refs/remotes/origin/main
git rev-parse --verify --quiet "${main_ref}^{commit}" >/dev/null || fail "origin/main is unavailable after fetch"
git merge-base --is-ancestor "$DEPLOY_SHA" "$main_ref" \
  || fail "DEPLOY_SHA is not on origin/main; branch or rewritten-history deployment is refused"
marker_sha=''
if [[ -e .deploy/current ]]; then
  marker_sha=$(cat .deploy/current)
  [[ "$marker_sha" =~ ^[0-9a-f]{40}$ ]] || fail "Deployed revision marker is malformed; operator diagnosis required"
fi
running_sha=''
if [[ -z "$previous_image" ]]; then
  [[ -z "$marker_sha" ]] || fail "A deployed revision marker exists but no app container exists; operator diagnosis required"
else
  [[ "$previous_sha" != unknown ]] || fail "Running app image has no verifiable revision label; operator diagnosis required"
  [[ -n "$marker_sha" ]] || fail "Running app has no deployed revision marker; operator diagnosis required"
  [[ "$marker_sha" == "$previous_sha" ]] || fail "Deployed revision marker does not match the running image label"
  git cat-file -e "${previous_sha}^{commit}" 2>/dev/null || fail "Running revision is not a known commit; operator diagnosis required"
  running_sha=$previous_sha
fi
skip() {
  echo "DEPLOY_RESULT=SKIPPED reason=$1 target=$DEPLOY_SHA running=$running_sha"
  echo "No deployment was performed; the running app and deployment markers are unchanged."
  exit 0
}
if [[ -n "$running_sha" ]]; then
  if [[ "$running_sha" == "$DEPLOY_SHA" ]]; then
    skip already-running
  elif git merge-base --is-ancestor "$DEPLOY_SHA" "$running_sha"; then
    skip stale
  else
    # Only exit 1 (not an ancestor) may continue; a Git error (e.g. 128) must not reach the reverse check.
    rc=$?
    [[ "$rc" -eq 1 ]] || fail "Git ancestry check failed (exit $rc); operator diagnosis required"
  fi
  # Exit 1 = not an ancestor; any other git error also refuses (fail closed).
  git merge-base --is-ancestor "$running_sha" "$DEPLOY_SHA" \
    || fail "DEPLOY_SHA is neither the running revision nor a descendant of it; operator diagnosis required"
fi

git checkout --detach "$DEPLOY_SHA"
ACTUAL_SHA=$(git rev-parse HEAD)
[[ "$ACTUAL_SHA" == "$DEPLOY_SHA" ]] || fail "Checked-out HEAD does not match DEPLOY_SHA"
[[ -f mvnw && -f Dockerfile && -f docker-compose.yml ]] || fail "Required build files are missing"
chmod +x mvnw
# The digest-pinned Docker build stage compiles once; no host Maven/JDK or stale host jar.
source_epoch=$(git show -s --format=%ct "$DEPLOY_SHA")
[[ "$source_epoch" =~ ^[0-9]+$ ]] || fail "Invalid commit build timestamp"
export APP_IMAGE_TAG="$DEPLOY_SHA"
image="inventory-management-system-app:$DEPLOY_SHA"
"${compose[@]}" -f docker-compose.yml config >"$work/target-compose.yml"
docker build --build-arg "REVISION=$DEPLOY_SHA" --build-arg "SOURCE_DATE_EPOCH=$source_epoch" \
  --label "org.opencontainers.image.revision=$DEPLOY_SHA" -t "$image" .
[[ $(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}') == "$DEPLOY_SHA" ]] \
    || fail "Built image revision does not match DEPLOY_SHA"
target_image=$(docker image inspect "$image" --format '{{.Id}}')
[[ "$target_image" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Invalid target image ID"
[[ $(docker image inspect "$target_image" --format '{{index .Config.Labels "inventory-management-system.health-path"}}') == /readyz ]] \
  || fail "Target image must declare readiness"
# Pin the actual built image too; changing a tag cannot change the cutover.
printf 'services:\n  app:\n    image: %s\n' "$target_image" >"$work/target-image.yml"
[[ $(git rev-parse HEAD) == "$DEPLOY_SHA" ]] || fail "HEAD changed before cutover"
# The ordering decision above must still describe the app being replaced (e.g. no manual change during build).
[[ $(docker inspect inventory-app --format '{{.Image}}' 2>/dev/null || true) == "$previous_image" ]] \
  || fail "Running app changed during deployment preparation"
[[ $(cat .deploy/current 2>/dev/null || true) == "$marker_sha" ]] \
  || fail "Deployed revision marker changed during deployment preparation"

echo "Switching app to $image; DB remains running"
cutover=true
"${compose[@]}" -f "$work/target-compose.yml" -f "$work/target-image.yml" \
    up -d --no-deps --no-build --pull never --force-recreate app
health /readyz || fail "Target HTTP readiness check failed"
[[ $(docker inspect inventory-app --format '{{.Image}}') == "$target_image" ]] || fail "Running image does not match the built image"

# Commit metadata only after HTTP success. The current marker is written last.
if [[ -n "$previous_image" ]]; then
  cp "$work/previous-compose.yml" .deploy/previous-compose.yml
  mv "$work/previous" .deploy/previous
  mv "$work/previous-image" .deploy/previous-image
fi
cp "$work/target-compose.yml" .deploy/current-compose.yml
printf '%s\n' "$target_image" >"$work/current-image"
printf '%s\n' "$DEPLOY_SHA" >"$work/current"
mv "$work/current-image" .deploy/current-image
mv "$work/current" .deploy/current
echo "Deployment healthy: $DEPLOY_SHA ($target_image)"
echo "DEPLOY_RESULT=DEPLOYED target=$DEPLOY_SHA"
SAFE_DEPLOY

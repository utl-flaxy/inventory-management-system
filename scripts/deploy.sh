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

cd "${DEPLOY_DIR:-$HOME/inventory-management-system}"
[[ -d .git ]] || fail "Deployment directory must be a Git checkout"
mkdir -p .deploy
chmod 700 .deploy
# Covers manual invocations too, including checkout and build.
exec 9>.deploy/lock
flock -n 9 || fail "Another deployment holds the host lock"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-amazon-corretto}"
export PATH="$JAVA_HOME/bin:$PATH"
java_version=$(java -version 2>&1)
[[ "$java_version" == *'version "17.'* ]] || fail "Java 17 is required"
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
cutover=false
secret_values=("$JWT_SECRET")

remember_secrets() {
  local entry key value
  while IFS= read -r entry; do
    key=${entry%%=*}
    value=${entry#*=}
    case "$key" in
      *PASSWORD*|*SECRET*|*TOKEN*) [[ -z "$value" ]] || secret_values+=("$value") ;;
    esac
  done <<< "$1"
}

redact_logs() {
  local line value
  while IFS= read -r line || [[ -n "$line" ]]; do
    for value in "${secret_values[@]}"; do
      line=${line//"$value"/[REDACTED]}
    done
    printf '%s\n' "$line"
  done
}

app_logs() {
  local env_text
  env_text=$(docker inspect inventory-app --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null) || env_text=''
  remember_secrets "$env_text"
  docker logs --tail 100 inventory-app 2>&1 | redact_logs || true
}

health() {
  local attempt
  # Public, read-only HTTP endpoint; Boot/Flyway must finish startup to serve it.
  for ((attempt=1; attempt<=30; attempt++)); do
    if curl --fail --silent --output /dev/null --connect-timeout 2 --max-time 5 \
        http://127.0.0.1:8080/v3/api-docs; then
      return 0
    fi
    sleep 2
  done
  return 1
}

finish() {
  local result=$?
  trap - EXIT
  if (( result != 0 )); then
    if [[ "$cutover" == true ]]; then
      echo "Target $DEPLOY_SHA failed; collecting redacted app logs" >&2
      app_logs
      if [[ -n "$previous_image" ]]; then
        echo "Restoring previous app image $previous_image (revision $previous_sha)" >&2
        if "${compose[@]}" -f "$work/previous-compose.yml" -f "$work/previous-image.yml" \
            up -d --no-deps --no-build --pull never --force-recreate app && \
            [[ $(docker inspect inventory-app --format '{{.Image}}') == "$previous_image" ]] && health; then
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
  old_env=$(docker inspect inventory-app --format '{{range .Config.Env}}{{println .}}{{end}}')
  remember_secrets "$old_env"
  if [[ -f .deploy/current-image && -f .deploy/current-compose.yml && \
        $(cat .deploy/current-image) == "$previous_image" ]]; then
    cp .deploy/current-compose.yml "$work/previous-compose.yml"
  else
    # Adopt a pre-Issue-17 app using its checkout and actual JWT environment.
    old_secret="$JWT_SECRET"
    while IFS= read -r entry; do
      [[ "$entry" != JWT_SECRET=* ]] || old_secret=${entry#JWT_SECRET=}
    done <<< "$old_env"
    JWT_SECRET="$old_secret" "${compose[@]}" -f docker-compose.yml config >"$work/previous-compose.yml"
  fi
  printf 'services:\n  app:\n    image: %s\n' "$previous_image" >"$work/previous-image.yml"
  printf '%s\n' "$previous_sha" >"$work/previous"
  printf '%s\n' "$previous_image" >"$work/previous-image"
else
  previous_image=''
fi

git checkout --detach "$DEPLOY_SHA"
ACTUAL_SHA=$(git rev-parse HEAD)
[[ "$ACTUAL_SHA" == "$DEPLOY_SHA" ]] || fail "Checked-out HEAD does not match DEPLOY_SHA"
[[ -f mvnw && -f Dockerfile && -f docker-compose.yml ]] || fail "Required build files are missing"
chmod +x mvnw
./mvnw clean package -DskipTests
[[ -s target/demo-0.0.1-SNAPSHOT.jar ]] || fail "Built jar is missing"
export APP_IMAGE_TAG="$DEPLOY_SHA"
image="inventory-management-system-app:$DEPLOY_SHA"
"${compose[@]}" -f docker-compose.yml config >"$work/target-compose.yml"
docker build --label "org.opencontainers.image.revision=$DEPLOY_SHA" -t "$image" .
[[ $(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}') == "$DEPLOY_SHA" ]] \
    || fail "Built image revision does not match DEPLOY_SHA"
target_image=$(docker image inspect "$image" --format '{{.Id}}')
[[ "$target_image" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Invalid target image ID"
# Pin the actual built image too; changing a tag cannot change the cutover.
printf 'services:\n  app:\n    image: %s\n' "$target_image" >"$work/target-image.yml"
[[ $(git rev-parse HEAD) == "$DEPLOY_SHA" ]] || fail "HEAD changed before cutover"

echo "Switching app to $image; DB remains running"
cutover=true
"${compose[@]}" -f "$work/target-compose.yml" -f "$work/target-image.yml" \
    up -d --no-deps --no-build --pull never --force-recreate app
health || fail "Target HTTP health check failed"
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
SAFE_DEPLOY

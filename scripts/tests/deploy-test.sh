#!/usr/bin/env bash
# Only mock binaries are used: no EC2, network, real Git or Docker operations.
set -euo pipefail
script=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
export SCENARIO="$2"
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
export MOCK_ROOT="$fixture" TRACE="$fixture/trace" DEPLOY_DIR="$fixture/repo"
export DEPLOY_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export OLD_SHA=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
export OLD_IMAGE="sha256:$(printf '%064d' 1)" NEW_IMAGE="sha256:$(printf '%064d' 2)"
export JWT_SECRET='new-test-secret-only-012345678901234567890'
export OLD_SECRET='old-test-secret-only-012345678901234567890'
export DB_PASSWORD='test-password-never-print'
export JAVA_HOME="$fixture/java"
mkdir -p "$fixture/bin" "$JAVA_HOME/bin" "$DEPLOY_DIR/.git" "$DEPLOY_DIR/target" "$DEPLOY_DIR/.deploy"
: >"$TRACE"
printf '%s\n' "$OLD_SHA" >"$DEPLOY_DIR/.deploy/current"
printf '%s\n' "$OLD_IMAGE" >"$DEPLOY_DIR/.deploy/current-image"
printf 'services: {app: {image: old}}\n' >"$DEPLOY_DIR/.deploy/current-compose.yml"
printf 'services: {app: {image: local}}\n' >"$DEPLOY_DIR/docker-compose.yml"
: >"$DEPLOY_DIR/Dockerfile"

cat >"$fixture/bin/mock" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
tool=$(basename "$0")
printf '%s %s\n' "$tool" "$*" >>"$TRACE"
case "$tool" in
  java) echo 'openjdk version "17.0.18"' >&2 ;;
  flock) [[ "$SCENARIO" != locked ]] ;;
  sleep) : ;;
  git)
    case "$1" in
      fetch) [[ "$SCENARIO" != fetch-failure ]] ;;
      cat-file) [[ "$SCENARIO" != unknown-sha ]] ;;
      checkout) [[ "$2" == --detach && "$3" == "$DEPLOY_SHA" ]] ;;
      rev-parse)
        if [[ "$SCENARIO" == head-mismatch ]]; then echo "$OLD_SHA"; else echo "$DEPLOY_SHA"; fi ;;
      *) exit 97 ;;
    esac ;;
  mvnw)
    [[ "$*" == 'clean package -DskipTests' ]] || exit 98
    [[ "$SCENARIO" != maven-failure ]] || exit 1
    [[ "$SCENARIO" == missing-jar ]] || echo jar >target/demo-0.0.1-SNAPSHOT.jar ;;
  curl)
    [[ -f "$MOCK_ROOT/active" ]] || exit 96
    active=$(cat "$MOCK_ROOT/active")
    case "$SCENARIO" in
      health-failure|rollback-start-failure|first-health-failure)
        [[ "$active" == previous ]] ;;
      rollback-health-failure) exit 22 ;;
      health-retry)
        count=0; [[ ! -f "$MOCK_ROOT/checks" ]] || count=$(cat "$MOCK_ROOT/checks")
        echo "$((count+1))" >"$MOCK_ROOT/checks"
        ((count >= 2)) ;;
      *) : ;;
    esac ;;
  docker)
    case "$1" in
      info) : ;;
      inspect)
        if [[ "$2" == inventory-db ]]; then
          if [[ "$*" == *State.Running* ]]; then
            [[ "$SCENARIO" == db-stopped ]] && echo false || echo true
          else echo inventory-management-system; fi
        elif [[ "$2" == inventory-app ]]; then
          [[ "$SCENARIO" != first-success && "$SCENARIO" != first-health-failure ]] || {
            [[ -f "$MOCK_ROOT/active" ]] || exit 1
          }
          if [[ "$*" == *Config.Env* ]]; then
            printf 'JWT_SECRET=%s\nSPRING_DATASOURCE_PASSWORD=%s\n' "$OLD_SECRET" "$DB_PASSWORD"
          elif [[ "$*" == *compose.project* ]]; then echo inventory-management-system
          elif [[ "$*" == *'{{.Image}}'* ]]; then
            if [[ -f "$MOCK_ROOT/active" && $(cat "$MOCK_ROOT/active") == target ]]; then echo "$NEW_IMAGE"; else echo "$OLD_IMAGE"; fi
          else exit 95; fi
        else exit 94; fi ;;
      image)
        if [[ "$3" == "$OLD_IMAGE" ]]; then
          [[ "$*" != *revision* ]] || echo "$OLD_SHA"
        elif [[ "$*" == *revision* ]]; then
          [[ "$SCENARIO" == image-mismatch ]] && echo "$OLD_SHA" || echo "$DEPLOY_SHA"
        elif [[ "$*" == *'{{.Id}}'* ]]; then echo "$NEW_IMAGE"
        else exit 93; fi ;;
      build) [[ "$SCENARIO" != docker-failure ]] ;;
      logs) printf 'diagnostic JWT=%s old=%s db=%s\n' "$JWT_SECRET" "$OLD_SECRET" "$DB_PASSWORD" ;;
      compose)
        if [[ "$*" == *' config' ]]; then
          [[ "$SCENARIO" != config-failure ]] || exit 1
          printf 'services: {app: {image: target}}\n'
        elif [[ "$*" == *' up '* ]]; then
          [[ "$*" == *'up -d --no-deps --no-build --pull never --force-recreate app' ]] || exit 92
          if [[ "$*" == *previous-image.yml* ]]; then
            [[ "$SCENARIO" != rollback-start-failure ]] || exit 1
            echo previous >"$MOCK_ROOT/active"
          else
            echo target >"$MOCK_ROOT/active"
            [[ "$SCENARIO" != startup-failure ]] || exit 1
          fi
        else exit 91; fi ;;
      *) exit 90 ;;
    esac ;;
  *) exit 99 ;;
esac
MOCK
chmod +x "$fixture/bin/mock"
for tool in git docker curl sleep flock; do cp "$fixture/bin/mock" "$fixture/bin/$tool"; done
cp "$fixture/bin/mock" "$JAVA_HOME/bin/java"
cp "$fixture/bin/mock" "$DEPLOY_DIR/mvnw"
export PATH="$fixture/bin:$PATH"

case "$SCENARIO" in
  invalid-sha) DEPLOY_SHA='main; echo unsafe' ;;
  missing-sha) unset DEPLOY_SHA ;;
  missing-secret) unset JWT_SECRET ;;
  weak-secret) JWT_SECRET=short ;;
  first-success|first-health-failure) rm "$DEPLOY_DIR/.deploy/current"* ;;
  legacy-success) rm "$DEPLOY_DIR/.deploy/current-compose.yml" ;;
esac

result=0
bash "$script" >"$fixture/output" 2>&1 || result=$?
cat "$fixture/output"
cat "$TRACE"
check() { if ! "$@"; then echo "Assertion failed: $*" >&2; exit 1; fi; }
check_not() { if "$@"; then echo "Unexpected match: $*" >&2; exit 1; fi; }
# Never print any test secret, including secret-bearing container diagnostics.
for secret in 'new-test-secret-only-012345678901234567890' "$OLD_SECRET" "$DB_PASSWORD"; do
  check_not grep -Fq "$secret" "$fixture/output"
  check_not grep -Fq "$secret" "$TRACE"
done
check_not grep -Eq 'compose .* (down|stop|rm)|volume (rm|prune)|system prune|reset --hard|up .* app db' "$TRACE"

case "$SCENARIO" in
  success|first-success|legacy-success|health-retry)
    check test "$result" -eq 0
    check test "$(cat "$DEPLOY_DIR/.deploy/current")" = "$DEPLOY_SHA"
    check test "$(cat "$DEPLOY_DIR/.deploy/current-image")" = "$NEW_IMAGE"
    check test "$(cat "$MOCK_ROOT/active")" = target
    check test "$(grep -c ' up ' "$TRACE")" -eq 1
    if [[ "$SCENARIO" != first-success ]]; then
      check test "$(cat "$DEPLOY_DIR/.deploy/previous-image")" = "$OLD_IMAGE"
    fi
    # Build / configuration / HEAD check must precede the first app mutation.
    check grep -Fq "git checkout --detach $DEPLOY_SHA" "$TRACE"
    check grep -Fq "git cat-file -e $DEPLOY_SHA^{commit}" "$TRACE"
    check grep -Fq "docker build --label org.opencontainers.image.revision=$DEPLOY_SHA -t inventory-management-system-app:$DEPLOY_SHA ." "$TRACE"
    build_line=$(grep -n 'docker build ' "$TRACE" | cut -d: -f1)
    config_line=$(grep -n 'docker-compose.yml config' "$TRACE" | tail -1 | cut -d: -f1)
    up_line=$(grep -n ' up ' "$TRACE" | cut -d: -f1)
    check test "$build_line" -lt "$up_line"
    check test "$config_line" -lt "$build_line"
    check grep -Fq "$NEW_IMAGE" "$DEPLOY_DIR/.deploy/current-image"
    ;;
  health-failure|startup-failure|rollback-start-failure|rollback-health-failure)
    check test "$result" -ne 0
    check test "$(cat "$DEPLOY_DIR/.deploy/current")" = "$OLD_SHA"
    check test "$(cat "$DEPLOY_DIR/.deploy/current-image")" = "$OLD_IMAGE"
    check test "$(grep -c ' up ' "$TRACE")" -eq 2
    check grep -Fq 'previous-image.yml up ' "$TRACE"
    check grep -Fq 'docker logs --tail 100 inventory-app' "$TRACE"
    snapshot=$(find "$DEPLOY_DIR/.deploy" -type d -name 'pending.*')
    check test -f "$snapshot/previous-compose.yml"
    check test "$(cat "$snapshot/previous-image")" = "$OLD_IMAGE"
    if [[ "$SCENARIO" == rollback-start-failure || "$SCENARIO" == rollback-health-failure ]]; then
      check grep -Fq 'App rollback failed' "$fixture/output"
    else
      check test "$(cat "$MOCK_ROOT/active")" = previous
      check grep -Fq 'Previous app is healthy' "$fixture/output"
    fi
    if [[ "$SCENARIO" == health-failure ]]; then
      check test "$(grep -c '^curl ' "$TRACE")" -eq 31
    fi ;;
  first-health-failure)
    check test "$result" -ne 0
    check test ! -f "$DEPLOY_DIR/.deploy/current"
    check test "$(grep -c ' up ' "$TRACE")" -eq 1
    check grep -Fq 'no previous app image' "$fixture/output" ;;
  *)
    check test "$result" -ne 0
    check_not grep -Fq ' up ' "$TRACE"
    check test "$(cat "$DEPLOY_DIR/.deploy/current")" = "$OLD_SHA"
    check test "$(cat "$DEPLOY_DIR/.deploy/current-image")" = "$OLD_IMAGE" ;;
esac

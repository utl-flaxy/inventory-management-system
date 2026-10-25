#!/usr/bin/env bash
# Issue #157: deployment ordering against a REAL disposable Git history (bare origin + deploy clone).
# Only docker/curl/flock/sleep are mocks. No network, credentials, SSH, AWS or real Docker are used.
# Usage: deploy-order-test.sh <deploy.sh> <scenario>
set -euo pipefail
script=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
scenario="$2"
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
export MOCK_ROOT="$fixture/mock" TRACE="$fixture/trace"
mkdir -p "$MOCK_ROOT/images" "$fixture/bin"
: >"$TRACE"

# Hermetic Git: no user/system config, hooks, signing or credential helpers.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid
origin="$fixture/origin.git"
work="$fixture/author"
export DEPLOY_DIR="$fixture/deploy"
git init -q --bare -b main "$origin"
git init -q -b main "$work"
git -C "$work" remote add origin "$origin"
printf '#!/bin/sh\n' >"$work/mvnw"
: >"$work/Dockerfile"
printf 'services: {app: {image: local}}\n' >"$work/docker-compose.yml"
commit() { # commit <label>; prints the new SHA
  printf '%s\n' "$1" >"$work/revision.txt"
  git -C "$work" add -A && git -C "$work" commit -q -m "$1" && git -C "$work" rev-parse HEAD
}
publish() { git -C "$work" push -q --force origin "HEAD:refs/heads/$1"; }
A=$(commit A); B=$(commit B); C=$(commit C)
git -C "$work" checkout -q -b side "$A"; X=$(commit X); publish side
git -C "$work" checkout -q main
publish main
git clone -q "$origin" "$DEPLOY_DIR"

cat >"$fixture/bin/mock" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
tool=$(basename "$0")
printf '%s %s\n' "$tool" "$*" >>"$TRACE"
image_id() { printf 'sha256:%s000000000000000000000000' "$1"; }
image_sha() { # resolve an image id or built tag to its revision label file
  local ref="$1"
  if [[ "$ref" == sha256:* ]]; then cat "$MOCK_ROOT/images/${ref#sha256:}" 2>/dev/null || return 1
  elif [[ "$ref" == inventory-management-system-app:* && -f "$MOCK_ROOT/images/${ref##*:}000000000000000000000000" ]]; then echo "${ref##*:}"
  else return 1; fi
}
case "$tool" in
  flock|sleep) : ;;
  curl)
    output=''
    while (( $# )); do [[ "$1" != --output ]] || { output="$2"; shift; }; shift; done
    printf '{"status":"UP"}' >"$output"; echo 200 ;;
  docker)
    case "$1" in
      info|logs) : ;;
      build)
        sha=''
        for arg in "$@"; do [[ "$arg" != org.opencontainers.image.revision=* ]] || sha=${arg#*=}; done
        id=$(image_id "$sha"); printf '%s\n' "$sha" >"$MOCK_ROOT/images/${id#sha256:}"
        # Simulates a manual change to the running app while the target image builds.
        [[ ! -f "$MOCK_ROOT/mutate-on-build" ]] || mv "$MOCK_ROOT/mutate-on-build" "$MOCK_ROOT/running" ;;
      inspect)
        if [[ "$2" == inventory-db ]]; then
          [[ "$*" == *State.Running* ]] && echo true || echo inventory
        elif [[ "$2" == inventory-app ]]; then
          [[ -f "$MOCK_ROOT/running" ]] || exit 1
          if [[ "$*" == *'{{.Image}}'* ]]; then cat "$MOCK_ROOT/running"
          elif [[ "$*" == *Config.Env* ]]; then printf 'JWT_SECRET=old-order-test-secret-0123456789abcdef\0'
          else echo inventory; fi
        else exit 94; fi ;;
      image)
        ref="$3"; sha=$(image_sha "$ref") || exit 1
        if [[ $# -eq 3 ]]; then :
        elif [[ "$*" == *revision* ]]; then [[ -n "$sha" ]] && echo "$sha" || echo '<no value>'
        elif [[ "$*" == *health-path* ]]; then echo /readyz
        elif [[ "$*" == *'{{.Id}}'* ]]; then image_id "$sha"
        else exit 93; fi ;;
      compose)
        if [[ "$*" == *' config' ]]; then printf 'services: {app: {image: target}}\n'
        elif [[ "$*" == *' up '* ]]; then
          all="$*"; file="${all##*-f }"; file="${file%% *}" # the last -f is the pinned image override
          sed -n 's/^ *image: *//p' "$file" >"$MOCK_ROOT/running"
        else exit 91; fi ;;
      *) exit 90 ;;
    esac ;;
  *) exit 99 ;;
esac
MOCK
chmod +x "$fixture/bin/mock"
for tool in docker curl sleep flock; do cp "$fixture/bin/mock" "$fixture/bin/$tool"; done
export PATH="$fixture/bin:$PATH"
export JWT_SECRET='order-test-secret-only-0123456789abcdef' DB_PASSWORD='order-test-db-password'
export DB_ROOT_PASSWORD='order-test-root-password' DB_NAME=inventory DB_USERNAME=inventory_app APP_MEMORY_LIMIT=1g

last_output="$fixture/last-output"
deploy() { # deploy <sha>; returns the script exit status
  : >"$TRACE"
  local status=0
  DEPLOY_SHA="$1" bash "$script" >"$last_output" 2>&1 || status=$?
  { echo "--- deploy $1 (exit $status)"; cat "$last_output"; } >>"$fixture/log"
  return "$status"
}
running_sha() { [[ -f "$MOCK_ROOT/running" ]] && cat "$MOCK_ROOT/images/$(cut -c8- "$MOCK_ROOT/running")" || echo none; }
marker() { [[ -f "$DEPLOY_DIR/.deploy/current" ]] && cat "$DEPLOY_DIR/.deploy/current" || echo none; }
check() { if ! "$@"; then echo "Assertion failed: $*" >&2; cat "$fixture/log" >&2; echo "running=$(label "$(running_sha)") marker=$(label "$(marker)")" >&2; exit 1; fi; }
label() { case "$1" in "$A") echo A;; "$B") echo B;; "$C") echo C;; "$X") echo X;; none) echo none;; *) echo "$1";; esac; }
expect_deployed() { # expect_deployed <sha>
  check test "$(label "$(running_sha)")" = "$(label "$1")"
  check test "$(label "$(marker)")" = "$(label "$1")"
  check grep -Fxq "DEPLOY_RESULT=DEPLOYED target=$1" "$last_output"
}
expect_unchanged() { # expect_unchanged <running sha or none>: no build, no cutover, markers untouched
  check test "$(label "$(running_sha)")" = "$(label "$1")"
  check test "$(label "$(marker)")" = "$(label "$1")"
  if grep -Eq '^docker (build|compose .* up )' "$TRACE"; then check false; fi
}
expect_skipped() { # expect_skipped <reason> <running sha>
  check grep -Fxq "DEPLOY_RESULT=SKIPPED reason=$1 target=$DEPLOY_TARGET running=$2" "$last_output"
  check grep -Fq 'No deployment was performed' "$last_output"
  expect_unchanged "$2"
}
expect_refused() { # expect_refused <message fragment> <running sha or none>
  check grep -Fq "$1" "$last_output"
  check grep -Fxq 'DEPLOY_RESULT=FAILED' "$last_output"
  expect_unchanged "$2"
}
seed_running() { # simulate an app that was deployed earlier (as the old script would leave it)
  check deploy "$1"
}

case "$scenario" in
  ci-completion-reversed) # push A then B; CI(B) succeeds first, CI(A) completes later
    seed_running "$B"
    DEPLOY_TARGET="$A"; status=0; deploy "$A" || status=$?
    check test "$status" -eq 0
    expect_skipped stale "$B" ;;
  first-deploy)
    check deploy "$A"; expect_deployed "$A" ;;
  same-sha)
    seed_running "$A"
    DEPLOY_TARGET="$A"; check deploy "$A"
    expect_skipped already-running "$A" ;;
  forward)
    seed_running "$A"
    check deploy "$B"; expect_deployed "$B" ;;
  forward-past-intermediate-then-late-intermediate) # A running, C succeeds, then late CI for B
    seed_running "$A"
    check deploy "$C"; expect_deployed "$C"
    DEPLOY_TARGET="$B"; check deploy "$B"
    expect_skipped stale "$C" ;;
  other-branch)
    seed_running "$A"
    if deploy "$X"; then check false; fi
    expect_refused 'not on origin/main' "$A" ;;
  rewritten-main) # B deployed, then main force-pushed to A + B'; B' is on main but not a descendant of B
    seed_running "$B"
    git -C "$work" checkout -q -b rewritten "$A"; B2=$(commit B-rewritten); publish main
    if deploy "$B2"; then check false; fi
    expect_refused 'neither the running revision nor a descendant' "$B" ;;
  missing-marker)
    seed_running "$A"; rm "$DEPLOY_DIR/.deploy/current"
    if deploy "$B"; then check false; fi
    check grep -Fq 'no deployed revision marker' "$last_output"
    check test "$(label "$(running_sha)")" = A ;;
  marker-mismatch)
    seed_running "$A"; printf '%s\n' "$B" >"$DEPLOY_DIR/.deploy/current"
    if deploy "$C"; then check false; fi
    check grep -Fq 'does not match the running image label' "$last_output"
    check test "$(label "$(running_sha)")" = A
    check test "$(label "$(marker)")" = B ;;
  malformed-marker)
    seed_running "$A"; printf 'main\n' >"$DEPLOY_DIR/.deploy/current"
    if deploy "$B"; then check false; fi
    check grep -Fq 'marker is malformed' "$last_output"
    check test "$(label "$(running_sha)")" = A ;;
  unlabeled-running-image)
    seed_running "$A"; : >"$MOCK_ROOT/images/$(cut -c8- "$MOCK_ROOT/running")"
    if deploy "$B"; then check false; fi
    check grep -Fq 'no verifiable revision label' "$last_output"
    check test "$(marker)" = "$A" ;;
  unknown-running-commit)
    seed_running "$A"
    ghost=0123456789abcdef0123456789abcdef01234567
    printf '%s\n' "$ghost" >"$MOCK_ROOT/images/$(cut -c8- "$MOCK_ROOT/running")"
    printf '%s\n' "$ghost" >"$DEPLOY_DIR/.deploy/current"
    if deploy "$B"; then check false; fi
    check grep -Fq 'not a known commit' "$last_output"
    check test "$(marker)" = "$ghost" ;;
  running-changed-during-build) # the ordering decision is rechecked right before cutover
    seed_running "$A"
    check deploy "$C"; expect_deployed "$C"
    printf 'sha256:%s000000000000000000000000\n' "$A" >"$MOCK_ROOT/mutate-on-build" # someone restarts A
    git -C "$work" checkout -q main; D=$(commit D); publish main
    if deploy "$D"; then check false; fi
    check grep -Fq 'Running app changed during deployment preparation' "$last_output"
    check grep -Fxq 'DEPLOY_RESULT=FAILED' "$last_output"
    if grep -Eq '^docker compose .* up ' "$TRACE"; then check false; fi
    check test "$(label "$(running_sha)")" = A
    check test "$(label "$(marker)")" = C ;;
  marker-without-container)
    seed_running "$A"; rm "$MOCK_ROOT/running"
    if deploy "$B"; then check false; fi
    check grep -Fq 'no app container exists' "$last_output"
    check test "$(marker)" = "$A" ;;
  ancestry-git-error) # the stale check hits a Git error (128) although the reverse check would succeed
    seed_running "$A"
    # Pass-through wrapper: only the first `merge-base --is-ancestor B A` fails; all other calls are real Git.
    export REAL_GIT GIT_FAULT_ARGS="$B $A"
    REAL_GIT=$(command -v git)
    : >"$MOCK_ROOT/git-fault"
    cat >"$fixture/bin/git" <<'GIT'
#!/usr/bin/env bash
printf 'git %s\n' "$*" >>"$TRACE"
if [[ "$*" == "merge-base --is-ancestor $GIT_FAULT_ARGS" && -f "$MOCK_ROOT/git-fault" ]]; then
  rm "$MOCK_ROOT/git-fault"; echo "fatal: injected repository error" >&2; exit 128
fi
exec "$REAL_GIT" "$@"
GIT
    chmod +x "$fixture/bin/git"
    check "$REAL_GIT" -C "$DEPLOY_DIR" merge-base --is-ancestor "$A" "$B" # the reverse check alone would allow B
    if deploy "$B"; then check false; fi
    check test ! -e "$MOCK_ROOT/git-fault" # the fault was actually injected
    expect_refused 'Git ancestry check failed (exit 128)' "$A"
    if grep -Eq '^git (checkout|merge-base --is-ancestor '"$A $B"')' "$TRACE"; then check false; fi ;;
  *) echo "unknown scenario $scenario" >&2; exit 2 ;;
esac
cat "$fixture/log"
echo "scenario=$scenario running=$(label "$(running_sha)") marker=$(label "$(marker)")"

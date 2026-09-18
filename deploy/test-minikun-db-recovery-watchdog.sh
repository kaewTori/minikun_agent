#!/bin/bash

set -Eeuo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
watchdog="$script_dir/minikun-db-recovery-watchdog.sh"
fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/minikun-db-recovery-test.XXXXXX")
fake_bin="$fixture_dir/bin"
state_dir="$fixture_dir/state"
mkdir -p "$fake_bin" "$state_dir"

printf '%s\n' '#!/bin/bash' \
  'if [ "${FAKE_MOUNTED:-yes}" = yes ]; then' \
  '  printf "%s\\n" "/dev/disk-test on /Volumes/minikun_data (apfs, local)"' \
  'fi' >"$fake_bin/mount"
printf '%s\n' '#!/bin/bash' \
  '[ -f "$FAKE_ROOT/ready" ]' >"$fake_bin/pg_isready"
printf '%s\n' '#!/bin/bash' \
  'case "${1:-}" in' \
  '  status) printf "%s\\n" "colima is running" ;;' \
  '  restart|start) printf "%s\\n" "${1}" >>"$FAKE_ROOT/colima.calls"; touch "$FAKE_ROOT/ready" ;;' \
  'esac' >"$fake_bin/colima"
printf '%s\n' '#!/bin/bash' \
  'printf "%s\\n" "$*" >>"$FAKE_ROOT/launchctl.calls"' >"$fake_bin/launchctl"
printf '%s\n' '#!/bin/bash' \
  'printf "%s\\n" "{\"status\":\"UP\"}"' >"$fake_bin/curl"
chmod 700 "$fake_bin"/*

run_watchdog() {
  FAKE_ROOT="$fixture_dir" \
  FAKE_MOUNTED=yes \
  MINIKUN_DB_RECOVERY_STATE_DIR="$state_dir" \
  MINIKUN_MOUNT_BIN="$fake_bin/mount" \
  MINIKUN_PG_ISREADY_BIN="$fake_bin/pg_isready" \
  MINIKUN_COLIMA_BIN="$fake_bin/colima" \
  MINIKUN_CURL_BIN="$fake_bin/curl" \
  MINIKUN_LAUNCHCTL_BIN="$fake_bin/launchctl" \
  MINIKUN_DB_RECOVERY_WAIT_ATTEMPTS=1 \
  MINIKUN_DB_RECOVERY_WAIT_SECONDS=0 \
  /bin/bash "$watchdog"
}

run_watchdog
run_watchdog
run_watchdog
run_watchdog

restart_count=$(grep -c '^restart$' "$fixture_dir/colima.calls" 2>/dev/null || true)
[ "$restart_count" -eq 1 ]

empty_state="$fixture_dir/empty-state"
mkdir -p "$empty_state"
FAKE_ROOT="$fixture_dir" \
FAKE_MOUNTED=no \
MINIKUN_DB_RECOVERY_STATE_DIR="$empty_state" \
MINIKUN_MOUNT_BIN="$fake_bin/mount" \
MINIKUN_PG_ISREADY_BIN="$fake_bin/pg_isready" \
MINIKUN_COLIMA_BIN="$fake_bin/colima" \
MINIKUN_CURL_BIN="$fake_bin/curl" \
MINIKUN_LAUNCHCTL_BIN="$fake_bin/launchctl" \
/bin/bash "$watchdog"

volume_restart_count=$(grep -c '^restart$' "$fixture_dir/colima.calls" 2>/dev/null || true)
[ "$volume_restart_count" -eq 1 ]

printf '%s\n' "database recovery watchdog test passed (fixtures: $fixture_dir)"

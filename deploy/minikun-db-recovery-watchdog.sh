#!/bin/bash

set -Eeuo pipefail

# Runs from an internal-disk LaunchAgent. It must remain usable while the
# external PostgreSQL volume is unavailable.
VOLUME_PATH=${MINIKUN_DATABASE_VOLUME_PATH:-/Volumes/minikun_data}
COLIMA=${MINIKUN_COLIMA_BIN:-/opt/homebrew/bin/colima}
PG_ISREADY=${MINIKUN_PG_ISREADY_BIN:-/opt/homebrew/opt/libpq/bin/pg_isready}
CURL=${MINIKUN_CURL_BIN:-/usr/bin/curl}
LAUNCHCTL=${MINIKUN_LAUNCHCTL_BIN:-/bin/launchctl}
MOUNT=${MINIKUN_MOUNT_BIN:-/sbin/mount}
AGENT_SERVICE=${MINIKUN_AGENT_SERVICE:-gui/$(id -u)/com.minikun.agent}
STATE_DIR=${MINIKUN_DB_RECOVERY_STATE_DIR:-"$HOME/Library/Application Support/Minikun/state"}
STATE_FILE="$STATE_DIR/database-recovery.state"
LOCK_DIR="$STATE_DIR/database-recovery.lock"
LOG_FILE="$STATE_DIR/database-recovery.log"
DB_HOST=${MINIKUN_DATABASE_HOST:-127.0.0.1}
DB_PORT=${MINIKUN_DATABASE_PORT:-5432}
DB_NAME=${MINIKUN_DATABASE_NAME:-minikun}
DB_USER=${MINIKUN_DATABASE_USER:-minikun}
APP_HEALTH_URL=${MINIKUN_AGENT_HEALTH_URL:-http://127.0.0.1:8080/actuator/health}
APP_PERSISTENCE_HEALTH_URL=${MINIKUN_AGENT_PERSISTENCE_HEALTH_URL:-http://127.0.0.1:8080/actuator/health/conversationPersistence}
FAILURE_THRESHOLD=${MINIKUN_DB_RECOVERY_FAILURE_THRESHOLD:-3}
COOLDOWN_SECONDS=${MINIKUN_DB_RECOVERY_COOLDOWN_SECONDS:-900}
AGENT_COOLDOWN_SECONDS=${MINIKUN_AGENT_RECOVERY_COOLDOWN_SECONDS:-300}
WAIT_ATTEMPTS=${MINIKUN_DB_RECOVERY_WAIT_ATTEMPTS:-12}
WAIT_SECONDS=${MINIKUN_DB_RECOVERY_WAIT_SECONDS:-5}

mkdir -p "$STATE_DIR"

log() {
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >>"$LOG_FILE"
}

release_lock() {
  rmdir "$LOCK_DIR" 2>/dev/null || true
}

acquire_lock() {
  if [ -d "$LOCK_DIR" ]; then
    old_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
      exit 0
    fi
    rm -rf "$LOCK_DIR"
  fi
  mkdir "$LOCK_DIR"
  printf '%s\n' "$$" >"$LOCK_DIR/pid"
  trap release_lock EXIT HUP INT TERM
}

mounted() {
  "$MOUNT" | /usr/bin/grep -F " on $VOLUME_PATH (" >/dev/null 2>&1
}

database_ready() {
  "$PG_ISREADY" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t 2 >/dev/null 2>&1
}

agent_database_offline() {
  health=$($CURL --fail --silent --show-error --max-time 3 "$APP_PERSISTENCE_HEALTH_URL" 2>/dev/null || true)
  case "$health" in
    *database_disabled*) return 0 ;;
    *) return 1 ;;
  esac
}

agent_ready() {
  $CURL --fail --silent --show-error --max-time 3 "$APP_HEALTH_URL" >/dev/null 2>&1
}

recover_agent_if_needed() {
  log 'database is ready but Mini-kun is in database-offline mode; restarting agent'
  "$LAUNCHCTL" kickstart -k "$AGENT_SERVICE" >/dev/null 2>&1 || {
    log 'agent restart failed'
    return 1
  }
  attempt=1
  while [ "$attempt" -le "$WAIT_ATTEMPTS" ]; do
    if agent_ready && ! agent_database_offline; then
      log 'agent recovered persistent database mode'
      return 0
    fi
    sleep "$WAIT_SECONDS"
    attempt=$((attempt + 1))
  done
  log 'agent did not leave database-offline mode after restart'
  return 1
}

read_state() {
  failure_count=0
  last_restart=0
  last_agent_restart=0
  if [ -f "$STATE_FILE" ]; then
    read -r failure_count last_restart last_agent_restart <"$STATE_FILE" || true
  fi
  case "$failure_count" in ''|*[!0-9]*) failure_count=0 ;; esac
  case "$last_restart" in ''|*[!0-9]*) last_restart=0 ;; esac
  case "$last_agent_restart" in ''|*[!0-9]*) last_agent_restart=0 ;; esac
}

write_state() {
  printf '%s %s %s\n' "$1" "$2" "$3" >"$STATE_FILE"
}

recover_agent_if_needed_once() {
  if ! agent_database_offline; then
    last_agent_restart=0
    write_state "$failure_count" "$last_restart" "$last_agent_restart"
    return 0
  fi
  now=$(date +%s)
  if [ "$last_agent_restart" -gt 0 ] && [ $((now - last_agent_restart)) -lt "$AGENT_COOLDOWN_SECONDS" ]; then
    return 0
  fi
  last_agent_restart=$now
  write_state "$failure_count" "$last_restart" "$last_agent_restart"
  recover_agent_if_needed || true
  if ! agent_database_offline; then
    last_agent_restart=0
    write_state "$failure_count" "$last_restart" "$last_agent_restart"
  fi
}

wait_for_database() {
  attempt=1
  while [ "$attempt" -le "$WAIT_ATTEMPTS" ]; do
    if database_ready; then
      return 0
    fi
    sleep "$WAIT_SECONDS"
    attempt=$((attempt + 1))
  done
  return 1
}

restart_colima() {
  colima_status=$($COLIMA status 2>&1 || true)
  case "$colima_status" in
    *[Rr]unning*)
      log 'PostgreSQL unavailable; restarting Colima'
      "$COLIMA" restart >>"$LOG_FILE" 2>&1
      ;;
    *)
      log 'PostgreSQL unavailable; starting stopped Colima'
      "$COLIMA" start >>"$LOG_FILE" 2>&1
      ;;
  esac
}

main() {
  acquire_lock
  read_state

  if ! mounted; then
    if [ "$failure_count" -ne 0 ]; then
      log "database volume is not mounted: $VOLUME_PATH"
    fi
    write_state 0 0 0
    return 0
  fi

  if database_ready; then
    if [ "$failure_count" -ne 0 ] || [ "$last_restart" -ne 0 ]; then
      log 'PostgreSQL is reachable again'
    fi
    recover_agent_if_needed_once
    return 0
  fi

  failure_count=$((failure_count + 1))
  now=$(date +%s)
  write_state "$failure_count" "$last_restart" "$last_agent_restart"
  if [ "$failure_count" -lt "$FAILURE_THRESHOLD" ]; then
    return 0
  fi
  if [ "$last_restart" -gt 0 ] && [ $((now - last_restart)) -lt "$COOLDOWN_SECONDS" ]; then
    return 0
  fi

  if ! restart_colima; then
    log 'Colima recovery command failed'
    write_state "$failure_count" "$now" "$last_agent_restart"
    return 0
  fi
  write_state 0 "$now" "$last_agent_restart"
  if wait_for_database; then
    log 'PostgreSQL recovered after Colima action'
    recover_agent_if_needed_once
  else
    log 'PostgreSQL did not recover after Colima action'
  fi
}

main "$@"

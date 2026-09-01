#!/bin/sh

set -eu

script_root="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
label="com.minikun.sdxl"
launch_domain="gui/$(id -u)"
launch_service="$launch_domain/$label"
local_root="$HOME/Library/Application Support/Minikun"
local_script="$local_root/java/script"
installed_plist="$HOME/Library/LaunchAgents/$label.plist"
port="${MINIKUN_SDXL_PORT:-8002}"
ready_wait_seconds="${MINIKUN_SDXL_READY_WAIT_SECONDS:-1800}"

mkdir -p "$local_script" "$HOME/Library/LaunchAgents" "$HOME/Library/Logs/Minikun"
cp "$script_root/start-minikun-sdxl.sh" "$local_script/start-minikun-sdxl.sh"
cp "$script_root/sdxl-fault-supervisor.py" "$local_script/sdxl-fault-supervisor.py"
chmod 700 "$local_script/start-minikun-sdxl.sh"
chmod 700 "$local_script/sdxl-fault-supervisor.py"
cp "$script_root/$label.plist" "$installed_plist"
plutil -lint "$installed_plist" >/dev/null

launchctl bootout "$launch_service" 2>/dev/null || true

# Replace only a TinyGrad SDXL process that owns the configured port. Never
# terminate an unrelated listener merely because it happens to use the port.
listener_pid="$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
if [ -n "$listener_pid" ]; then
  listener_command="$(ps -p "$listener_pid" -ww -o command= 2>/dev/null || true)"
  case "$listener_command" in
    *examples/sdxl_use.py*|*examples/sdxl_server.py*)
      kill "$listener_pid"
      stop_attempt=0
      while kill -0 "$listener_pid" 2>/dev/null; do
        stop_attempt=$((stop_attempt + 1))
        if [ "$stop_attempt" -ge 30 ]; then
          echo "Timed out waiting for the previous SDXL process to stop" >&2
          exit 1
        fi
        sleep 1
      done
      ;;
    *)
      echo "Port $port is owned by an unrelated process: $listener_command" >&2
      exit 1
      ;;
  esac
fi

launchctl enable "$launch_service" 2>/dev/null || true
bootstrap_attempt=0
bootstrap_error=''
until bootstrap_error="$(launchctl bootstrap "$launch_domain" "$installed_plist" 2>&1)"; do
  if launchctl print "$launch_service" >/dev/null 2>&1; then
    break
  fi
  bootstrap_attempt=$((bootstrap_attempt + 1))
  if [ "$bootstrap_attempt" -ge 30 ]; then
    echo "Unable to bootstrap $label after 30 attempts" >&2
    if [ -n "$bootstrap_error" ]; then
      printf '%s\n' "$bootstrap_error" >&2
    fi
    exit 1
  fi
  sleep 2
done
launchctl kickstart "$launch_service"

attempt=0
until curl --fail --silent "http://127.0.0.1:$port/health"; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$ready_wait_seconds" ]; then
    echo "TinyGrad SDXL did not become healthy within $ready_wait_seconds seconds" >&2
    tail -n 80 "$HOME/Library/Logs/Minikun/sdxl.err.log" >&2 || true
    exit 1
  fi
  sleep 1
done
printf '\n'
echo "TinyGrad SDXL is ready on http://127.0.0.1:$port"

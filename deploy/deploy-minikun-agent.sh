#!/bin/sh

set -eu

script_root="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -f "$script_root/../pom.xml" ]; then
  app_root="$(CDPATH= cd -- "$script_root/.." && pwd)"
else
  app_root="$(CDPATH= cd -- "$script_root/../minikun_agent" && pwd)"
fi
workspace_root="$(CDPATH= cd -- "$app_root/../.." && pwd)"
local_root="$HOME/Library/Application Support/Minikun"
local_app="$local_root/java/minikun_agent"
local_script="$local_root/java/script"
label="com.minikun.agent"
plist="$app_root/deploy/$label.plist"

cd "$app_root"
./mvnw clean package

mkdir -p \
  "$local_app/target" \
  "$local_script" \
  "$local_root/config/minikun-agent/mcs" \
  "$local_root/logs" \
  "$HOME/Library/Logs/Minikun" \
  "$HOME/Library/LaunchAgents"

staged_jar="$local_app/target/.minikun_agent-1.0.0.jar.$$"
trap 'rm -f "$staged_jar"' EXIT HUP INT TERM
cp "$app_root/target/minikun_agent-1.0.0.jar" "$staged_jar"
mv "$staged_jar" "$local_app/target/minikun_agent-1.0.0.jar"
trap - EXIT HUP INT TERM
cp "$workspace_root/java/script/minikun-agent.sh" "$local_script/minikun-agent.sh"
chmod 700 "$local_script/minikun-agent.sh"
ditto "$workspace_root/config/minikun-agent/mcs" "$local_root/config/minikun-agent/mcs"

if [ -f "$workspace_root/.env" ]; then
  cp "$workspace_root/.env" "$local_root/.env"
  chmod 600 "$local_root/.env"
fi

cp "$plist" "$HOME/Library/LaunchAgents/$label.plist"
launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
sleep 1
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/$label.plist"
launchctl kickstart -k "gui/$(id -u)/$label"

attempt=0
until curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "Mini-kun did not become healthy within 30 seconds" >&2
    exit 1
  fi
  sleep 1
done
printf '\n'

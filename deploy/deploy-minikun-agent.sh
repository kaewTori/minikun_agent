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
local_voice="$local_root/python/voice"
label="com.minikun.agent"
plist="$app_root/deploy/$label.plist"

cd "$app_root"
./mvnw clean package

# Local installs keep the database credential in the launcher. Reuse only that
# explicit export when deploy is run from a clean shell, so migrations remain
# non-interactive without loading or executing the launcher itself.
if [ -z "${SPRING_DATASOURCE_PASSWORD:-}" ]; then
  source_launcher="$workspace_root/java/script/minikun-agent.sh"
  datasource_password_export="$(sed -n '/^export SPRING_DATASOURCE_PASSWORD=/p' "$source_launcher")"

  if [ -n "$datasource_password_export" ]; then
    eval "$datasource_password_export"
  fi
fi
if [ -z "${MINIKUN_SEARCH_TAVILY_API_KEY:-}" ]; then
  source_launcher="$workspace_root/java/script/minikun-agent.sh"
  tavily_api_key_export="$(sed -n '/^export MINIKUN_SEARCH_TAVILY_API_KEY=/p' "$source_launcher")"

  if [ -n "$tavily_api_key_export" ]; then
    eval "$tavily_api_key_export"
  fi
fi

"$app_root/deploy/migrate-database.sh"

mkdir -p \
  "$local_app/target" \
  "$local_script" \
  "$local_voice" \
  "$local_root/config/minikun-agent/mcs" \
  "$local_root/logs" \
  "$HOME/Documents/Minikun Knowledge" \
  "$HOME/Library/Logs/Minikun" \
  "$HOME/Library/LaunchAgents"

# Credentials are declared in the private source launcher below. Remove
# copies from previous runtime deployments so the launcher is the only
# investment/Crawl4AI secret source.
rm -f \
  "$local_root/config/crawl4ai.token" \
  "$local_root/config/investment/twelve-data.key" \
  "$local_root/config/investment/alpaca-key-id" \
  "$local_root/config/investment/alpaca-secret" \
  "$local_root/config/investment/sec-user-agent"
rmdir "$local_root/config/investment" 2>/dev/null || true

/bin/sh "$app_root/deploy/setup-local-https.sh"

staged_jar="$local_app/target/.minikun_agent-1.0.0.jar.$$"
trap 'rm -f "$staged_jar"' EXIT HUP INT TERM
cp "$app_root/target/minikun_agent-1.0.0.jar" "$staged_jar"
mv "$staged_jar" "$local_app/target/minikun_agent-1.0.0.jar"
trap - EXIT HUP INT TERM
source_launcher="$workspace_root/java/script/minikun-agent.sh"
# The shared launcher contains legacy environment loading. Deploy only its
# stable bootstrap, explicit runtime configuration, and the investment/Crawl4AI
# credential exports kept in that launcher. Preserve Tavily's explicit key
# export even though it appears immediately before the MCS_ROOT section.
staged_launcher="$local_script/.minikun-agent.sh.$$"
trap 'rm -f "$staged_launcher"' EXIT HUP INT TERM
{
  sed -n '1,7p' "$source_launcher"
  sed -n '/^export MINIKUN_SEARCH_TAVILY_API_KEY=/p' "$source_launcher"
  sed -n '/^export MCS_ROOT/p' "$source_launcher"
  sed -n '/^export MCS_ROOT/,$p' "$source_launcher" \
    | sed '1d;/^export MINIKUN_SEARCH_TAVILY_API_KEY=/d'
} > "$staged_launcher"
mv "$staged_launcher" "$local_script/minikun-agent.sh"
trap - EXIT HUP INT TERM
chmod 700 "$local_script/minikun-agent.sh"
browser_runtime="${MINIKUN_BROWSER_RUNTIME_ROOT:-$local_root/browser}"
if [ -x "$browser_runtime/venv/bin/python" ]; then
  cp "$app_root/browser/session.py" "$browser_runtime/session.py"
  chmod 600 "$browser_runtime/session.py"
fi
cp "$app_root/deploy/minikun-db-recovery-watchdog.sh" "$local_script/minikun-db-recovery-watchdog.sh"
chmod 700 "$local_script/minikun-db-recovery-watchdog.sh"
cp "$app_root/voice/whisper_transcribe.py" "$local_voice/whisper_transcribe.py"
chmod 700 "$local_voice/whisper_transcribe.py"
ditto "$workspace_root/config/minikun-agent/mcs" "$local_root/config/minikun-agent/mcs"
knowledge_readme="$HOME/Documents/Minikun Knowledge/README.md"
if [ ! -f "$knowledge_readme" ]; then
  cp "$app_root/deploy/knowledge/README.md" "$knowledge_readme"
elif cmp -s "$app_root/deploy/knowledge/README.legacy.md" "$knowledge_readme"; then
  # Migrate only the generated legacy onboarding document. Any user-edited
  # knowledge README remains untouched.
  cp "$knowledge_readme" "$knowledge_readme.legacy-backup"
  cp "$app_root/deploy/knowledge/README.md" "$knowledge_readme"
fi

installed_plist="$HOME/Library/LaunchAgents/$label.plist"
launch_domain="gui/$(id -u)"
launch_service="$launch_domain/$label"
cp "$plist" "$installed_plist"

# Some restricted shells allow bootout but deny bootstrap with the generic
# "Bootstrap failed: 5" error. Verify bootstrap access before stopping the
# running application so a failed deploy cannot leave Mini-kun offline.
preflight_label="$label.deploy-preflight.$$"
preflight_service="$launch_domain/$preflight_label"
preflight_tmp="$(mktemp "${TMPDIR:-/tmp}/minikun-launchctl-preflight.XXXXXX")"
preflight_plist="$preflight_tmp.plist"
mv "$preflight_tmp" "$preflight_plist"
cleanup_launchctl_preflight() {
  launchctl bootout "$preflight_service" 2>/dev/null || true
  rm -f "$preflight_plist"
}
trap cleanup_launchctl_preflight EXIT HUP INT TERM
{
  printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
  printf '%s\n' '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">'
  printf '%s\n' '<plist version="1.0"><dict>'
  printf '%s\n' '<key>Label</key>' "<string>$preflight_label</string>"
  printf '%s\n' '<key>ProgramArguments</key><array><string>/usr/bin/true</string></array>'
  printf '%s\n' '</dict></plist>'
} > "$preflight_plist"
preflight_error=''
if ! preflight_error="$(launchctl bootstrap "$launch_domain" "$preflight_plist" 2>&1)"; then
  echo "Cannot deploy $label because this shell cannot bootstrap LaunchAgents." >&2
  if [ -n "$preflight_error" ]; then
    printf '%s\n' "$preflight_error" >&2
  fi
  echo "Run this script directly from Terminal as the logged-in user; do not use sudo." >&2
  exit 1
fi
cleanup_launchctl_preflight
trap - EXIT HUP INT TERM

launchctl bootout "$launch_service" 2>/dev/null || true

# bootout may return before launchd has fully removed the job or before the
# application's listeners have closed. Starting the same label during this
# interval can fail with Bootstrap failed: 5 (Input/output error).
stop_attempt=0
while launchctl print "$launch_service" >/dev/null 2>&1 \
    || curl --silent --max-time 1 \
        http://127.0.0.1:8080/actuator/health/liveness >/dev/null 2>&1; do
  stop_attempt=$((stop_attempt + 1))
  if [ "$stop_attempt" -ge 30 ]; then
    echo "Timed out waiting for the previous $label process to stop" >&2
    exit 1
  fi
  sleep 1
done

launchctl enable "$launch_service" 2>/dev/null || true
bootstrap_attempt=0
bootstrap_error=''
until bootstrap_error="$(launchctl bootstrap "$launch_domain" "$installed_plist" 2>&1)"; do
  # Treat the service as loaded if bootstrap completed despite a non-zero exit.
  if launchctl print "$launch_service" >/dev/null 2>&1; then
    break
  fi
  bootstrap_attempt=$((bootstrap_attempt + 1))
  if [ "$bootstrap_attempt" -ge 30 ]; then
    echo "Unable to bootstrap $label after 30 attempts" >&2
    if [ -n "$bootstrap_error" ]; then
      printf '%s\n' "$bootstrap_error" >&2
    fi
    plutil -lint "$installed_plist" >&2 || true
    echo "Run this script directly from Terminal as the logged-in user; do not use sudo." >&2
    exit 1
  fi
  sleep 2
done

# RunAtLoad normally starts the job during bootstrap. kickstart without -k
# starts an idle job but does not terminate one that is already initializing.
launchctl kickstart "$launch_service"

attempt=0
until curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    echo "Mini-kun did not become healthy within 30 seconds" >&2
    exit 1
  fi
  sleep 1
done
printf '\n'

curl --fail --silent --show-error \
  --cacert "$local_root/tls/minikun-local-ca.pem" \
  https://127.0.0.1:8443/actuator/health/readiness
printf '\n'

recovery_label="com.minikun.database-recovery"
recovery_plist="$app_root/deploy/$recovery_label.plist"
installed_recovery_plist="$HOME/Library/LaunchAgents/$recovery_label.plist"
recovery_service="$launch_domain/$recovery_label"
cp "$recovery_plist" "$installed_recovery_plist"
launchctl bootout "$recovery_service" 2>/dev/null || true
recovery_attempt=0
recovery_error=''
until recovery_error="$(launchctl bootstrap "$launch_domain" "$installed_recovery_plist" 2>&1)"; do
  if launchctl print "$recovery_service" >/dev/null 2>&1; then
    break
  fi
  recovery_attempt=$((recovery_attempt + 1))
  if [ "$recovery_attempt" -ge 15 ]; then
    echo "Unable to bootstrap $recovery_label" >&2
    if [ -n "$recovery_error" ]; then
      printf '%s\n' "$recovery_error" >&2
    fi
    plutil -lint "$installed_recovery_plist" >&2 || true
    exit 1
  fi
  sleep 2
done
launchctl kickstart "$recovery_service"

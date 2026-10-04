#!/bin/sh
set -eu
app_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
runtime_root="${MINIKUN_BROWSER_RUNTIME_ROOT:-$HOME/Library/Application Support/Minikun/browser}"
mkdir -p "$runtime_root"
chmod 700 "$runtime_root"
python3 -m venv "$runtime_root/venv"
"$runtime_root/venv/bin/python" -m pip install 'playwright==1.58.0'
PLAYWRIGHT_BROWSERS_PATH="$runtime_root/browsers" "$runtime_root/venv/bin/python" -m playwright install chromium
cp "$app_root/browser/session.py" "$runtime_root/session.py"
chmod 600 "$runtime_root/session.py"
printf '%s\n' 'Browser runtime installed. Open Settings > Browser session after deploying Mini-kun.'

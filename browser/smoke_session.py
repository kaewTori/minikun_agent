#!/usr/bin/env python3
"""Optional live check: headed browser rendering and cookie persistence across restarts."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

runtime = Path(os.environ.get("MINIKUN_BROWSER_RUNTIME_ROOT", Path.home() / "Library/Application Support/Minikun/browser"))
script = Path(__file__).with_name("session.py").resolve()
environment = os.environ.copy()
environment["PLAYWRIGHT_BROWSERS_PATH"] = str(runtime / "browsers")


def read(profile, url):
    commands = "\n".join(json.dumps({"action": action, "url": url}) for action in ("open", "render")) + "\n"
    result = subprocess.run([str(runtime / "venv/bin/python"), str(script), profile], input=commands,
                            capture_output=True, text=True, timeout=50, env=environment, check=True)
    responses = [json.loads(line) for line in result.stdout.splitlines()]
    assert len(responses) == 2 and all(response.get("success") for response in responses), responses
    assert responses[-1].get("status_code") == 200, responses[-1].get("status_code")
    return responses[-1]["content"]


with tempfile.TemporaryDirectory(prefix="minikun-session-check-") as profile:
    assert "Example Domain" in read(profile, "https://example.com")
    assert "retained" in read(profile, "https://httpbin.org/cookies/set?minikun_session_smoke=retained")
    assert "retained" in read(profile, "https://httpbin.org/cookies")
print("Headed render and session-cookie persistence passed")

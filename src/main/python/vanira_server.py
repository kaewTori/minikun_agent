#!/usr/bin/env python3
"""Small local HTTP bridge that keeps the VaniraTTS ONNX session warm."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from vaniratts import VaniraTTS

MAX_BODY_BYTES = 64 * 1024
MAX_TEXT_CHARACTERS = 4000


class VaniraServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    server_version = "MinikunVanira/1.0"

    def do_GET(self) -> None:
        if self.path != "/health":
            self._json(404, {"error": "not found"})
            return
        state = self.server.state
        self._json(200, {"status": "ok", "model": str(state.model_dir), "speaker": 3})

    def do_POST(self) -> None:
        if self.path != "/tts":
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY_BYTES:
                raise ValueError("request body is too large or empty")
            request = json.loads(self.rfile.read(length))
            if not isinstance(request, dict):
                raise ValueError("request must be an object")
            text = request.get("text")
            if not isinstance(text, str) or not text.strip():
                raise ValueError("text must be non-empty")
            if len(text) > MAX_TEXT_CHARACTERS:
                raise ValueError("text is too long")
            speaker = int(request.get("speaker", 3))
            if speaker < 1 or speaker > 6:
                raise ValueError("speaker must be 1..6")
            speed = float(request.get("speed", 1.0))
            if not 0.5 <= speed <= 2.0:
                raise ValueError("speed must be between 0.5 and 2.0")
            self._synthesize(text, speaker, speed)
        except (json.JSONDecodeError, TypeError, ValueError) as exc:
            self._json(400, {"error": str(exc)})
        except Exception as exc:  # pragma: no cover - surfaced through the HTTP boundary
            print(f"[vanira] synthesis failed: {exc}", flush=True)
            self._json(500, {"error": "synthesis failed"})

    def _synthesize(self, text: str, speaker: int, speed: float) -> None:
        state = self.server.state
        fd, name = tempfile.mkstemp(prefix="minikun-vanira-", suffix=".wav")
        os.close(fd)
        output = Path(name)
        try:
            # ponytail: serialize inference for stable memory/latency; add a worker queue if concurrency matters.
            with state.lock:
                state.tts.infer(text, speaker=speaker, speed=speed, volume=1.0, output=str(output))
            data = output.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        finally:
            output.unlink(missing_ok=True)

    def _json(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: object) -> None:
        print(f"[vanira] {self.address_string()} - {format % args}", flush=True)


class State:
    def __init__(self, tts: VaniraTTS, model_dir: Path) -> None:
        self.tts = tts
        self.model_dir = model_dir
        self.lock = threading.Lock()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18022)
    parser.add_argument("--model-dir", required=True, type=Path)
    args = parser.parse_args()
    model_dir = args.model_dir.expanduser().resolve()
    if not (model_dir / "tts.onnx").is_file() or not (model_dir / "vocab.json").is_file():
        raise SystemExit(f"VaniraTTS model files are missing under {model_dir}")
    print(f"[vanira] loading model from {model_dir}", flush=True)
    tts = VaniraTTS(local_path=str(model_dir), device="cpu")
    server = VaniraServer((args.host, args.port), Handler)
    server.state = State(tts, model_dir)
    print(f"[vanira] ready on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Small local HTTP bridge for warm Thai/English text-to-speech."""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import soundfile as sf
from kokoro import KPipeline
from kokoro.model import KModel
from vaniratts import VaniraTTS

MAX_BODY_BYTES = 64 * 1024
MAX_TEXT_CHARACTERS = 4000
TARGET_SAMPLE_RATE = 24000
SILENCE_SECONDS = 0.04
ENGLISH_WORD = re.compile(r"[A-Za-z]+(?:['’\-][A-Za-z]+)*")
THAI_CHAR = re.compile(r"[\u0E00-\u0E7F]")
BOUNDARY_CHARS = set(" \t\r\n,.;:!?\"'()[]{}…—–-")


def split_text(text: str) -> list[tuple[str, str]]:
    """Keep short English tokens in Thai speech; route only useful English phrases."""
    text = text.strip()
    if not text:
        return []
    words = list(ENGLISH_WORD.finditer(text))
    if not words:
        return [("th", text)]
    if not THAI_CHAR.search(text):
        return [("en", text)]

    groups: list[list[re.Match[str]]] = []
    current = [words[0]]
    for word in words[1:]:
        gap = text[current[-1].end():word.start()]
        if len(gap) <= 12 and not THAI_CHAR.search(gap) and all(char in BOUNDARY_CHARS for char in gap):
            current.append(word)
        else:
            groups.append(current)
            current = [word]
    groups.append(current)

    selected = [group for group in groups
                if len(group) >= 4 or len(text[group[0].start():group[-1].end()].strip()) >= 24]
    if not selected:
        return [("th", text)]

    parts: list[tuple[str, str]] = []
    cursor = 0
    for group in selected:
        start = group[0].start()
        end = group[-1].end()
        while end < len(text) and text[end] in BOUNDARY_CHARS:
            end += 1
        prefix = text[cursor:start].strip()
        english = text[start:end].strip()
        if prefix:
            parts.append(("th", prefix))
        if english:
            parts.append(("en", english))
        cursor = end
    suffix = text[cursor:].strip()
    if suffix:
        parts.append(("th", suffix))
    return parts


def _mono(audio: np.ndarray) -> np.ndarray:
    audio = np.asarray(audio, dtype=np.float32)
    return audio.mean(axis=1) if audio.ndim > 1 else audio.reshape(-1)


def _resample(audio: np.ndarray, source_rate: int, target_rate: int) -> np.ndarray:
    if source_rate == target_rate or audio.size < 2:
        return audio
    target_size = max(1, round(audio.size * target_rate / source_rate))
    # ponytail: linear resampling keeps this self-contained; use soxr if artifacts matter.
    return np.interp(
        np.linspace(0, audio.size - 1, target_size),
        np.arange(audio.size),
        audio,
    ).astype(np.float32)


def _encode_wav(audio: np.ndarray) -> bytes:
    output = io.BytesIO()
    sf.write(output, np.asarray(audio, dtype=np.float32), TARGET_SAMPLE_RATE,
             format="WAV", subtype="PCM_16")
    return output.getvalue()


class TtsServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    server_version = "MinikunMixedTTS/1.0"

    def do_GET(self) -> None:
        if self.path != "/health":
            self._json(404, {"error": "not found"})
            return
        state = self.server.state
        self._json(200, {
            "status": "ok",
            "thai_model": str(state.model_dir),
            "english_model": str(state.kokoro_model_dir),
            "english_voice": str(state.kokoro_voice),
        })

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
            print(f"[tts] synthesis failed: {exc}", flush=True)
            self._json(500, {"error": "synthesis failed"})

    def _synthesize(self, text: str, speaker: int, speed: float) -> None:
        state = self.server.state
        # ponytail: serialize inference for stable memory/latency; add a worker queue if throughput matters.
        with state.lock:
            data, engines = state.synthesize(text, speaker, speed)
        print(f"[tts] engines={','.join(engines)} text_characters={len(text)} output_bytes={len(data)}",
              flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _json(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: object) -> None:
        print(f"[tts] {self.address_string()} - {format % args}", flush=True)


class State:
    def __init__(self, vanira: VaniraTTS, model_dir: Path, kokoro: KPipeline,
                 kokoro_model_dir: Path, kokoro_voice: Path) -> None:
        self.vanira = vanira
        self.model_dir = model_dir
        self.kokoro = kokoro
        self.kokoro_model_dir = kokoro_model_dir
        self.kokoro_voice = kokoro_voice
        self.lock = threading.Lock()

    def synthesize(self, text: str, speaker: int, speed: float) -> tuple[bytes, list[str]]:
        parts = split_text(text)
        if len(parts) == 1 and parts[0][0] == "th":
            return self._vanira_wave(parts[0][1], speaker, speed), ["vaniratts"]
        audio_parts: list[np.ndarray] = []
        engines: list[str] = []
        for kind, segment in parts:
            if kind == "en":
                audio, sample_rate = self._kokoro_audio(segment, speed)
                engines.append("kokoro")
            else:
                audio, sample_rate = self._vanira_audio(segment, speaker, speed)
                engines.append("vaniratts")
            audio = _resample(_mono(audio), sample_rate, TARGET_SAMPLE_RATE)
            if audio.size:
                if audio_parts:
                    audio_parts.append(np.zeros(round(TARGET_SAMPLE_RATE * SILENCE_SECONDS), dtype=np.float32))
                audio_parts.append(audio)
        if not audio_parts:
            raise RuntimeError("speech synthesis returned no audio")
        return _encode_wav(np.concatenate(audio_parts)), engines

    def _vanira_wave(self, text: str, speaker: int, speed: float) -> bytes:
        handle, name = tempfile.mkstemp(prefix="minikun-vanira-", suffix=".wav")
        os.close(handle)
        output = Path(name)
        output.unlink(missing_ok=True)
        try:
            self.vanira.infer(text, speaker=speaker, speed=speed, volume=1.0, output=str(output))
            return output.read_bytes()
        finally:
            output.unlink(missing_ok=True)

    def _vanira_audio(self, text: str, speaker: int, speed: float) -> tuple[np.ndarray, int]:
        handle, name = tempfile.mkstemp(prefix="minikun-vanira-", suffix=".wav")
        os.close(handle)
        Path(name).unlink(missing_ok=True)
        output = Path(name)
        try:
            self.vanira.infer(text, speaker=speaker, speed=speed, volume=1.0, output=str(output))
            return sf.read(output, dtype="float32", always_2d=False)
        finally:
            output.unlink(missing_ok=True)

    def _kokoro_audio(self, text: str, speed: float) -> tuple[np.ndarray, int]:
        chunks: list[np.ndarray] = []
        for result in self.kokoro(text, voice=str(self.kokoro_voice), speed=speed):
            if result.output is None:
                continue
            audio = result.output.audio
            if hasattr(audio, "detach"):
                audio = audio.detach().cpu().numpy()
            chunks.append(_mono(audio))
        if not chunks:
            raise RuntimeError("Kokoro produced no audio")
        return np.concatenate(chunks), TARGET_SAMPLE_RATE


def self_test() -> None:
    assert split_text("สวัสดีครับ") == [("th", "สวัสดีครับ")]
    assert split_text("Hello, this is Mini-kun.") == [("en", "Hello, this is Mini-kun.")]
    assert [kind for kind, _ in split_text("มินิคุง The deployment is ready แล้ว")] == ["th", "en", "th"]
    assert split_text("มินิคุง deploy Ollama ให้ครับ") == [("th", "มินิคุง deploy Ollama ให้ครับ")]
    print("mixed TTS segmentation self-test: ok")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18022)
    parser.add_argument("--model-dir", type=Path)
    parser.add_argument("--kokoro-model-dir", type=Path)
    parser.add_argument("--kokoro-voice", default="voices/am_michael.pt", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.model_dir is None or args.kokoro_model_dir is None:
        parser.error("--model-dir and --kokoro-model-dir are required")

    model_dir = args.model_dir.expanduser().resolve()
    kokoro_model_dir = args.kokoro_model_dir.expanduser().resolve()
    kokoro_voice = args.kokoro_voice.expanduser()
    if not kokoro_voice.is_absolute():
        kokoro_voice = kokoro_model_dir / kokoro_voice
    kokoro_voice = kokoro_voice.resolve()
    if not (model_dir / "tts.onnx").is_file() or not (model_dir / "vocab.json").is_file():
        raise SystemExit(f"VaniraTTS model files are missing under {model_dir}")
    if not (kokoro_model_dir / "config.json").is_file() \
            or not (kokoro_model_dir / "kokoro-v1_0.pth").is_file() \
            or not kokoro_voice.is_file():
        raise SystemExit(f"Kokoro model files are missing under {kokoro_model_dir}")

    print(f"[tts] loading VaniraTTS from {model_dir}", flush=True)
    vanira = VaniraTTS(local_path=str(model_dir), device="cpu")
    print(f"[tts] loading Kokoro from {kokoro_model_dir}", flush=True)
    kokoro_model = KModel(
        repo_id=str(kokoro_model_dir),
        config=str(kokoro_model_dir / "config.json"),
        model=str(kokoro_model_dir / "kokoro-v1_0.pth"),
    ).to("cpu").eval()
    kokoro = KPipeline(lang_code="a", repo_id=str(kokoro_model_dir), model=kokoro_model, device="cpu")

    server = TtsServer((args.host, args.port), Handler)
    server.state = State(vanira, model_dir, kokoro, kokoro_model_dir, kokoro_voice)
    print(f"[tts] ready on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Small JSON adapter around MLX Whisper. Input is normalized PCM WAV; audio is never retained."""

import argparse
import json
import wave

import numpy as np
import mlx_whisper


def load_pcm_wave(path: str) -> tuple[np.ndarray, float]:
    with wave.open(path, "rb") as source:
        channels = source.getnchannels()
        sample_width = source.getsampwidth()
        sample_rate = source.getframerate()
        frames = source.getnframes()
        if channels != 1 or sample_width != 2 or sample_rate != 16000:
            raise ValueError("expected mono 16-bit PCM WAV at 16000 Hz")
        raw = source.readframes(frames)
    samples = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    return samples, frames / float(sample_rate)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--language", default="auto")
    parser.add_argument("--prompt", default="")
    args = parser.parse_args()

    audio, duration = load_pcm_wave(args.input)
    options = {
        "path_or_hf_repo": args.model,
        "task": "transcribe",
        "temperature": 0.0,
        "verbose": False,
    }
    if args.language != "auto":
        options["language"] = args.language
    if args.prompt.strip():
        options["initial_prompt"] = args.prompt.strip()
    result = mlx_whisper.transcribe(audio, **options)
    print(json.dumps({
        "text": str(result.get("text", "")).strip(),
        "language": str(result.get("language", args.language)),
        "duration_seconds": round(duration, 3),
    }, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()

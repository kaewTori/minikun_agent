#!/bin/sh

set -eu

python_bin="${VOICE_PYTHON_BIN:-/opt/homebrew/bin/python3}"
hf_bin="${HF_BIN:-$HOME/.local/bin/hf}"
local_root="$HOME/Library/Application Support/Minikun"
venv="$local_root/python/voice-venv"
model_dir="$local_root/models/whisper-large-v3-turbo-q4"
model_repo="mlx-community/whisper-large-v3-turbo-q4"
model_revision="660c343bbf4e52ac257f0b7d952e5388e6f93bef"

if [ ! -x "$python_bin" ]; then
  echo "Python executable not found: $python_bin" >&2
  exit 1
fi
if [ ! -x "$hf_bin" ]; then
  echo "Hugging Face CLI not found: $hf_bin" >&2
  exit 1
fi

mkdir -p "$local_root/python" "$local_root/models"
if [ ! -x "$venv/bin/python" ]; then
  "$python_bin" -m venv "$venv"
fi
"$venv/bin/python" -m pip install --disable-pip-version-check "mlx-whisper==0.4.3"

if [ ! -f "$model_dir/weights.npz" ]; then
  "$hf_bin" download "$model_repo" --revision "$model_revision" --local-dir "$model_dir"
fi

"$venv/bin/python" -c 'import mlx_whisper, numpy; print("Voice runtime ready")'

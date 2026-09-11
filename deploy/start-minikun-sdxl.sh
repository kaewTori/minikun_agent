#!/bin/sh

set -eu

tinygrad_root="${MINIKUN_SDXL_TINYGRAD_ROOT:-/Volumes/minikun/homelab/tinygrad}"
python_bin="${MINIKUN_SDXL_PYTHON:-$tinygrad_root/.venv/bin/python3}"
weights="${MINIKUN_SDXL_WEIGHTS:-/Volumes/minikun/ollama/models/sd_model/sdxl/malaAnimeMixNSFW_v70WithoutVAE.safetensors}"
lora_slime="${MINIKUN_SDXL_LORA_SLIME:-/Volumes/minikun/ollama/models/sd_model/lora/Slime_suit-000011.safetensors}"
lora_corrupt="${MINIKUN_SDXL_LORA_CORRUPT:-/Volumes/minikun/ollama/models/sd_model/lora/CorruptPonyXL-000003.safetensors}"
lora_tentacles="${MINIKUN_SDXL_LORA_TENTACLES:-/Volumes/minikun/ollama/models/sd_model/lora/NSFW_Tentacles_Pony_V2.safetensors}"
host="${MINIKUN_SDXL_HOST:-127.0.0.1}"
port="${MINIKUN_SDXL_PORT:-8002}"
script_root="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
supervisor="${MINIKUN_SDXL_SUPERVISOR:-$script_root/sdxl-fault-supervisor.py}"

if ! cd "$tinygrad_root"; then
  echo "TinyGrad directory is not accessible: $tinygrad_root" >&2
  echo "Check that the volume is mounted and that the LaunchAgent has permission to access it." >&2
  exit 1
fi

if [ ! -x "$python_bin" ]; then
  echo "TinyGrad Python environment not found or not executable: $python_bin" >&2
  exit 1
fi
if [ ! -f "$supervisor" ]; then
  echo "TinyGrad fault supervisor not found: $supervisor" >&2
  exit 1
fi
if [ ! -f "$tinygrad_root/examples/sdxl_use.py" ]; then
  echo "TinyGrad SDXL server not found: $tinygrad_root/examples/sdxl_use.py" >&2
  exit 1
fi
if [ ! -f "$weights" ]; then
  echo "TinyGrad SDXL checkpoint not found: $weights" >&2
  exit 1
fi
if [ ! -f "$lora_slime" ]; then
  echo "TinyGrad SDXL LoRA not found: $lora_slime" >&2
  exit 1
fi

export PYTHONPATH="$tinygrad_root"
export ALLOW_TF32="${ALLOW_TF32:-1}"
export FLOAT16="${FLOAT16:-1}"
default_dev="${MINIKUN_SDXL_DEV:-}"
if [ -z "$default_dev" ]; then
  case "$(uname -s)" in
    Darwin) default_dev="NV" ;;
    *) default_dev="NV" ;;
  esac
fi
export DEV="${DEV:-$default_dev}"
# ponytail: keep JIT kernels, skip unstable NV graph replay on this host.
export JIT="${JIT:-2}"
export PATH="${MINIKUN_SDXL_PATH:-$HOME/.local/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin}"

set -- "$python_bin" examples/sdxl_use.py \
  --weights "$weights" \
  --lora "$lora_slime:${MINIKUN_SDXL_LORA_SLIME_SCALE:-1}"

if [ "${MINIKUN_SDXL_ENABLE_CORRUPT_LORA:-0}" = "1" ]; then
  if [ ! -f "$lora_corrupt" ]; then
    echo "TinyGrad SDXL LoRA not found: $lora_corrupt" >&2
    exit 1
  fi
  set -- "$@" --lora "$lora_corrupt:${MINIKUN_SDXL_LORA_CORRUPT_SCALE:-0.8}"
fi

if [ "${MINIKUN_SDXL_ENABLE_TENTACLES_LORA:-0}" = "1" ]; then
  if [ ! -f "$lora_tentacles" ]; then
    echo "TinyGrad SDXL LoRA not found: $lora_tentacles" >&2
    exit 1
  fi
  set -- "$@" --lora "$lora_tentacles:${MINIKUN_SDXL_LORA_TENTACLES_SCALE:-0.7}"
fi

set -- "$@" \
  --vae-tile-size "${MINIKUN_SDXL_VAE_TILE_SIZE:-512}" \
  --vae-tile-overlap "${MINIKUN_SDXL_VAE_TILE_OVERLAP:-64}" \
  --cache-entries "${MINIKUN_SDXL_CACHE_ENTRIES:-1}" \
  --host "$host" \
  --serve "$port"

# The full model plus three LoRAs reaches 12.24 GB with ADetailer enabled on
# this host, leaving too little memory for conditioning. Keep the automatic
# service within VRAM by default while allowing an explicit future override.
if [ "${MINIKUN_SDXL_ADETAILER_ENABLED:-1}" = "1" ]; then
  adetailer_device="${MINIKUN_SDXL_ADETAILER_DEVICE:-}"
  if [ -z "$adetailer_device" ]; then
    case "$(uname -s)" in
      Darwin) adetailer_device="auto" ;;
      *) adetailer_device="nv" ;;
    esac
  fi
  set -- "$@" \
    --adetailer-device "$adetailer_device" \
    --adetailer-steps "${MINIKUN_SDXL_ADETAILER_STEPS:-18}" \
    --adetailer-strength "${MINIKUN_SDXL_ADETAILER_STRENGTH:-0.30}" \
    --adetailer-mask-padding "${MINIKUN_SDXL_ADETAILER_MASK_PADDING:-0.15}" \
    --adetailer-max-faces "${MINIKUN_SDXL_ADETAILER_MAX_FACES:-3}"
else
  set -- "$@" --no-adetailer
fi

exec "$python_bin" "$supervisor" "$@"

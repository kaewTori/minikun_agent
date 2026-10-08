#!/bin/sh

set -eu

model="${EMBEDDING_MODEL:-embeddinggemma-2:270m-mxfp8-text}"

if ! command -v ollama >/dev/null 2>&1; then
  echo "Ollama is required before installing the Personal Knowledge runtime" >&2
  exit 1
fi

ollama pull "$model"
ollama show "$model" >/dev/null
echo "Personal Knowledge embedding runtime ready: $model"

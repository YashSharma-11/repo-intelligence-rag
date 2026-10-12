#!/usr/bin/env bash
# Downloads the local ONNX embedding model (all-MiniLM-L6-v2, ~90 MB) used for
# offline embeddings. The file is gitignored because of its size.
set -euo pipefail

DEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/src/main/resources/onnx"
MODEL_URL="https://media.githubusercontent.com/media/spring-projects/spring-ai/main/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2/model.onnx"

mkdir -p "$DEST_DIR"
echo "Downloading embedding model to $DEST_DIR/model.onnx ..."
curl -fL "$MODEL_URL" -o "$DEST_DIR/model.onnx"

SIZE=$(wc -c < "$DEST_DIR/model.onnx")
if [ "$SIZE" -lt 1000000 ]; then
  echo "ERROR: downloaded file is only $SIZE bytes (expected ~90 MB). Check your network." >&2
  exit 1
fi
echo "Done. model.onnx is $SIZE bytes."

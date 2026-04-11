#!/usr/bin/env bash
# start-node.sh — Launch a StorageNode on the given port.
#
# Usage:
#   ./scripts/start-node.sh [port] [dataDir]
#
# Defaults:
#   port    = 7000
#   dataDir = ./node-data/<port>
#
# The script must be run from the repository root (where pom.xml lives).

set -euo pipefail

PORT="${1:-7100}"    # 7000 is reserved by macOS AirPlay (ControlCenter)
DATA_DIR="${2:-node-data/${PORT}}"

echo "Starting StorageNode on port ${PORT}  (data → ${DATA_DIR})"
mkdir -p "${DATA_DIR}"

java -jar storage-node/target/storage-node.jar "${PORT}" "${DATA_DIR}"

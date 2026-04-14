#!/usr/bin/env bash
# start-cluster.sh — Launch N StorageNode processes on consecutive ports.
#
# Usage (from repo root):
#   ./scripts/start-cluster.sh [numNodes] [basePort] [dataRootDir]
#
# Defaults:
#   numNodes   = 6        (matches the default RS (6,4) config)
#   basePort   = 7100     (nodes listen on 7100, 7101, …, 7105)
#   dataRootDir= ./cluster-data
#
# The script prints the "host:port" list that can be pasted directly into
# rs-upload / rs-download / rs-demo commands.
#
# Stop all nodes:
#   kill $(cat /tmp/objectstore-pids.txt)
#   rm -f /tmp/objectstore-pids.txt
#
# Prerequisites:
#   mvn clean package (or ./scripts/build.sh) must have been run first.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.."; pwd)"
NODE_JAR="${ROOT_DIR}/storage-node/target/storage-node.jar"

NUM_NODES="${1:-6}"
BASE_PORT="${2:-7100}"
DATA_ROOT="${3:-${ROOT_DIR}/cluster-data}"
PID_FILE="/tmp/objectstore-pids.txt"

# ── 0. Sanity-check ───────────────────────────────────────────────────────────
if [[ ! -f "${NODE_JAR}" ]]; then
    echo "ERROR: ${NODE_JAR} not found."
    echo "       Run 'mvn clean package' (or use the Maven wrapper) from the repo root first."
    exit 1
fi

# ── 1. Kill any previously launched cluster ───────────────────────────────────
if [[ -f "${PID_FILE}" ]]; then
    echo "[cluster] Stopping previous cluster..."
    while IFS= read -r pid; do
        kill "${pid}" 2>/dev/null && echo "  killed pid ${pid}" || true
    done < "${PID_FILE}"
    rm -f "${PID_FILE}"
fi

# ── 2. Launch nodes ───────────────────────────────────────────────────────────
echo "[cluster] Starting ${NUM_NODES} StorageNode(s) from port ${BASE_PORT}..."
touch "${PID_FILE}"

NODE_LIST=""
for i in $(seq 0 $((NUM_NODES - 1))); do
    PORT=$((BASE_PORT + i))
    DATA_DIR="${DATA_ROOT}/node-${PORT}"
    mkdir -p "${DATA_DIR}"

    java -jar "${NODE_JAR}" "${PORT}" "${DATA_DIR}" \
        > "${DATA_ROOT}/node-${PORT}.log" 2>&1 &
    echo "$!" >> "${PID_FILE}"
    NODE_LIST="${NODE_LIST} localhost:${PORT}"
    echo "  Node ${i}: port=${PORT}  data=${DATA_DIR}  log=${DATA_ROOT}/node-${PORT}.log"
done

# ── 3. Wait for all nodes to be ready (up to 15 s each) ──────────────────────
echo ""
echo "[cluster] Waiting for nodes to become ready..."
for i in $(seq 0 $((NUM_NODES - 1))); do
    PORT=$((BASE_PORT + i))
    READY=false
    for attempt in $(seq 1 30); do
        if nc -z localhost "${PORT}" 2>/dev/null; then
            echo "  ✅  localhost:${PORT} — READY"
            READY=true
            break
        fi
        sleep 0.5
    done
    if [[ "${READY}" != "true" ]]; then
        echo "  ❌  localhost:${PORT} — FAILED to start within 15 s"
        echo "      Check log: ${DATA_ROOT}/node-${PORT}.log"
        exit 1
    fi
done

echo ""
echo "✅  Cluster of ${NUM_NODES} nodes is up."
echo ""
echo "PIDs saved to: ${PID_FILE}"
echo "  Stop with: kill \$(cat ${PID_FILE})"
echo ""
echo "Node addresses (copy-paste into rs-upload/rs-download):"
echo " ${NODE_LIST}"
echo ""

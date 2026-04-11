#!/usr/bin/env bash
# demo.sh — Phase 1 end-to-end demonstration.
#
# Steps:
#   1. Start a StorageNode in the background on port 7000.
#   2. Generate a random test file.
#   3. Upload it to the node as shard "demo-shard-0".
#   4. Download it back.
#   5. Assert byte equality with diff.
#   6. Stop the background node.
#
# Usage (from repo root, after building):
#   ./scripts/demo.sh
#
# Prerequisites:
#   mvn clean package must have been run first.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NODE_JAR="${ROOT_DIR}/storage-node/target/storage-node.jar"
CLIENT_JAR="${ROOT_DIR}/client/target/client.jar"

PORT=7100    # 7000 is reserved by macOS AirPlay (ControlCenter)
DATA_DIR="${ROOT_DIR}/demo-node-data"
SHARD_ID="demo-shard-0"
TEST_FILE="${ROOT_DIR}/demo-input.bin"
DOWNLOAD_FILE="${ROOT_DIR}/demo-output.bin"

# ── Cleanup on exit ────────────────────────────────────────────────────────
cleanup() {
    echo ""
    echo "[demo] Cleaning up..."
    if [[ -n "${NODE_PID-}" ]]; then
        kill "${NODE_PID}" 2>/dev/null && echo "[demo] StorageNode (pid ${NODE_PID}) stopped."
    fi
    rm -f "${TEST_FILE}" "${DOWNLOAD_FILE}"
    rm -rf "${DATA_DIR}"
    echo "[demo] Done."
}
trap cleanup EXIT

# ── 0. Verify JARs exist ───────────────────────────────────────────────────
if [[ ! -f "${NODE_JAR}" || ! -f "${CLIENT_JAR}" ]]; then
    echo "ERROR: JARs not found. Run 'mvn clean package' from the repo root first."
    exit 1
fi

# ── 1. Start StorageNode ───────────────────────────────────────────────────
echo "[demo] Starting StorageNode on port ${PORT}..."
mkdir -p "${DATA_DIR}"
java -jar "${NODE_JAR}" "${PORT}" "${DATA_DIR}" &
NODE_PID=$!

# Wait until the node is actually listening (up to 10 s)
echo "[demo] Waiting for StorageNode to be ready..."
for i in $(seq 1 20); do
    if nc -z localhost "${PORT}" 2>/dev/null; then
        echo "[demo] StorageNode is ready (pid ${NODE_PID})"
        break
    fi
    sleep 0.5
    if [[ "${i}" -eq 20 ]]; then
        echo "[demo] ERROR: StorageNode did not start in time."
        exit 1
    fi
done

# ── 2. Generate test file (64 KB of random bytes) ──────────────────────────
echo "[demo] Generating 64 KB test file: ${TEST_FILE}"
dd if=/dev/urandom of="${TEST_FILE}" bs=1024 count=64 2>/dev/null
echo "[demo] Test file size: $(wc -c < "${TEST_FILE}") bytes"

# ── 3. Upload ──────────────────────────────────────────────────────────────
echo ""
echo "[demo] === UPLOAD ==="
java -jar "${CLIENT_JAR}" upload "${TEST_FILE}" "${SHARD_ID}" localhost "${PORT}"

# ── 4. Download ────────────────────────────────────────────────────────────
echo ""
echo "[demo] === DOWNLOAD ==="
java -jar "${CLIENT_JAR}" download "${SHARD_ID}" "${DOWNLOAD_FILE}" localhost "${PORT}"

# ── 5. Verify ──────────────────────────────────────────────────────────────
echo ""
echo "[demo] === VERIFY ==="
if diff -q "${TEST_FILE}" "${DOWNLOAD_FILE}" > /dev/null; then
    echo "[demo] ✅  SUCCESS — files are identical (byte-for-byte match)"
else
    echo "[demo] ❌  FAILURE — files differ!"
    exit 1
fi

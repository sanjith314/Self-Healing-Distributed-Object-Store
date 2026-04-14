#!/usr/bin/env bash
# rs-demo.sh — Phase 3 end-to-end demonstration of Reed-Solomon erasure coding.
#
# Demo scenarios run in sequence:
#   1. Normal round-trip   — upload → download → diff
#   2. Node failure        — kill 2 of 6 nodes → download still succeeds
#   3. Corruption detection — flip a byte on disk → hash mismatch caught, RS reconstructs
#
# Usage (from repo root, after building):
#   ./scripts/rs-demo.sh
#
# Prerequisites:
#   mvn clean package must have been run first (fat JARs must exist).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.."; pwd)"
NODE_JAR="${ROOT_DIR}/storage-node/target/storage-node.jar"
CLIENT_JAR="${ROOT_DIR}/client/target/client.jar"
SCRIPTS_DIR="${ROOT_DIR}/scripts"

BASE_PORT=7100
NUM_NODES=6
DATA_ROOT="${ROOT_DIR}/rs-demo-data"
FILE_ID="demo-file"
TEST_FILE="${ROOT_DIR}/rs-demo-input.txt"
DOWNLOAD_FILE="${ROOT_DIR}/rs-demo-recovered.txt"
PID_FILE="/tmp/objectstore-rs-pids.txt"

# Build the node-address string: "localhost:7100 localhost:7101 ..."
NODES=""
for i in $(seq 0 $((NUM_NODES - 1))); do
    NODES="${NODES} localhost:$((BASE_PORT + i))"
done

# ── Helpers ───────────────────────────────────────────────────────────────────
log()    { echo "[rs-demo] $*"; }
header() { echo ""; echo "$(printf '═%.0s' {1..65})"; echo " $*"; echo "$(printf '═%.0s' {1..65})"; }

start_node() {
    local port="$1"
    local data_dir="${DATA_ROOT}/node-${port}"
    mkdir -p "${data_dir}"
    java -jar "${NODE_JAR}" "${port}" "${data_dir}" \
        > "${DATA_ROOT}/node-${port}.log" 2>&1 &
    echo "$!" >> "${PID_FILE}"
}

wait_for_port() {
    local port="$1"
    for _ in $(seq 1 20); do
        nc -z localhost "${port}" 2>/dev/null && return 0
        sleep 0.5
    done
    log "ERROR: port ${port} never became ready."
    return 1
}

stop_node_on_port() {
    # Kills the StorageNode listening on a given port (best-effort)
    local port="$1"
    local pid
    pid=$(lsof -ti "tcp:${port}" 2>/dev/null || true)
    if [[ -n "${pid}" ]]; then
        kill "${pid}" 2>/dev/null && log "Killed node on port ${port} (pid ${pid})" || true
        sleep 0.3
    fi
}

cleanup() {
    log "Cleaning up..."
    if [[ -f "${PID_FILE}" ]]; then
        while IFS= read -r pid; do
            kill "${pid}" 2>/dev/null || true
        done < "${PID_FILE}"
        rm -f "${PID_FILE}"
    fi
    rm -f "${TEST_FILE}" "${DOWNLOAD_FILE}"
    rm -rf "${DATA_ROOT}"
}
trap cleanup EXIT

# ── 0. Pre-flight ─────────────────────────────────────────────────────────────
if [[ ! -f "${NODE_JAR}" || ! -f "${CLIENT_JAR}" ]]; then
    log "ERROR: JARs not found. Run 'mvn clean package' first."
    exit 1
fi

# ── 1. Generate test file ─────────────────────────────────────────────────────
header "Setup"
mkdir -p "${DATA_ROOT}"
rm -f "${PID_FILE}"

# 64 KB of pseudo-random content
python3 -c "
import random, sys
rng = random.Random(42)
data = bytes(rng.getrandbits(8) for _ in range(64 * 1024))
sys.stdout.buffer.write(data)
" > "${TEST_FILE}"
log "Test file: ${TEST_FILE} ($(wc -c < "${TEST_FILE}") bytes)"

# ── 2. Start cluster ──────────────────────────────────────────────────────────
header "Scenario 1 — Normal round-trip"
log "Starting ${NUM_NODES} nodes on ports ${BASE_PORT}–$((BASE_PORT + NUM_NODES - 1))..."
for i in $(seq 0 $((NUM_NODES - 1))); do
    start_node $((BASE_PORT + i))
done
for i in $(seq 0 $((NUM_NODES - 1))); do
    wait_for_port $((BASE_PORT + i)) && log "  ✅  localhost:$((BASE_PORT + i)) ready"
done

# ── 3. Upload ─────────────────────────────────────────────────────────────────
log ""
log "Uploading '${TEST_FILE}' as '${FILE_ID}' across ${NUM_NODES} nodes..."
java -jar "${CLIENT_JAR}" rs-upload "${TEST_FILE}" "${FILE_ID}" ${NODES}

# ── 4. Download (all nodes healthy) ──────────────────────────────────────────
log ""
log "Downloading '${FILE_ID}' (all nodes healthy)..."
java -jar "${CLIENT_JAR}" rs-demo "${TEST_FILE}" "${FILE_ID}" ${NODES}

log ""
if diff -q "${TEST_FILE}" "${ROOT_DIR}/rs-demo-input.txt.recovered" > /dev/null 2>&1; then
    log "✅  Scenario 1 PASSED — recovered file is identical to original."
else
    log "❌  Scenario 1 FAILED — files differ!"
    exit 1
fi
rm -f "${ROOT_DIR}/rs-demo-input.txt.recovered"

# ── 5. Scenario 2: Node Failure ───────────────────────────────────────────────
header "Scenario 2 — Node failure (kill 2 of 6)"
log "Killing nodes on ports $((BASE_PORT + 2)) and $((BASE_PORT + 4))..."
stop_node_on_port $((BASE_PORT + 2))
stop_node_on_port $((BASE_PORT + 4))
sleep 0.5

log "Attempting download with 4 of 6 nodes remaining (need k=4)..."
RECOVERED_2="${ROOT_DIR}/rs-demo-scenario2.bin"
java -jar "${CLIENT_JAR}" rs-download "${FILE_ID}" "${RECOVERED_2}" ${NODES}

log ""
if diff -q "${TEST_FILE}" "${RECOVERED_2}" > /dev/null 2>&1; then
    log "✅  Scenario 2 PASSED — reconstructed from 4 of 6 shards despite 2 dead nodes."
else
    log "❌  Scenario 2 FAILED — could not recover with 2 nodes offline!"
    exit 1
fi
rm -f "${RECOVERED_2}"


# ── 6. Scenario 3: Corruption Detection ──────────────────────────────────────
header "Scenario 3 — Corruption detection (hash mismatch → erasure → RS reconstruct)"

# Restart the killed nodes so all 6 are back
start_node $((BASE_PORT + 2))
start_node $((BASE_PORT + 4))
wait_for_port $((BASE_PORT + 2)) && log "  ✅  localhost:$((BASE_PORT + 2)) restarted"
wait_for_port $((BASE_PORT + 4)) && log "  ✅  localhost:$((BASE_PORT + 4)) restarted"

# Re-upload (fresh shards on restarted nodes — this also saves the manifest)
java -jar "${CLIENT_JAR}" rs-upload "${TEST_FILE}" "${FILE_ID}" ${NODES}

# Physically corrupt shard 1 on disk
SHARD_PATH="${DATA_ROOT}/node-$((BASE_PORT + 1))/${FILE_ID}/shard-1"
log ""
log "Corrupting shard on disk: ${SHARD_PATH}"
python3 "${SCRIPTS_DIR}/corrupt-shard.py" "${SHARD_PATH}" 0
log ""

# Now download — client should detect hash mismatch and treat it as an erasure
log "Downloading with 1 corrupted shard (must still reconstruct from 5 healthy ones)..."
RECOVERED_3="${ROOT_DIR}/rs-demo-scenario3.bin"
java -jar "${CLIENT_JAR}" rs-download "${FILE_ID}" "${RECOVERED_3}" ${NODES}

log ""
if diff -q "${TEST_FILE}" "${RECOVERED_3}" > /dev/null 2>&1; then
    log "✅  Scenario 3 PASSED — corruption detected and data reconstructed via RS."
else
    log "❌  Scenario 3 FAILED — corrupted shard was not handled correctly!"
    exit 1
fi
rm -f "${RECOVERED_3}"

# ── Done ──────────────────────────────────────────────────────────────────────
header "All Phase 3 scenarios passed ✅"

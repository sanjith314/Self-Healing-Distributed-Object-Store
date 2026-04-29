#!/usr/bin/env bash
# coded-demo.sh - Phase 3 demonstration of manual linear coding with homomorphic fingerprints.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.."; pwd)"
NODE_JAR="${ROOT_DIR}/storage-node/target/storage-node.jar"
CLIENT_JAR="${ROOT_DIR}/client/target/client.jar"
SCRIPTS_DIR="${ROOT_DIR}/scripts"

BASE_PORT=7100
NUM_NODES=6
DATA_ROOT="${ROOT_DIR}/coded-demo-data"
FILE_ID="demo-file"
TEST_FILE="${ROOT_DIR}/coded-demo-input.txt"
PID_FILE="/tmp/objectstore-coded-pids.txt"

NODES=""
for i in $(seq 0 $((NUM_NODES - 1))); do
    NODES="${NODES} localhost:$((BASE_PORT + i))"
done

log() { echo "[coded-demo] $*"; }
header() { echo ""; echo "$(printf '=%.0s' {1..65})"; echo " $*"; echo "$(printf '=%.0s' {1..65})"; }

start_node() {
    local port="$1"
    local data_dir="${DATA_ROOT}/node-${port}"
    mkdir -p "${data_dir}"
    java -jar "${NODE_JAR}" "${port}" "${data_dir}" > "${DATA_ROOT}/node-${port}.log" 2>&1 &
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
    rm -f "${TEST_FILE}" "${ROOT_DIR}/${FILE_ID}.manifest"
    rm -rf "${DATA_ROOT}"
}
trap cleanup EXIT

if [[ ! -f "${NODE_JAR}" || ! -f "${CLIENT_JAR}" ]]; then
    log "ERROR: JARs not found. Run 'mvn clean package' first."
    exit 1
fi

header "Setup"
mkdir -p "${DATA_ROOT}"
rm -f "${PID_FILE}"
python3 -c "
import random, sys
rng = random.Random(42)
sys.stdout.buffer.write(bytes(rng.getrandbits(8) for _ in range(64 * 1024)))
" > "${TEST_FILE}"
log "Test file: ${TEST_FILE} ($(wc -c < "${TEST_FILE}") bytes)"

header "Scenario 1 - Normal round-trip"
for i in $(seq 0 $((NUM_NODES - 1))); do
    start_node $((BASE_PORT + i))
done
for i in $(seq 0 $((NUM_NODES - 1))); do
    wait_for_port $((BASE_PORT + i)) && log "localhost:$((BASE_PORT + i)) ready"
done

java -jar "${CLIENT_JAR}" coded-upload "${TEST_FILE}" "${FILE_ID}" ${NODES}
RECOVERED_1="${ROOT_DIR}/coded-demo-scenario1.bin"
java -jar "${CLIENT_JAR}" coded-download "${FILE_ID}" "${RECOVERED_1}"
diff -q "${TEST_FILE}" "${RECOVERED_1}"
rm -f "${RECOVERED_1}"
log "Scenario 1 passed."

header "Scenario 2 - Node failure"
stop_node_on_port $((BASE_PORT + 2))
stop_node_on_port $((BASE_PORT + 4))
RECOVERED_2="${ROOT_DIR}/coded-demo-scenario2.bin"
java -jar "${CLIENT_JAR}" coded-download "${FILE_ID}" "${RECOVERED_2}"
diff -q "${TEST_FILE}" "${RECOVERED_2}"
rm -f "${RECOVERED_2}"
log "Scenario 2 passed."

header "Scenario 3 - Corruption detection"
start_node $((BASE_PORT + 2))
start_node $((BASE_PORT + 4))
wait_for_port $((BASE_PORT + 2))
wait_for_port $((BASE_PORT + 4))
java -jar "${CLIENT_JAR}" coded-upload "${TEST_FILE}" "${FILE_ID}" ${NODES}

SHARD_PATH="${DATA_ROOT}/node-$((BASE_PORT + 1))/${FILE_ID}/shard-1"
python3 "${SCRIPTS_DIR}/corrupt-shard.py" "${SHARD_PATH}" 0
RECOVERED_3="${ROOT_DIR}/coded-demo-scenario3.bin"
java -jar "${CLIENT_JAR}" coded-download "${FILE_ID}" "${RECOVERED_3}"
diff -q "${TEST_FILE}" "${RECOVERED_3}"
rm -f "${RECOVERED_3}"
log "Scenario 3 passed."

header "All coded-object scenarios passed"

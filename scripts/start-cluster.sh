#!/usr/bin/env bash
# start-cluster.sh — Launch 6 StorageNode processes on consecutive ports.
#
# Usage (from repo root):
#   ./scripts/start-cluster.sh          # basic mode (no peer awareness)
#   ./scripts/start-cluster.sh --avid   # AVID-FP mode (peers configured)
#
# Stop all nodes:
#   kill $(cat /tmp/objectstore-pids.txt)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.."; pwd)"
NODE_JAR="${ROOT_DIR}/storage-node/target/storage-node.jar"

NUM_NODES=6
BASE_PORT=7100
DATA_ROOT="${ROOT_DIR}/cluster-data"
PID_FILE="/tmp/objectstore-pids.txt"
AVID_MODE=false

# Check for --avid flag
for arg in "$@"; do
    if [[ "$arg" == "--avid" ]]; then AVID_MODE=true; fi
done

if [[ ! -f "${NODE_JAR}" ]]; then
    echo "ERROR: ${NODE_JAR} not found. Run 'mvn clean package' first."
    exit 1
fi

# Kill previous cluster and wait for ports to free up
if [[ -f "${PID_FILE}" ]]; then
    echo "[cluster] Stopping previous cluster..."
    while IFS= read -r pid; do
        kill "${pid}" 2>/dev/null && echo "  killed pid ${pid}" || true
    done < "${PID_FILE}"
    rm -f "${PID_FILE}"

    # Wait for ports to actually free up (avoid "Address already in use")
    echo "[cluster] Waiting for ports to free up..."
    for i in $(seq 0 $((NUM_NODES - 1))); do
        PORT=$((BASE_PORT + i))
        for attempt in $(seq 1 20); do
            if ! lsof -ti:${PORT} >/dev/null 2>&1; then
                break
            fi
            sleep 0.3
        done
    done
    sleep 1
fi

# Build peer list for AVID-FP mode
ALL_PORTS=()
for i in $(seq 0 $((NUM_NODES - 1))); do
    ALL_PORTS+=($((BASE_PORT + i)))
done

echo "[cluster] Starting ${NUM_NODES} nodes (AVID=${AVID_MODE})..."
touch "${PID_FILE}"
NODE_LIST=""

for i in $(seq 0 $((NUM_NODES - 1))); do
    PORT=${ALL_PORTS[$i]}
    DATA_DIR="${DATA_ROOT}/node-${PORT}"
    mkdir -p "${DATA_DIR}"

    if [[ "${AVID_MODE}" == "true" ]]; then
        # Build comma-separated peer list (all nodes except this one)
        PEERS=""
        for j in $(seq 0 $((NUM_NODES - 1))); do
            if [[ $j -ne $i ]]; then
                [[ -n "$PEERS" ]] && PEERS="${PEERS},"
                PEERS="${PEERS}localhost:${ALL_PORTS[$j]}"
            fi
        done
        java -jar "${NODE_JAR}" "${PORT}" "${DATA_DIR}" --peers "${PEERS}" \
            > "${DATA_ROOT}/node-${PORT}.log" 2>&1 &
    else
        java -jar "${NODE_JAR}" "${PORT}" "${DATA_DIR}" \
            > "${DATA_ROOT}/node-${PORT}.log" 2>&1 &
    fi

    echo "$!" >> "${PID_FILE}"
    NODE_LIST="${NODE_LIST} localhost:${PORT}"
    echo "  Node ${i}: port=${PORT}"
done

# Wait for nodes to accept connections
echo ""
echo "[cluster] Waiting for nodes..."
for PORT in "${ALL_PORTS[@]}"; do
    for attempt in $(seq 1 30); do
        if nc -z localhost "${PORT}" 2>/dev/null; then
            echo "  OK localhost:${PORT}"
            break
        fi
        sleep 0.5
        if [[ $attempt -eq 30 ]]; then
            echo "  FAILED localhost:${PORT}"
            exit 1
        fi
    done
done

echo ""
echo "Cluster is up!"
echo "  Stop with: kill \$(cat ${PID_FILE})"
echo "  Nodes:${NODE_LIST}"
echo ""

#!/usr/bin/env bash
#
# demo.sh - Demonstrates the distributed object store
#
# This runs through 4 tests:
#   1. Basic upload and download (happy path)
#   2. Node failure - kill 2 nodes, still recover the file
#   3. Corruption detection - tamper with a fragment on disk, FPCC catches it
#   4. AVID-FP - the full server-to-server consensus protocol from the paper
#
# Run from the repo root:
#   ./scripts/demo.sh

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.."; pwd)"
cd "$ROOT"

NODES="localhost:7100 localhost:7101 localhost:7102 localhost:7103 localhost:7104 localhost:7105"
CLIENT="java -jar client/target/client.jar"
PASS=0
FAIL=0

# helper to print section headers
section() {
    echo ""
    echo "================================================================"
    echo "  $1"
    echo "================================================================"
    echo ""
}

cleanup() {
    echo ""
    echo "-- Cleaning up --"
    pkill -f "storage-node.jar" 2>/dev/null || true
    rm -f /tmp/objectstore-pids.txt
    rm -f testfile.txt recovered.txt recovered2.txt recovered3.txt
    rm -f myfile.manifest myfile2.manifest testfile.txt.recovered
    rm -rf cluster-data
}

# make sure we clean up on exit
trap cleanup EXIT

# ---- build ----
section "Building the project"
mvn clean package -q
echo "Build successful."

# create a test file
echo "Hello! This is a test file for our distributed object store. It will be split into fragments, distributed across 6 storage nodes, and reconstructed even if some nodes go down." > testfile.txt
FILESIZE=$(wc -c < testfile.txt | tr -d ' ')
echo "Created test file (${FILESIZE} bytes)."


# ================================================================
# TEST 1: happy path - upload then download, compare bytes
# ================================================================
section "TEST 1: Upload and Download (happy path)"

./scripts/start-cluster.sh
sleep 1

echo "Uploading..."
$CLIENT demo testfile.txt myfile $NODES

# the demo command already checks byte equality, so if we get here it passed
echo ""
echo ">> TEST 1 PASSED"
PASS=$((PASS + 1))


# ================================================================
# TEST 2: kill 2 nodes, download should still work
# ================================================================
section "TEST 2: Node failure recovery (kill 2 of 6 nodes)"

echo "Killing nodes on ports 7104 and 7105..."
kill $(sed -n '5p' /tmp/objectstore-pids.txt) 2>/dev/null || true
kill $(sed -n '6p' /tmp/objectstore-pids.txt) 2>/dev/null || true
sleep 1

echo "Downloading with 2 nodes down..."
$CLIENT download myfile recovered.txt

if diff -q testfile.txt recovered.txt > /dev/null 2>&1; then
    echo ""
    echo ">> TEST 2 PASSED - file recovered with 2 nodes down"
    PASS=$((PASS + 1))
else
    echo ""
    echo ">> TEST 2 FAILED"
    FAIL=$((FAIL + 1))
fi


# ================================================================
# TEST 3: corrupt a fragment on disk, FPCC should catch it
# ================================================================
section "TEST 3: Corruption detection via FPCC"

# restart the full cluster
./scripts/start-cluster.sh
sleep 1

echo "Uploading a fresh copy..."
$CLIENT upload testfile.txt myfile2 $NODES

echo ""
echo "Corrupting fragment 2 on node 7102..."
echo "this data is corrupted" > cluster-data/node-7102/myfile2/shard-2

echo "Downloading (FPCC should detect the bad fragment)..."
$CLIENT download myfile2 recovered2.txt

if diff -q testfile.txt recovered2.txt > /dev/null 2>&1; then
    echo ""
    echo ">> TEST 3 PASSED - corruption detected and file recovered"
    PASS=$((PASS + 1))
else
    echo ""
    echo ">> TEST 3 FAILED"
    FAIL=$((FAIL + 1))
fi


# ================================================================
# TEST 4: AVID-FP protocol (server-to-server consensus)
# ================================================================
section "TEST 4: AVID-FP consensus protocol"

# restart with peer awareness
./scripts/start-cluster.sh --avid
sleep 2

echo "Dispersing file using AVID-FP protocol..."
$CLIENT avid-disperse testfile.txt myfile3 $NODES

echo ""
echo "Retrieving via AVID-FP..."
$CLIENT avid-retrieve myfile3 $FILESIZE recovered3.txt $NODES

if diff -q testfile.txt recovered3.txt > /dev/null 2>&1; then
    echo ""
    echo ">> TEST 4 PASSED - AVID-FP round trip works"
    PASS=$((PASS + 1))
else
    echo ""
    echo ">> TEST 4 FAILED"
    FAIL=$((FAIL + 1))
fi


# ================================================================
# Results
# ================================================================
section "Results"
echo "  Passed: ${PASS}"
echo "  Failed: ${FAIL}"
echo ""

if [[ $FAIL -eq 0 ]]; then
    echo "All tests passed!"
    exit 0
else
    echo "Some tests failed."
    exit 1
fi

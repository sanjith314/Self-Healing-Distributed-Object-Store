# Distributed Object Store with Integrity

A fault-tolerant distributed object storage system that preserves data integrity even when stored on untrusted/failing servers.

Based on: **Hendricks, Ganger & Reiter — "Verifying Distributed Erasure-Coded Data"** (PODC 2007)  
Paper: https://dl.acm.org/doi/pdf/10.1145/1281100.1281122

## How It Works

1. **Erasure Coding**: Files are split into `k` data fragments and `n-k` parity fragments using a manual GF(2⁸) Vandermonde codec. Any `k` out of `n` fragments can reconstruct the original file. Default: `(n=6, k=4)` — tolerates any 2 fragment losses with only 1.5× storage overhead.

2. **Fingerprinted Cross-Checksum (FPCC)**: Each upload computes a compact integrity structure containing SHA-256 hashes and homomorphic fingerprints of all fragments. Any party can independently verify that a fragment was correctly derived from the original data without seeing all other fragments.

3. **AVID-FP Protocol**: The full server-to-server consensus protocol from Section 4 of the paper. Servers exchange ECHO and READY messages to reach agreement, tolerating up to `f` Byzantine (malicious) servers out of `n = m + 2f`.

## Project Structure

```
common/         — Shared: GF(2⁸) arithmetic, SHA-256 hashing, homomorphic fingerprinting, wire protocol
client/         — Client: erasure codec, FPCC, upload/download engine, AVID-FP client
storage-node/   — Storage server: STORE/RETRIEVE handlers, AVID-FP echo/ready consensus
scripts/        — Cluster launch script
```

## Building

```bash
mvn clean package
```

## Running

### 1. Start a cluster of 6 storage nodes

```bash
./scripts/start-cluster.sh
```

This starts nodes on ports 7100–7105.

### 2. Upload a file (erasure coded + FPCC verified)

```bash
java -jar client/target/client.jar upload myfile.txt myfile \
  localhost:7100 localhost:7101 localhost:7102 \
  localhost:7103 localhost:7104 localhost:7105
```

### 3. Download and reconstruct

```bash
java -jar client/target/client.jar download myfile recovered.txt
```

### 4. Full demo (upload → download → verify)

```bash
java -jar client/target/client.jar demo myfile.txt myfile \
  localhost:7100 localhost:7101 localhost:7102 \
  localhost:7103 localhost:7104 localhost:7105
```

### 5. AVID-FP mode (server-to-server consensus)

Start nodes with peer awareness:
```bash
# Example for node 0 (port 7100), knowing about all other peers:
java -jar storage-node/target/storage-node.jar 7100 data/node-7100 \
  --peers localhost:7101,localhost:7102,localhost:7103,localhost:7104,localhost:7105
```

Then disperse and retrieve:
```bash
java -jar client/target/client.jar avid-disperse myfile.txt myfile \
  localhost:7100 localhost:7101 localhost:7102 \
  localhost:7103 localhost:7104 localhost:7105

java -jar client/target/client.jar avid-retrieve myfile <originalFileSize> recovered.txt \
  localhost:7100 localhost:7101 localhost:7102 \
  localhost:7103 localhost:7104 localhost:7105
```

## Corruption Detection Demo

To see integrity verification in action:
1. Upload a file using the `demo` command
2. Kill one or two storage node processes
3. Run download — the system detects the missing fragments and reconstructs from the remaining healthy ones

You can also corrupt a stored fragment on disk:
```bash
# Corrupt a fragment
echo "corrupted" > cluster-data/node-7102/myfile/shard-2
```
The FPCC verification will detect the corruption and treat it as an erasure.

## Technical Details

| Component | Implementation |
|-----------|---------------|
| Language | Java 17+ |
| Erasure Coding | Manual GF(2⁸) Vandermonde codec (`LinearErasureCodec`) |
| Integrity | Fingerprinted Cross-Checksum (`FingerprintedCrossChecksum`) |
| Fingerprinting | Evaluation fingerprinting over GF(2⁸) (`HomomorphicFingerprint`) |
| Networking | Plain Java TCP sockets |
| Hashing | SHA-256 via `java.security.MessageDigest` |
| Concurrency | `CompletableFuture` + `ExecutorService` for parallel I/O |

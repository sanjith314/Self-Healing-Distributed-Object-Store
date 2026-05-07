# Distributed Object Store with Integrity

A fault-tolerant distributed storage system that verifies file integrity across multiple untrusted servers using erasure coding and fingerprinted cross-checksums.

Based on: **Hendricks, Ganger & Reiter — "Verifying Distributed Erasure-Coded Data" (PODC 2007)**

## Overview

When you store a file, the system:
1. Splits it into 4 data fragments using a GF(2^8) Vandermonde erasure code
2. Generates 2 parity fragments (so any 4 of 6 can reconstruct the file)
3. Computes a Fingerprinted Cross-Checksum (FPCC) for integrity
4. Distributes the 6 fragments across 6 storage nodes

When you download, it:
1. Fetches fragments from all reachable nodes
2. Verifies each fragment against the FPCC (SHA-256 hash + homomorphic fingerprint)
3. Discards any corrupted fragments
4. Reconstructs the original file from any 4 healthy fragments

The system tolerates up to 2 node failures or corruptions and still recovers the file perfectly.

## Project Structure

```
common/                  Shared code (protocol, GF(2^8) math, hashing, fingerprinting)
├── Protocol.java        Wire protocol for client-node and node-node communication
├── GaloisField256.java  GF(2^8) finite field arithmetic (add, multiply, inverse, matrix inversion)
├── HashUtil.java        SHA-256 utilities
└── HomomorphicFingerprint.java  Polynomial evaluation fingerprinting (Theorem 2.4)

client/                  Client-side code
├── Client.java          CLI entry point (upload, download, demo, avid-disperse, avid-retrieve)
├── ErasureClient.java   Upload/download engine with parallel I/O and FPCC verification
├── AvidFpClient.java    AVID-FP consensus protocol client (Section 4 of the paper)
├── LinearErasureCodec.java  (6,4) Vandermonde erasure encoder/decoder over GF(2^8)
├── FingerprintedCrossChecksum.java  FPCC: SHA-256 hashes + homomorphic fingerprints
├── NodeConnection.java  TCP connection to a single storage node
└── ShardManifest.java   Client-held metadata (node addresses, FPCC, coding params)

storage-node/            Storage server
└── StorageNode.java     Handles STORE, RETRIEVE, GETHASH + AVID-FP echo/ready consensus

scripts/
├── start-cluster.sh     Starts 6 storage nodes on ports 7100-7105
└── demo.sh              Runs all 4 demo tests automatically
```

## Prerequisites

- Java 17+
- Maven

## Quick Start

Build the project:
```
mvn clean package -q
```

Run the full demo (builds, tests everything, cleans up after):
```
./scripts/demo.sh
```

This runs 4 tests:
1. **Happy path** — upload a file, download it, verify bytes match
2. **Node failure** — kill 2 of 6 nodes, download still works
3. **Corruption detection** — tamper with a stored fragment, FPCC catches it
4. **AVID-FP** — server-to-server consensus protocol from the paper

## Running Manually

### Start storage nodes

```
./scripts/start-cluster.sh
```

### Upload a file

```
java -jar client/target/client.jar upload myfile.txt myfile localhost:7100 localhost:7101 localhost:7102 localhost:7103 localhost:7104 localhost:7105
```

### Download a file

```
java -jar client/target/client.jar download myfile recovered.txt
```

### Upload + download + verify in one step

```
java -jar client/target/client.jar demo myfile.txt myfile localhost:7100 localhost:7101 localhost:7102 localhost:7103 localhost:7104 localhost:7105
```

### Test corruption detection

After uploading, corrupt a fragment on disk:
```
echo "bad data" > cluster-data/node-7102/myfile/shard-2
```

Then download — the FPCC will detect the corruption and reconstruct from the remaining 5 fragments.

### AVID-FP consensus mode

Start nodes with peer awareness:
```
./scripts/start-cluster.sh --avid
```

Disperse and retrieve:
```
java -jar client/target/client.jar avid-disperse myfile.txt myfile localhost:7100 localhost:7101 localhost:7102 localhost:7103 localhost:7104 localhost:7105

java -jar client/target/client.jar avid-retrieve myfile 188 recovered.txt localhost:7100 localhost:7101 localhost:7102 localhost:7103 localhost:7104 localhost:7105
```

(Replace `188` with your file's size in bytes)

### Stop the cluster

```
kill $(cat /tmp/objectstore-pids.txt)
```

## How the Integrity Check Works

The Fingerprinted Cross-Checksum (FPCC) is the core idea from the paper. It has two parts:

- **Cross-checksum**: SHA-256 hash of each of the 6 encoded fragments
- **Source fingerprints**: homomorphic fingerprints of the 4 original data fragments

To verify fragment `i`, we check:
1. Does `SHA-256(fragment)` match the stored hash? (catches random corruption)
2. Does `fingerprint(fragment)` match what the erasure code would produce from the source fingerprints? (catches fragments that belong to a different file)

The fingerprint function uses polynomial evaluation over GF(2^8) — it's homomorphic, meaning `fp(encode(data)) = encode(fp(data))`. This lets us verify each fragment independently without needing all the other fragments.

## Tech Stack

| What | How |
|------|-----|
| Language | Java 17 |
| Erasure coding | Manual GF(2^8) Vandermonde codec (no libraries) |
| Integrity | Fingerprinted Cross-Checksum (FPCC) |
| Fingerprinting | Polynomial evaluation over GF(2^8) |
| Networking | Plain Java TCP sockets |
| Hashing | SHA-256 (java.security.MessageDigest) |
| Concurrency | CompletableFuture for parallel node I/O |

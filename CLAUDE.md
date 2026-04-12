# CLAUDE.md — Distributed Object Store with Integrity

## Project Overview

Build a fault-tolerant, distributed object storage system that preserves data integrity even when data is stored on untrusted or failing servers. The system combines **Erasure Coding** (Reed-Solomon) for space-efficient redundancy with **Cryptographic Hashing** for per-shard integrity verification, enabling automatic detection and repair of corrupted or lost data.

---

## Goals

- Store files across `n` storage nodes using `(n, k)` Reed-Solomon erasure coding
- Fingerprint every shard with SHA-256 so corruption is detectable at the byte level
- Tolerate loss or corruption of any `n - k` shards and still reconstruct the original file
- Run a background Auditor that detects degraded shards and repairs them autonomously
- Demonstrate the full "detect → reconstruct → re-deploy" self-repair loop

---

## Architecture

Three independently deployable processes communicate over the network:

```
┌──────────┐      shards + hashes      ┌───────────────────┐
│  Client  │ ─────────────────────────▶ │  Storage Node 1   │
│          │ ◀───────────────────────── │  Storage Node 2   │
│ (Encodes │      shard on request      │  Storage Node 3   │
│  Decodes │                            │  Storage Node ...n│
│ Verifies)│                            └───────────────────┘
└──────────┘
      ▲
      │ Master Manifest (client-held, signed)
      │
┌─────────────┐    periodic audit     ┌───────────────────┐
│   Auditor   │ ─────────────────────▶│  Storage Nodes    │
│  (Healer)   │ ◀───────────────────── │                   │
│             │    shard hashes        └───────────────────┘
│ Acts as     │
│ agent of    │
│ client only │
└─────────────┘
```

### A. Storage Nodes

- Simple data repositories; deliberately "dumb"
- Expose two operations: `store(shardId, bytes)` and `retrieve(shardId) → bytes`
- Also expose `getHash(shardId) → SHA-256 hex` for Auditor use (computes hash on the fly; does **not** store it — nodes are untrusted)
- Failure modes: crash (offline) or Byzantine (return altered bytes)

### B. Client

1. **Encode**: Split file into `k` data shards; generate `n - k` parity shards via Reed-Solomon
2. **Fingerprint**: Compute SHA-256 for every shard → store in the **Master Manifest**
3. **Distribute**: Upload each shard to a distinct Storage Node in parallel
4. **Retrieve**: Download `k` or more shards in parallel; verify each shard's hash against the Manifest; treat hash mismatches as erasures; reconstruct with RS decoding

> **Important**: The Master Manifest (file name → shard list → expected hashes) is **client-owned and client-signed**. The Auditor acts only as an agent of the client; it does not have independent authority over the manifest. This is the correct trust model — the manifest cannot be on an untrusted server.

### C. Auditor

- Runs as a standalone background process
- Receives a **read-only copy of the signed Manifest** from the client at upload time
- Periodic audit loop:
  1. Ping each node — mark unresponsive nodes as `DOWN`
  2. Request `getHash(shardId)` from each live node; compare against Manifest
  3. On mismatch or missing node: fetch `k` healthy shards, reconstruct missing shard via RS, re-upload to a healthy (or replacement) node
- **Repair lock**: If more than `n - k` shards are simultaneously degraded, the Auditor raises an alert rather than attempting partial repair. Do not attempt reconstruction when quorum is lost.

---

## Key Concepts

### Erasure Coding — Reed-Solomon `(n, k)`

| Symbol | Meaning |
|--------|---------|
| `k` | Number of original data shards |
| `n` | Total shards (data + parity) |
| `n - k` | Max shards that can be lost/corrupted while still recovering |

**Default config**: `(6, 4)` — 4 data shards + 2 parity shards. Tolerates any 2 failures. Storage overhead: 1.5x (vs. 3x for full replication).

### Integrity Verification

- Every shard is SHA-256 fingerprinted at upload time
- On download (by Client or Auditor), recompute hash and compare against Manifest
- A hash mismatch → shard is immediately discarded and treated as an **erasure** for RS decoding purposes (not a "corruption" requiring a separate code path — Reed-Solomon handles erasures natively)
- This is the critical distinction: **mismatch = erasure flag**, not a separate error type

### Merkle Tree (Stretch Goal)

Instead of a flat list of hashes, root all shard hashes in a Merkle tree. Benefits:
- A client can verify which specific shard is corrupt via a single logarithmic-length proof
- No need to download all shards to locate the corrupted one
- Directly mirrors production integrity systems (e.g., Git, ZFS, Cassandra)

---

## Technical Stack

| Concern | Library / API |
|---------|---------------|
| Language | Java 17+ |
| Erasure Coding | [Backblaze Reed-Solomon](https://github.com/Backblaze/JavaReedSolomon) |
| Networking | Java `ServerSocket` / `Socket` (keep it simple; see note below) |
| Integrity | `java.security.MessageDigest` (SHA-256) |
| Concurrency | `CompletableFuture` + `ExecutorService` for parallel node I/O |
| Serialization | Plain byte arrays over sockets, or Protocol Buffers if you want gRPC later |

> **Networking choice**: Commit to plain Java sockets for now. They are easier to debug, easier to demo partial failures (just kill the process), and introduce no external dependencies. gRPC can be layered on in a v2 without changing core logic.

---

## Implementation Roadmap

### Phase 1 — Single-Node Baseline ✅ COMPLETE
- [x] `StorageNode`: TCP server, handles `STORE` and `RETRIEVE` commands
- [x] `Client`: connects to one node, uploads a raw file, downloads it, confirms byte equality
- [x] Establish the wire protocol (length-prefixed byte arrays over plain TCP sockets)

### Phase 2 — Hashing & Manifest ✅ COMPLETE
- [x] Implement `ShardManifest` — maps `(fileId, shardIndex)` → `expectedSha256`
- [x] Client computes SHA-256 on every chunk before sending
- [x] Client re-verifies hash on every received chunk
- [x] Unit test: flip one byte in the received data and confirm detection
- [x] `StorageNode` adds `GETHASH` command (recomputes from stored bytes on the fly)

> **Why Phase 2 before Phase 3?** Hash logic is easiest to unit-test in isolation on a single node. Debugging a hash mismatch while also debugging multi-node fanout is significantly harder.

### Phase 3 — Erasure Coding & Multi-Node Distribution
- [ ] Integrate Backblaze RS library; unit test `encode → decode` round-trip locally
- [ ] `Client.upload(file)`: split into `k` data shards → encode `n - k` parity shards → upload shard `i` to node `i`
- [ ] `Client.download(fileId)`: fetch all reachable shards in parallel → verify hashes → pass erasure flags to RS decoder → reconstruct
- [ ] Test with one node offline; confirm successful reconstruction

### Phase 4 — Auditor (Self-Healing)
- [ ] `Auditor` process receives a copy of the signed manifest at upload time
- [ ] Implement heartbeat loop: ping all nodes, update `nodeStatus` map
- [ ] Implement audit loop: `GETHASH` all shards → compare against manifest
- [ ] Implement repair: on degraded shard, fetch `k` healthy shards → RS reconstruct → re-upload
- [ ] Add repair lock: alert (log + throw) if `degradedCount > n - k`
- [ ] Configurable audit interval (default: 30 seconds for demo)

### Phase 5 (Optional) — Object Store Layer
- [ ] Add a simple namespace/key-value API on top (`put(key, file)`, `get(key)`, `delete(key)`, `list()`)
- [ ] Store manifest alongside object metadata in a local SQLite DB or JSON file
- [ ] Pitch as a full-fledged fault-tolerant object store

---

## Demo Script

### 1. Normal Flow
```
upload largefile.bin
delete largefile.bin locally
download largefile.bin
diff original vs downloaded → identical
```

### 2. Node Failure (Crash Tolerance)
```
start 6 storage nodes
upload file (6 shards)
kill node 3 and node 5
download file → still succeeds (only 2 of 6 nodes lost; tolerance = 2 for (6,4))
```

### 3. Integrity Failure — "The Wow Moment"
```
start 6 nodes + Auditor
upload file
using hex editor or script: flip a byte in shard_2.bin on node 2's disk
wait for Auditor's next audit cycle
observe logs:
  [AUDIT] shard_2 hash mismatch on node 2 — expected abc123, got def456
  [REPAIR] fetching 4 healthy shards from nodes 1,3,4,5
  [REPAIR] reconstructing shard_2 via Reed-Solomon
  [REPAIR] re-uploading shard_2 to node 2
download file → succeeds; original data fully restored
```

---

## Project Structure (Suggested)

```
distributed-object-store/
├── storage-node/
│   └── src/main/java/
│       └── StorageNode.java          # TCP server, STORE / RETRIEVE / GETHASH
├── client/
│   └── src/main/java/
│       ├── Client.java               # Upload / download entry point
│       ├── ReedSolomonHelper.java    # Wraps Backblaze RS library
│       ├── ShardManifest.java        # fileId → List<ShardMeta(hash, nodeAddr)>
│       └── NodeConnection.java       # Per-node socket wrapper
├── auditor/
│   └── src/main/java/
│       └── Auditor.java              # Heartbeat + audit + repair loops
├── common/
│   └── src/main/java/
│       ├── Protocol.java             # Wire format constants / helpers
│       └── HashUtil.java             # SHA-256 utility
└── scripts/
    ├── start-cluster.sh              # Launches N node processes on localhost
    ├── corrupt-shard.py              # Demo helper: flips a byte in a shard file
    └── demo.sh                       # Full demo sequence
```

---

## Design Decisions & Rationale

| Decision | Rationale |
|----------|-----------|
| Manifest is client-held, not server-held | Untrusted servers must not own the ground truth for integrity — that defeats the purpose |
| `GETHASH` recomputes on the fly | If the node stored hashes itself, a compromised node could return a fake hash for corrupted data |
| Hash mismatch → erasure (not error) | Reed-Solomon has a native erasure path; treating it as a separate error type is redundant and complicates decoding |
| Sockets over gRPC | Simpler to debug, easier to demo failures, no proto compilation step needed for coursework |
| Auditor uses repair lock | Prevents partial, inconsistent repair attempts that could leave the system in a worse state |

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Auditor is itself a SPOF | Out of scope for coursework; note it in writeup; real systems use Raft/Paxos for auditor HA |
| Concurrent uploads of same file | Use fileId + version in manifest; document as a known limitation |
| Repair window: second failure during repair | Repair lock ensures we halt and alert rather than producing inconsistent state |
| RS library API confusion (erasure vs. decode) | Write a unit test for the `(6,4)` round-trip with explicit erasure flags before integrating with networking |

---

## References

- Backblaze Reed-Solomon Java library: https://github.com/Backblaze/JavaReedSolomon
- Shacham & Waters, "Compact Proofs of Retrievability" (ASIACRYPT 2008) — the academic foundation this project builds on
- Plank et al., "A Performance Evaluation and Examination of Open-Source Erasure Coding Libraries for Storage" — good background on RS tradeoffs

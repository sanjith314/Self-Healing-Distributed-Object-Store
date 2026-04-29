# AGENTS.md — Distributed Object Store with Integrity

## Project Overview

Build a fault-tolerant, distributed object storage system that preserves data integrity even when data is stored on untrusted or failing servers. The system implements the **Shacham-Waters Proof of Retrievability (PoR)** scheme (CCS 2008) using **homomorphic PRF-based verification tags** for compact, challenge-based integrity proofs and a **manually-implemented GF(2⁸) Vandermonde erasure codec** for space-efficient redundancy. The Auditor can prove a node holds intact data *without downloading it*, by issuing a cryptographic challenge and verifying the aggregated response.

---

## Goals

- Store files across `n` storage nodes using a manual `(n, k)` systematic Vandermonde erasure codec over GF(2⁸) — **no external RS library**
- Attach a **PRF-based homomorphic verification tag** σᵢ to every block so the server can prove possession via a compact challenge-response
- Tolerate loss or corruption of any `n - k` blocks and still reconstruct the original file
- Run a background Auditor that **challenges** nodes with random linear queries and repairs degraded blocks autonomously
- Demonstrate the full Shacham-Waters "challenge → verify → reconstruct → re-deploy" self-repair loop

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
- Expose: `STORE(shardId, bytes, tag)`, `RETRIEVE(shardId) → bytes`, `CHALLENGE(indices, coefficients) → (μ[], σ)`
- Store each block's PRF tag σᵢ in a separate `.tag` file alongside the block data — nodes store what they receive without interpreting it
- Failure modes: crash (offline) or Byzantine (return altered bytes or forged proof)

### B. Client

1. **Encode**: Split file into `k` data blocks; generate `n - k` parity blocks via manual GF(2⁸) Vandermonde codec (`LinearErasureCodec`)
2. **Tag**: For each block `i`, split into `s` sectors and compute PRF tag: `σᵢ = f_k(i) + Σⱼ mᵢ,ⱼ · uⱼ (mod p)`. Store secret key `(k, α, u[])` locally — **never sent to nodes**
3. **Distribute**: Upload each block + its tag to a distinct Storage Node in parallel
4. **Retrieve**: Issue a `CHALLENGE` to each node first; nodes that fail the proof are treated as erasures. Fetch raw bytes only from nodes that passed. Reconstruct with `LinearErasureCodec.decode()`

> **Important**: The **POR Secret Key** and **Master Manifest** are **client-owned**. The Auditor acts only as an agent of the client using a read-only copy. Untrusted nodes hold blocks + tags but never the verification key — the key cannot be forged.

### C. Auditor

- Runs as a standalone background process
- Receives a **read-only copy of the Manifest + POR Secret Key** from the client at upload time
- Periodic audit loop:
  1. Ping each node — mark unresponsive nodes as `DOWN`
  2. Issue a random `CHALLENGE` to each live node; verify the response proof using the secret key
  3. On proof failure or missing node: fetch `k` healthy blocks, reconstruct missing block via `LinearErasureCodec`, recompute its tag, re-upload to a healthy (or replacement) node
- **Repair lock**: If more than `n - k` blocks are simultaneously degraded, the Auditor raises an alert rather than attempting partial repair. Do not attempt reconstruction when quorum is lost.

---

## Key Concepts

### Erasure Coding — Manual GF(2⁸) Vandermonde Codec `(n, k)`

| Symbol | Meaning |
|--------|---------|
| `k` | Number of original data blocks |
| `n` | Total blocks (data + parity) |
| `n - k` | Max blocks that can be lost/corrupted while still recovering |

**Default config**: `(6, 4)` — 4 data blocks + 2 parity blocks. Tolerates any 2 failures. Storage overhead: 1.5× (vs. 3× for full replication). Implemented entirely in `LinearErasureCodec` + `GaloisField256` — **no external library**.

The first `k` rows of the coding matrix are the identity (systematic code); parity rows are Vandermonde-style. Decoding selects any `k` healthy blocks, extracts the corresponding matrix rows, inverts via GF(2⁸) Gaussian elimination, and multiplies.

### Integrity Verification — Shacham-Waters PRF Tags

This project implements the **private-verifiability construction** from Shacham & Waters (CCS 2008):

**Key generation**: Client generates secret key `sk = (k_prf, α, u₁…uₛ)` where all values are in `Z_p` (`p = 2⁶¹ − 1`, a Mersenne prime).

**Tag computation** (upload): For each block `i` with `s` sectors `mᵢ,₁…mᵢ,ₛ ∈ Z_p`:
```
σᵢ = f_{k_prf}(i) + Σⱼ mᵢ,ⱼ · uⱼ   (mod p)
```
where `f_{k_prf}(i) = HMAC-SHA256(k_prf, i) mod p` is the PRF.

**Challenge**: Client sends a random subset `I ⊆ {1…n}` with random coefficients `νᵢ ∈ Z_p`.

**Response** (server computes): `μⱼ = Σᵢ∈I νᵢ · mᵢ,ⱼ` and `σ = Σᵢ∈I νᵢ · σᵢ`.

**Verification** (client checks): `σ == Σᵢ∈I νᵢ · f_k(i) + Σⱼ μⱼ · uⱼ   (mod p)`

The homomorphic property means a server cannot fabricate a valid response unless it holds all challenged blocks intact. A failed challenge → block treated as **erasure** for `LinearErasureCodec.decode()`.

---

## Technical Stack

| Concern | Library / API |
|---------|---------------|
| Language | Java 17+ |
| Erasure Coding | Manual GF(2⁸) Vandermonde codec — `LinearErasureCodec` + `GaloisField256` (no external library) |
| PoR Tags | `PorTagEngine` — HMAC-SHA256 PRF + Z_p arithmetic (`Math.multiplyHigh` for 128-bit multiply) |
| Networking | Java `ServerSocket` / `Socket` |
| Integrity | Shacham-Waters challenge-response; `javax.crypto.Mac` (HMAC-SHA256) as PRF |
| Concurrency | `CompletableFuture` + `ExecutorService` for parallel node I/O |
| Serialization | Plain byte arrays over sockets |

> **Networking choice**: Plain Java sockets — easier to debug, easier to demo partial failures (just kill the process), no external dependencies.

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

### Phase 3 — Manual Erasure Codec & Multi-Node Distribution ✅ COMPLETE
- [x] Implement `LinearErasureCodec` (manual GF(2⁸) Vandermonde) — unit test `encode → decode` round-trip locally
- [x] `Client.upload(file)`: split into `k` data blocks → encode `n - k` parity blocks → upload block `i` to node `i`
- [x] `Client.download(fileId)`: fetch all reachable blocks in parallel → verify hashes → pass erasure flags to decoder → reconstruct
- [x] Test with one node offline; confirm successful reconstruction

### Phase 3B — Wire `LinearErasureCodec`, Remove Backblaze RS & Add PRF Tags ✅ COMPLETE

> **Do steps 1–3 first and confirm tests pass before touching any PoR code.**
> `ErasureClient` currently calls `ReedSolomonHelper` (Backblaze) even though `LinearErasureCodec`
> already exists. Steps 1–3 close that gap without changing any observable behaviour.

#### Step 1 — Swap `ErasureClient` to use `LinearErasureCodec`
- [x] In `ErasureClient.upload()`: replace `ReedSolomonHelper.encode(data, dataShards, parityShards)` with `new LinearErasureCodec(dataShards, parityShards).encode(data)` → use `EncodedBlock.encodedFragments()`
- [x] In `ErasureClient.download()`: replace `ReedSolomonHelper.decode(shards, present, k, p, len)` with `codec.decode(fragments, present, originalLength)`
- [x] Replace all `ReedSolomonHelper.InsufficientShardsException` references with `LinearErasureCodec.InsufficientFragmentsException`
- [x] Replace all `ReedSolomonHelper.DATA_SHARDS` / `PARITY_SHARDS` / `TOTAL_SHARDS` constants with `LinearErasureCodec.SOURCE_FRAGMENTS` / `PARITY_FRAGMENTS` / `TOTAL_FRAGMENTS`
- [x] Replace all `ReedSolomonHelper.TOTAL_SHARDS` references in `Client.java` with `LinearErasureCodec.TOTAL_FRAGMENTS`
- [x] **Run all existing tests** (`LinearErasureCodecTest`, `HashVerificationTest`, `ReedSolomonHelperTest` integration) — all must pass with `LinearErasureCodec` driving the encode/decode path

#### Step 2 — Delete Backblaze RS
- [x] Delete `client/src/main/java/com/objectstore/client/ReedSolomonHelper.java`
- [x] Delete `libs/JavaReedSolomon/` directory entirely
- [x] Remove the Backblaze `<dependency>` and any `<module>` or `<systemPath>` entries from `client/pom.xml` and the root `pom.xml`
- [x] Delete `client/src/test/java/com/objectstore/client/ReedSolomonHelperTest.java` (tests a deleted class)
- [x] Confirm the project compiles cleanly with no Backblaze imports remaining (`grep -r "backblaze" .` should return nothing)

#### Step 3 — Remove SHA-256 Hash Verification from Download Path
- [x] Remove `manifest.verify(idx, bytes)` call inside `ErasureClient.download()` (SHA-256 check will be superseded by the PoR challenge in Phase 4)
- [x] Remove `manifest.registerShard(i, shards[i])` SHA-256 registration from `ErasureClient.upload()` — tags replace hashes
- [x] Keep `ShardManifest.shardId()`, `getNodeAddress()`, `getDataShards()`, `getTotalShards()`, `getOriginalFileLength()` — these are still needed
- [x] **Run integration test** (`rs-demo` / `por-demo`): upload → download → byte-compare must still pass

#### Step 4 — Implement PRF Key & Tag Engine
- [x] Create `PorSecretKey.java` — immutable record holding `byte[] prf_k` (32 bytes), `long alpha`, `long[] u` (one per sector); `generate()` uses `SecureRandom`; `save(Path)` / `load(Path)` serialise as JSON or a simple binary format
- [x] Create `PorTag.java` — record `(int blockIndex, long sigma)` where `sigma ∈ Z_p`
- [x] Create `PorTagEngine.java`:
  - `prf(byte[] k, int i) → long` — `HMAC-SHA256(k, i)` reduced mod p (`p = 2^61 - 1`)
  - `toSectors(byte[] fragment, int s) → long[]` — splits fragment bytes into `s` sector values in Z_p
  - `computeTag(PorSecretKey sk, int blockIndex, byte[] fragmentBytes) → long` — `f_k(i) + Σⱼ m_{i,j}·u_j mod p`
  - `addMod(long, long)` and `mulMod(long, long)` helpers using `Math.multiplyHigh` for safe 128-bit arithmetic
- [x] Unit test `PorTagEngine`: known PRF vector, known tag computation, verify equation `σ == f_k(i) + Σⱼ m_j·u_j (mod p)` holds

#### Step 5 — Wire Tags into Upload & Manifest
- [x] Update `ShardManifest`: remove `Map<ShardKey, String> hashMap`; add `List<PorTag> tags` and `PorSecretKey secretKey`; update `registerShard()` → `registerTag(int blockIndex, long sigma)`; update `save()`/`load()` to persist key + tags (manifest file stays local — never sent to nodes)
- [x] Update `ErasureClient.upload()`: after encoding, for each fragment `i` call `PorTagEngine.computeTag(sk, i, fragment)` → store in manifest via `manifest.registerTag(i, sigma)`; pass `sigma` to the node STORE command (extended wire format — see Phase 4 Step 1)
- [x] Update `Client.java` sub-command names: `rs-upload` → `por-upload`, `rs-download` → `por-download`, `rs-demo` → `por-demo`; save secret key alongside manifest as `<fileId>.porkey`

#### Step 6 — Extend StorageNode STORE to Accept Tags
- [x] Extend `Protocol.CMD_STORE` wire format: after the shard bytes, append `[8 bytes] sigma (big-endian long)` — nodes that receive an old STORE without sigma fall back gracefully (0 tag)
- [x] In `StorageNode.handleStore()`: read the `sigma` long from the wire; write it to `<shardId>.tag` (8 bytes) alongside `<shardId>.data`
- [x] Unit test (`StorageNodeTagTest`): STORE a fragment + tag (including zero-sigma and max-sigma edge cases), read back `.tag` file, confirm decoded long matches sent sigma

#### Step 7 — End-to-End Tag Round-Trip Test
- [x] Integration test (`PorTagRoundTripTest`): encode a file with `LinearErasureCodec`, compute tags with `PorTagEngine`, STORE all fragments to a live embedded node, read back each `.tag` file, verify (a) bytes decode to the sent sigma and (b) tag equation `σ == f_k(i) + Σⱼ m_{i,j}·u_j (mod p)` holds for every block — proves the full upload path is self-consistent before Phase 4 challenge wiring begins
- [x] Additional supporting changes made during Step 7:
  - Added `storage-node` as a `test`-scoped dependency in `client/pom.xml` to allow embedded node startup in tests
  - Added missing `ShardManifest` methods required by `FingerprintedCrossChecksumTest`: `setCodingMetadata(int, int, long, int)`, `setFingerprintCrossChecksum()`, `getFingerprintCrossChecksum()`, `getSourceFragments()`, `getTotalFragments()`, `verifyFragment(int, byte[], LinearErasureCodec)` — also extended `save()`/`load()` to persist FPCC fields
  - Fixed pre-existing test data bug in `FingerprintedCrossChecksumTest.mixedObjectFragment_isRejected` (original `"object A"` / `"object B"` strings share identical first three fragments under the 4-shard codec; replaced with clearly distinct data)
  - All 47 tests pass: `HashUtilTest` (8), `FingerprintedCrossChecksumTest` (4), `StorageNodeTagTest` (3), `HomomorphicFingerprintTest` (3), `PorTagEngineTest` (14), `PorTagRoundTripTest` (3), `HashVerificationTest` (14), `LinearErasureCodecTest` (6)

### Phase 4 — Challenge-Response Protocol
- [ ] Add `CMD_CHALLENGE = 0x04` to `Protocol.java`; add challenge wire-format helpers
- [ ] Implement `StorageNode` CHALLENGE handler: load blocks + tags for challenged indices, compute `(μⱼ, σ_agg)`, return proof
- [ ] Implement `PorChallengeEngine.generateChallenge(n, l)` — pick `l` random indices + random `νᵢ`
- [ ] Implement `PorChallengeEngine.verify(sk, challenge, proof)` — check `σ == Σ νᵢ·f_k(i) + Σⱼ μⱼ·uⱼ (mod p)`
- [ ] Modify `ErasureClient.download()`: challenge-first (failed proof → erasure), retrieve-second, decode with `LinearErasureCodec`
- [ ] Add `NodeConnection.challenge()` method
- [ ] Unit test: full challenge round-trip; corrupt one block's tag → verify challenge fails

### Phase 5 — Auditor (Self-Healing via PoR)
- [ ] `Auditor` process receives read-only copy of `ShardManifest` + `PorSecretKey` from client at upload time
- [ ] Heartbeat loop: ping all nodes, update `nodeStatus` map
- [ ] Audit loop: issue `CHALLENGE` to each live node → verify proof with `PorChallengeEngine`
- [ ] Repair: on failed proof or down node, fetch `k` healthy blocks → `LinearErasureCodec` reconstruct → recompute tag → re-upload
- [ ] Repair lock: alert (log + throw) if `degradedCount > n - k`
- [ ] Configurable audit interval (default: 30 seconds for demo)

### Phase 6 (Optional) — Object Store Layer
- [ ] Add a simple namespace/key-value API on top (`put(key, file)`, `get(key)`, `delete(key)`, `list()`)
- [ ] Store manifest + POR key alongside object metadata in a local SQLite DB or JSON file
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
upload file (blocks + PRF tags stored on nodes)
using hex editor or script: flip a byte in shard_2.data on node 2's disk
wait for Auditor's next audit cycle
observe logs:
  [AUDIT] Challenging node 2 with 10 random block indices...
  [AUDIT] Proof FAILED on node 2 — σ mismatch (expected 0x..., got 0x...)
  [REPAIR] fetching 4 healthy blocks from nodes 1,3,4,5
  [REPAIR] reconstructing block_2 via LinearErasureCodec (GF(256) Vandermonde)
  [REPAIR] recomputing PRF tag σ_2 for reconstructed block
  [REPAIR] re-uploading block_2 + σ_2 to node 2
download file → succeeds; original data fully restored
```

---

## Project Structure (Suggested)

```
distributed-object-store/
├── storage-node/
│   └── src/main/java/com/objectstore/node/
│       └── StorageNode.java              # TCP server: STORE / RETRIEVE / GETHASH; writes .tag files
├── client/
│   ├── src/main/java/com/objectstore/client/
│   │   ├── Client.java                   # Upload / download entry point (por-* sub-commands)
│   │   ├── ErasureClient.java            # Multi-node upload/download engine
│   │   ├── LinearErasureCodec.java       # Manual GF(2^8) Vandermonde (n,k) codec
│   │   ├── GaloisField256.java           # GF(2^8) arithmetic (add/mul/inv/matrix)
│   │   ├── PorSecretKey.java             # sk = (k_prf, α, u[]) in Z_p; save()/load()
│   │   ├── PorTag.java                   # (blockIndex, σ) value object
│   │   ├── PorTagEngine.java             # PRF f_k(i), computeTag(), Z_p arithmetic
│   │   ├── FingerprintedCrossChecksum.java  # SHA-256 + homomorphic fingerprint integrity
│   │   ├── HomomorphicFingerprint.java   # GF(2^8) linear fingerprint engine
│   │   ├── ShardManifest.java            # fileId → List<PorTag> + PorSecretKey + FPCC; save()/load()
│   │   └── NodeConnection.java           # Per-node socket wrapper (store with sigma)
│   └── src/test/java/com/objectstore/client/
│       ├── StorageNodeTagTest.java        # Step 6: STORE + .tag file round-trip (3 tests)
│       ├── PorTagRoundTripTest.java       # Step 7: encode→tag→store→verify equation (3 tests)
│       ├── PorTagEngineTest.java          # PRF, tag equation, Z_p arithmetic (14 tests)
│       ├── LinearErasureCodecTest.java    # Encode/decode round-trips (6 tests)
│       ├── FingerprintedCrossChecksumTest.java  # FPCC verify + manifest persistence (4 tests)
│       ├── HomomorphicFingerprintTest.java      # Fingerprint correctness (3 tests)
│       └── HashVerificationTest.java      # SHA-256 manifest verify (14 tests)
├── auditor/                              # (Phase 5 — not yet implemented)
├── common/
│   └── src/main/java/com/objectstore/common/
│       ├── Protocol.java                 # Wire format constants
│       └── HashUtil.java                 # SHA-256 / bytesToHex utilities
└── scripts/
    ├── start-cluster.sh                  # Launches N node processes on localhost
    ├── corrupt-shard.py                  # Demo helper: flips a byte in a shard file
    └── demo.sh                           # Full demo sequence
```

---

## Design Decisions & Rationale

| Decision | Rationale |
|----------|-----------|
| Manifest + POR secret key is client-held, never on nodes | Untrusted servers cannot own the verification key — a compromised node with the key could forge valid proofs |
| PRF tags stored on nodes, key held only by client | The tag σᵢ is useless to an attacker without α and u[]; a Byzantine node cannot recompute a valid tag for corrupted data |
| Challenge-response instead of GETHASH + download | Shacham-Waters: the server proves possession without the auditor downloading any data — O(1) communication per audit regardless of file size |
| Manual GF(2⁸) Vandermonde codec, no Backblaze library | Project requirement: erasure coding must be hand-coded to demonstrate understanding of the algebra |
| Failed challenge → erasure (not error) | Uniform path: both node-down and Byzantine-proof-failure feed the same `present[i]=false` mask into `LinearErasureCodec.decode()` |
| p = 2⁶¹ − 1 (Mersenne prime) | Fits in Java `long`; fast reduction; `Math.multiplyHigh` enables safe 128-bit multiply without BigInteger overhead |
| Sockets over gRPC | Simpler to debug, easier to demo failures, no proto compilation step needed for coursework |
| Auditor uses repair lock | Prevents partial, inconsistent repair attempts that could leave the system in a worse state |

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Auditor is itself a SPOF | Out of scope for coursework; note it in writeup; real systems use Raft/Paxos for auditor HA |
| Concurrent uploads of same file | Use fileId + version in manifest; document as a known limitation |
| Repair window: second failure during repair | Repair lock ensures we halt and alert rather than producing inconsistent state |
| 128-bit multiply overflow in Z_p | Use `Math.multiplyHigh` (Java 9+) for safe multiply; unit test with known vectors at boundary values |
| PRF output bias mod p | Accept negligible bias (< 2⁻⁵²) from HMAC-SHA256 output; document in writeup |
| Secret key loss = data permanently unverifiable | Document: save `.porkey` file to multiple offline locations alongside the manifest |

---

## References

- Shacham & Waters, "Compact Proofs of Retrievability" (ACM CCS 2008) — the primary academic foundation; implements the private-verifiability PRF construction
- Juels & Kaliski, "PORs: Proofs of Retrievability for Large Files" (ACM CCS 2007) — original PoR definition
- Plank et al., "A Performance Evaluation and Examination of Open-Source Erasure Coding Libraries for Storage" — background on GF(2⁸) Vandermonde codes

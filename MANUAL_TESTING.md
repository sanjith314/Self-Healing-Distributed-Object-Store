# Manual Testing Guide — Self-Healing Distributed Object Store

> Run all commands from the **repo root**: `/Users/sanjithjayasankar/Documents/gitrepo/Self-Healing-Distributed-Object-Store`

---

## Prerequisites

> [!IMPORTANT]
> You need Java 17+ and Maven installed. Check with:
> ```bash
> java -version   # must be 17+
> mvn -version
> nc -h           # netcat — used by start-cluster.sh readiness probe
> ```

---

## Step 0 — Build the Project

```bash
cd /Users/sanjithjayasankar/Documents/gitrepo/Self-Healing-Distributed-Object-Store
mvn clean package -DskipTests
```

Expected output: `BUILD SUCCESS` and two JARs produced:
- `storage-node/target/storage-node.jar`
- `client/target/client.jar`

---

## Step 1 — Create a Sample Text File

```bash
cat > sample.txt << 'EOF'
Hello from the Self-Healing Distributed Object Store!

This file will be split into 4 data shards + 2 parity shards
using a manual GF(2^8) Vandermonde erasure codec.

Even if we kill 2 of the 6 nodes, the system will reconstruct
the full file using the surviving 4 shards.

Cryptographic PRF tags (Shacham-Waters PoR) are attached to
each shard, allowing integrity verification without downloading
the full data — just a compact challenge-response proof.

The "wow moment": corrupt a shard byte on disk, and the system
detects it via the homomorphic tag mismatch.
EOF
```

Verify the file:
```bash
wc -c sample.txt   # should be ~500+ bytes
cat sample.txt
```

---

## Step 2 — Start the 6-Node Cluster

```bash
./scripts/start-cluster.sh
```

Expected output:
```
[cluster] Starting 6 StorageNode(s) from port 7100...
  Node 0: port=7100  data=./cluster-data/node-7100  log=./cluster-data/node-7100.log
  ...
  ✅  localhost:7100 — READY
  ✅  localhost:7101 — READY
  ✅  localhost:7102 — READY
  ✅  localhost:7103 — READY
  ✅  localhost:7104 — READY
  ✅  localhost:7105 — READY

✅  Cluster of 6 nodes is up.
PIDs saved to: /tmp/objectstore-pids.txt
```

> [!TIP]
> To view what each node is doing in real time, tail a log file:
> ```bash
> tail -f cluster-data/node-7100.log
> ```

---

## Step 3 — Upload the File (Encode + Distribute)

```bash
java -jar client/target/client.jar por-upload \
  sample.txt \
  sample \
  localhost:7100 localhost:7101 localhost:7102 \
  localhost:7103 localhost:7104 localhost:7105
```

**What happens:**
1. `sample.txt` is read (e.g. ~512 bytes)
2. `LinearErasureCodec` splits it into **4 data fragments + 2 parity fragments** (6 total)
3. A `PorSecretKey` is generated: `sk = (k_prf, α, u[1..s])`
4. A PRF tag `σᵢ = f_k(i) + Σⱼ mᵢ,ⱼ·uⱼ (mod p)` is computed for every fragment
5. All 6 fragments + tags are uploaded **in parallel** to nodes 7100–7105
6. `sample.manifest` and `sample.porkey` are saved locally

Expected output:
```
============================================================
 Phase 3B — POR Upload
============================================================
  File        : .../sample.txt
  File ID     : sample
  Codec params: (4 data + 2 parity = 6 total fragments)

[1/3] Read 512 bytes from disk.
[1/3] Encoded → 6 fragments of 128 bytes each (50.0% overhead).
[2/3] Manifest built: 6 tags computed.
[3/3] Uploading 6 shards in parallel...
  ✅ fragment 0 → localhost:7100  (128 bytes, σ=...)
  ✅ fragment 1 → localhost:7101  (128 bytes, σ=...)
  ✅ fragment 2 → localhost:7102  (128 bytes, σ=...)
  ✅ fragment 3 → localhost:7103  (128 bytes, σ=...)
  ✅ fragment 4 → localhost:7104  (128 bytes, σ=...)
  ✅ fragment 5 → localhost:7105  (128 bytes, σ=...)

✅  Upload complete
```

Verify local artifacts were created:
```bash
ls -la sample.manifest sample.porkey
```

Verify shards landed on the nodes:
```bash
ls cluster-data/node-7100/sample/   # should show: shard-0, shard-0.tag
ls cluster-data/node-7101/sample/   # shard-1, shard-1.tag
# etc.
```

> [!NOTE]
> Each node stores **two** files per shard:
> - `shard-N` — the encoded fragment bytes
> - `shard-N.tag` — the 8-byte PRF sigma (σ) written as a big-endian long

---

## Step 4 — Happy Path Download (All Nodes Up)

```bash
java -jar client/target/client.jar por-download \
  sample \
  recovered_happy.txt
```

> The manifest is loaded automatically from `sample.manifest` — no need to pass node addresses again.

Expected output:
```
 Phase 3B — POR Download
  File ID    : sample
  Codec      : (need 4 of 6 fragments)

  ✅ fragment 0 [localhost:7100] — OK (128 bytes)
  ✅ fragment 1 [localhost:7101] — OK (128 bytes)
  ✅ fragment 2 [localhost:7102] — OK (128 bytes)
  ✅ fragment 3 [localhost:7103] — OK (128 bytes)
  ✅ fragment 4 [localhost:7104] — OK (128 bytes)
  ✅ fragment 5 [localhost:7105] — OK (128 bytes)

[2/3] Retrieved 6 / 6 fragments successfully.
[3/3] Running LinearErasureCodec reconstruction...

✅  Reconstruction complete — wrote 512 bytes to 'recovered_happy.txt'
```

Verify byte-for-byte equality:
```bash
diff sample.txt recovered_happy.txt && echo "✅ Files identical" || echo "❌ Files differ"
```

---

## Step 5 — Kill Two Nodes (Crash Tolerance Test)

### 5a — Find the PIDs for nodes 7102 and 7104

```bash
# Option A — kill by port (easiest)
lsof -ti tcp:7102 | xargs kill -9
lsof -ti tcp:7104 | xargs kill -9
```

Verify they are down:
```bash
nc -z localhost 7102 && echo "still up" || echo "✅ 7102 is DOWN"
nc -z localhost 7104 && echo "still up" || echo "✅ 7104 is DOWN"
```

### 5b — Download with 2 nodes dead

```bash
java -jar client/target/client.jar por-download \
  sample \
  recovered_after_kill.txt
```

Expected output — notice the two erasures, but reconstruction still succeeds:
```
  ⚠️  fragment 2 [localhost:7102] — UNREACHABLE (Connection refused)
  ⚠️  fragment 4 [localhost:7104] — UNREACHABLE (Connection refused)
  ✅ fragment 0 [localhost:7100] — OK (128 bytes)
  ✅ fragment 1 [localhost:7101] — OK (128 bytes)
  ✅ fragment 3 [localhost:7103] — OK (128 bytes)
  ✅ fragment 5 [localhost:7105] — OK (128 bytes)

[2/3] Retrieved 4 / 6 fragments successfully.
[3/3] Running LinearErasureCodec reconstruction...

✅  Reconstruction complete — wrote 512 bytes to 'recovered_after_kill.txt'
```

Verify:
```bash
diff sample.txt recovered_after_kill.txt && echo "✅ Reconstructed correctly despite 2 dead nodes!"
```

> [!IMPORTANT]
> The system tolerates exactly `n - k = 6 - 4 = 2` failures. Killing a **third** node will cause:
> ```
> LinearErasureCodec.InsufficientFragmentsException: Only 3 healthy fragments available; need 4 to reconstruct.
> ```

---

## Step 6 — Corrupt a Shard on Disk (Byzantine Node Test)

Restart the killed nodes first, or use remaining live ones and corrupt a live shard instead.

### 6a — Restart the cluster

```bash
kill $(cat /tmp/objectstore-pids.txt) 2>/dev/null; sleep 1
./scripts/start-cluster.sh
```

### 6b — Re-upload the file (fresh shards on all 6 nodes)

```bash
java -jar client/target/client.jar por-upload \
  sample.txt \
  sample \
  localhost:7100 localhost:7101 localhost:7102 \
  localhost:7103 localhost:7104 localhost:7105
```

### 6c — Corrupt two shards using the helper script

```bash
# Corrupt byte 0 of shard-1 on node 7101
python3 scripts/corrupt-shard.py cluster-data/node-7101/sample/shard-1

# Corrupt byte 0 of shard-3 on node 7103
python3 scripts/corrupt-shard.py cluster-data/node-7103/sample/shard-3
```

Expected output from the script:
```
[corrupt-shard] File    : cluster-data/node-7101/sample/shard-1
[corrupt-shard] Offset  : 0
[corrupt-shard] Before  : 0x48 (72)
[corrupt-shard] After   : 0xB7 (183)
[corrupt-shard] ✅  Shard corrupted — the Auditor should detect this.
```

### 6d — Try to download

> [!NOTE]
> **Current state (Phase 3B):** SHA-256 verification was removed in Phase 3B Step 3 (superseded by PoR challenge-response in Phase 4). This means the download will currently **succeed but return wrong data** if corrupted bytes are read — the corruption detection via PoR challenge-response is wired in Phase 4.
>
> To observe **current** behavior, test the shard file directly:

```bash
# See what's physically stored on disk vs. what was expected
xxd cluster-data/node-7101/sample/shard-1 | head -3
# The first byte will be the corrupted value (e.g. 0xB7 instead of 0x48)
```

To simulate what Phase 4 will do (PoR challenge catches it):
```bash
# Kill nodes 7101 and 7103 to simulate that the corrupted nodes are "erasures"
lsof -ti tcp:7101 | xargs kill -9
lsof -ti tcp:7103 | xargs kill -9

# Now download — treats them as erasures, reconstructs from the 4 healthy nodes
java -jar client/target/client.jar por-download \
  sample \
  recovered_after_corrupt.txt

diff sample.txt recovered_after_corrupt.txt && echo "✅ Recovered correctly!"
```

---

## Step 7 — Stop the Cluster

```bash
kill $(cat /tmp/objectstore-pids.txt)
rm -f /tmp/objectstore-pids.txt
echo "✅ Cluster stopped."
```

---

## Quick Reference — All Commands

| Action | Command |
|--------|---------|
| Build | `mvn clean package -DskipTests` |
| Start 6 nodes | `./scripts/start-cluster.sh` |
| Stop all nodes | `kill $(cat /tmp/objectstore-pids.txt)` |
| Upload file | `java -jar client/target/client.jar por-upload sample.txt sample localhost:7100 ... localhost:7105` |
| Download file | `java -jar client/target/client.jar por-download sample recovered.txt` |
| Full round-trip demo | `java -jar client/target/client.jar por-demo sample.txt sample localhost:7100 ... localhost:7105` |
| Kill node N | `lsof -ti tcp:71XX \| xargs kill -9` |
| Corrupt a shard | `python3 scripts/corrupt-shard.py cluster-data/node-71XX/sample/shard-N` |
| Tail node log | `tail -f cluster-data/node-71XX.log` |

---

## What Each File Does

| File | Purpose |
|------|---------|
| `sample.manifest` | Client-held map: fragment index → node address + PoR tag σᵢ + original file length |
| `sample.porkey` | Secret key `sk = (k_prf, α, u[])` — **never sent to nodes**; needed to verify proofs |
| `cluster-data/node-71XX/sample/shard-N` | Encoded fragment bytes stored on node |
| `cluster-data/node-71XX/sample/shard-N.tag` | 8-byte big-endian σᵢ stored alongside the shard |

---

## Phase 4 Preview (Not Yet Implemented)

Once Phase 4 (Challenge-Response) is wired:
- **Before** fetching shard bytes, the client will send a `CHALLENGE` to each node
- The node computes `σ_agg = Σ νᵢ·σᵢ` and `μⱼ = Σ νᵢ·mᵢ,ⱼ` and returns a compact proof
- The client verifies: `σ_agg == Σ νᵢ·f_k(i) + Σⱼ μⱼ·uⱼ (mod p)`
- **A failed challenge → treated as an erasure** — the corrupted shard above will be caught here without downloading it


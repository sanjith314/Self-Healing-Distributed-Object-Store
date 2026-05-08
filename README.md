# Distributed Object Store with Integrity

A fault-tolerant distributed storage system that keeps your files safe even when nodes go down or get corrupted. Files are split into fragments using erasure coding, spread across 6 storage nodes, and verified on every download using a Fingerprinted Cross-Checksum (FPCC) — based on Hendricks, Ganger & Reiter (PODC 2007).

It tolerates up to 2 node failures or corruptions and still reconstructs the original file perfectly.

---

## Requirements

You need Java 17+ and Maven.

Install Maven on Mac:
```bash
brew install maven
```

On Linux:
```bash
sudo apt install maven
```

---

## Just run the demo

```bash
./scripts/demo.sh
```
It builds everything, runs 4 tests, and cleans up. The tests cover a normal upload/download, recovering with 2 nodes dead, catching a corrupted fragment, and the full AVID-FP consensus protocol.

---

## Running manually

Build first:
```bash
mvn clean package -q
```

Start the 6 storage nodes:
```bash
./scripts/start-cluster.sh
```

Upload a file:
```bash
java -jar client/target/client.jar upload myfile.txt myfile \
  localhost:7100 localhost:7101 localhost:7102 localhost:7103 localhost:7104 localhost:7105
```

Download it back:
```bash
java -jar client/target/client.jar download myfile recovered.txt
```

Stop everything:
```bash
kill $(cat /tmp/objectstore-pids.txt)
```

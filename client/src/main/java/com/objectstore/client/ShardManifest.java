package com.objectstore.client;

import com.objectstore.common.HashUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client-held manifest that records the expected SHA-256 hash for every shard
 * belonging to a stored file.
 *
 * <h2>Trust model</h2>
 * <p>The manifest is <em>client-owned</em>. It is computed locally before
 * any data is sent to the untrusted storage nodes and is never stored on those
 * nodes. This is the critical design property described in {@code CLAUDE.md}:
 * the nodes cannot forge or modify the ground-truth integrity metadata because
 * they simply do not hold it.
 *
 * <h2>Shard addressing</h2>
 * <p>Each shard is identified by a {@code (fileId, shardIndex)} pair where:
 * <ul>
 *   <li>{@code fileId} — a unique identifier for the logical file
 *       (e.g. a UUID or a human-readable name)</li>
 *   <li>{@code shardIndex} — zero-based index of the shard within the file</li>
 * </ul>
 * The manifest translates these to a wire-level {@code shardId} string of the
 * form {@code "<fileId>/shard-<index>"} which maps naturally to a directory
 * hierarchy on the storage node's filesystem.
 *
 * <h2>Thread safety</h2>
 * <p>Instances are <em>not</em> thread-safe once published. They are intended
 * to be fully populated before sharing with the Auditor. In Phase 2 all access
 * is single-threaded.
 */
public class ShardManifest {

    /**
     * Immutable key that uniquely identifies one shard within the system.
     *
     * @param fileId     logical file identifier (non-null, non-empty)
     * @param shardIndex zero-based shard index (≥ 0)
     */
    public record ShardKey(String fileId, int shardIndex) {

        public ShardKey {
            Objects.requireNonNull(fileId, "fileId must not be null");
            if (fileId.isBlank()) throw new IllegalArgumentException("fileId must not be blank");
            if (shardIndex < 0)  throw new IllegalArgumentException("shardIndex must be >= 0");
        }

        /**
         * Returns the wire-level shard identifier used in storage node commands.
         * Format: {@code "<fileId>/shard-<index>"}
         */
        public String toShardId() {
            return fileId + "/shard-" + shardIndex;
        }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** fileId that every shard in this manifest belongs to. */
    private final String fileId;

    /** Maps ShardKey → expected SHA-256 hex digest. */
    private final Map<ShardKey, String> hashMap = new HashMap<>();

    // Phase 3 — Reed-Solomon coding parameters (set by ErasureClient at upload time)

    /** Number of data shards (k). -1 if not yet configured. */
    private int dataShards = -1;

    /** Total shard count (n = data + parity). -1 if not yet configured. */
    private int totalShards = -1;

    /** Original file length in bytes, before zero-padding for RS alignment. */
    private long originalFileLength = -1;

    /**
     * Ordered list of {@code "host:port"} strings, one per shard index.
     * {@code nodeAddresses.get(i)} is the node that holds shard {@code i}.
     * Populated by ErasureClient at upload time; used by the Auditor in Phase 4.
     */
    private final List<String> nodeAddresses = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Creates an empty manifest for the given file.
     *
     * @param fileId logical file identifier (non-null, non-empty)
     */
    public ShardManifest(String fileId) {
        Objects.requireNonNull(fileId, "fileId must not be null");
        if (fileId.isBlank()) throw new IllegalArgumentException("fileId must not be blank");
        this.fileId = fileId;
    }

    // -----------------------------------------------------------------------
    // Builder-style API (populate during upload)
    // -----------------------------------------------------------------------

    /**
     * Records the expected SHA-256 hash for a shard, computed from its raw bytes.
     *
     * <p>Call this for every shard <em>before</em> uploading it to the node.
     *
     * @param shardIndex  zero-based shard index
     * @param shardBytes  raw shard contents (used to compute the hash)
     * @return the computed hex digest (convenient for callers that want to log it)
     */
    public String registerShard(int shardIndex, byte[] shardBytes) {
        Objects.requireNonNull(shardBytes, "shardBytes must not be null");
        String hex = HashUtil.sha256Hex(shardBytes);
        hashMap.put(new ShardKey(fileId, shardIndex), hex);
        return hex;
    }

    // -----------------------------------------------------------------------
    // Verification API (used during download and by the Auditor)
    // -----------------------------------------------------------------------

    /**
     * Returns the expected SHA-256 hex digest for the specified shard.
     *
     * @param shardIndex zero-based shard index
     * @return expected hash hex string
     * @throws IllegalArgumentException if this shard index is not in the manifest
     */
    public String expectedHash(int shardIndex) {
        ShardKey key = new ShardKey(fileId, shardIndex);
        String hash = hashMap.get(key);
        if (hash == null) {
            throw new IllegalArgumentException(
                    "Shard index " + shardIndex + " not found in manifest for file '" + fileId + "'");
        }
        return hash;
    }

    /**
     * Verifies that the given bytes match the recorded SHA-256 hash for the shard.
     *
     * <p>If the hashes match, returns {@code true}. If they differ, returns
     * {@code false} — the caller should treat this shard as an <em>erasure</em>
     * for Reed-Solomon purposes (see {@code CLAUDE.md} — "mismatch = erasure flag").
     *
     * @param shardIndex   zero-based shard index
     * @param receivedBytes bytes received from the storage node
     * @return {@code true} if the hash matches; {@code false} on mismatch
     * @throws IllegalArgumentException if this shard index is not in the manifest
     */
    public boolean verify(int shardIndex, byte[] receivedBytes) {
        Objects.requireNonNull(receivedBytes, "receivedBytes must not be null");
        String expected = expectedHash(shardIndex);
        String actual   = HashUtil.sha256Hex(receivedBytes);
        return expected.equals(actual);
    }

    /**
     * Returns the wire-level shard ID for the given index.
     *
     * @param shardIndex zero-based shard index
     * @return shard ID string (e.g. {@code "my-file/shard-0"})
     */
    public String shardId(int shardIndex) {
        return new ShardKey(fileId, shardIndex).toShardId();
    }

    // -----------------------------------------------------------------------
    // Phase 3 — RS metadata setters (called once by ErasureClient at upload)
    // -----------------------------------------------------------------------

    /**
     * Records the Reed-Solomon coding parameters and original file length.
     * Must be called exactly once, before the manifest is shared with the Auditor.
     *
     * @param dataShards         number of data shards (k)
     * @param totalShards        total shards including parity (n)
     * @param originalFileLength original file size in bytes (before RS zero-padding)
     */
    public void setRsMetadata(int dataShards, int totalShards, long originalFileLength) {
        if (dataShards < 1 || totalShards <= dataShards) {
            throw new IllegalArgumentException(
                    "Invalid RS params: dataShards=" + dataShards + ", totalShards=" + totalShards);
        }
        if (originalFileLength < 0) {
            throw new IllegalArgumentException("originalFileLength must be >= 0");
        }
        this.dataShards         = dataShards;
        this.totalShards        = totalShards;
        this.originalFileLength = originalFileLength;
    }

    /**
     * Appends a node address for one shard (in shard-index order).
     * Call this once per shard during the upload loop.
     *
     * @param hostPort {@code "host:port"} string for the node hosting this shard
     */
    public void addNodeAddress(String hostPort) {
        Objects.requireNonNull(hostPort, "hostPort must not be null");
        nodeAddresses.add(hostPort);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Returns the file identifier this manifest belongs to. */
    public String getFileId() { return fileId; }

    /** Returns the number of shards registered in this manifest. */
    public int shardCount() { return hashMap.size(); }

    /** Returns the number of data shards (k). Returns -1 if RS metadata not yet set. */
    public int getDataShards() { return dataShards; }

    /** Returns the total shard count (n). Returns -1 if RS metadata not yet set. */
    public int getTotalShards() { return totalShards; }

    /** Returns the original file length in bytes (before RS zero-padding). */
    public long getOriginalFileLength() { return originalFileLength; }

    /**
     * Returns the {@code "host:port"} address of the node holding shard {@code index}.
     *
     * @param shardIndex zero-based shard index
     * @return host:port string
     * @throws IndexOutOfBoundsException if {@code shardIndex} is out of range
     */
    public String getNodeAddress(int shardIndex) {
        return nodeAddresses.get(shardIndex);
    }

    /** Returns an unmodifiable view of all node addresses (in shard-index order). */
    public List<String> getNodeAddresses() {
        return Collections.unmodifiableList(nodeAddresses);
    }

    /** Returns an unmodifiable snapshot of the internal hash map (useful for Auditor). */
    public Map<ShardKey, String> allEntries() {
        return Collections.unmodifiableMap(hashMap);
    }

    // -----------------------------------------------------------------------
    // Persistence — save / load (simple line-based text format)
    // -----------------------------------------------------------------------

    /**
     * Persists this manifest to a local file so it can be reloaded for
     * {@code rs-download} without keeping the JVM alive.
     *
     * <p>Format (one key=value per line, {@code #} comment lines ignored):
     * <pre>
     * fileId=...
     * dataShards=4
     * totalShards=6
     * originalFileLength=65536
     * nodeAddress.0=localhost:7100
     * ...
     * hash.0=&lt;sha256hex&gt;
     * ...
     * </pre>
     *
     * @param path file path to write to (parent directories must exist)
     * @throws IOException on write error
     */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent() == null
                ? path.toAbsolutePath().getParent() : path.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path))) {
            pw.println("# ShardManifest — auto-generated by the distributed object store client");
            pw.printf("fileId=%s%n", fileId);
            pw.printf("dataShards=%d%n", dataShards);
            pw.printf("totalShards=%d%n", totalShards);
            pw.printf("originalFileLength=%d%n", originalFileLength);
            for (int i = 0; i < nodeAddresses.size(); i++) {
                pw.printf("nodeAddress.%d=%s%n", i, nodeAddresses.get(i));
            }
            for (int i = 0; i < totalShards; i++) {
                ShardKey key = new ShardKey(fileId, i);
                String hash = hashMap.get(key);
                if (hash != null) {
                    pw.printf("hash.%d=%s%n", i, hash);
                }
            }
        }
    }

    /**
     * Loads a {@link ShardManifest} previously written by {@link #save(Path)}.
     *
     * @param path path to the manifest file
     * @return the loaded manifest
     * @throws IOException              on read error
     * @throws IllegalArgumentException if the file is malformed
     */
    public static ShardManifest load(Path path) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String fileId = null;
            int dataShards = -1, totalShards = -1;
            long originalFileLength = -1;
            List<String> nodeAddresses = new ArrayList<>();
            Map<Integer, String> hashes = new HashMap<>();

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();

                switch (key) {
                    case "fileId"             -> fileId = val;
                    case "dataShards"         -> dataShards = Integer.parseInt(val);
                    case "totalShards"        -> totalShards = Integer.parseInt(val);
                    case "originalFileLength" -> originalFileLength = Long.parseLong(val);
                    default -> {
                        if (key.startsWith("nodeAddress.")) {
                            nodeAddresses.add(val);
                        } else if (key.startsWith("hash.")) {
                            int idx = Integer.parseInt(key.substring("hash.".length()));
                            hashes.put(idx, val);
                        }
                    }
                }
            }

            if (fileId == null) throw new IllegalArgumentException("Manifest missing 'fileId'");
            ShardManifest m = new ShardManifest(fileId);
            m.setRsMetadata(dataShards, totalShards, originalFileLength);
            for (String addr : nodeAddresses) m.addNodeAddress(addr);
            for (Map.Entry<Integer, String> e : hashes.entrySet()) {
                // Register a placeholder so the hashMap contains the key,
                // then overwrite the hash directly.
                m.hashMap.put(new ShardKey(fileId, e.getKey()), e.getValue());
            }
            return m;
        }
    }

    @Override
    public String toString() {
        return "ShardManifest{fileId='" + fileId + "', shards=" + hashMap.size()
                + ", dataShards=" + dataShards + ", totalShards=" + totalShards
                + ", originalLength=" + originalFileLength + "}";
    }
}

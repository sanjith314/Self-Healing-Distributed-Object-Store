package com.objectstore.client;

import com.objectstore.common.HashUtil;

import java.util.Collections;
import java.util.HashMap;
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
    // Accessors
    // -----------------------------------------------------------------------

    /** Returns the file identifier this manifest belongs to. */
    public String getFileId() { return fileId; }

    /** Returns the number of shards registered in this manifest. */
    public int shardCount() { return hashMap.size(); }

    /** Returns an unmodifiable snapshot of the internal hash map (useful for Auditor). */
    public Map<ShardKey, String> allEntries() {
        return Collections.unmodifiableMap(hashMap);
    }

    @Override
    public String toString() {
        return "ShardManifest{fileId='" + fileId + "', shards=" + hashMap.size() + "}";
    }
}

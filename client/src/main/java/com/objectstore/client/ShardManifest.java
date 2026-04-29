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
 * Client-held manifest that records integrity metadata for every block fragment
 * belonging to a stored file.
 *
 * <h2>Trust model</h2>
 * <p>The manifest is <em>client-owned</em>. It is computed locally before
 * any data is sent to the untrusted storage nodes and is never stored on those
 * nodes. From Phase 3B onward it holds the PoR secret key and per-block tags
 * rather than SHA-256 hashes.
 *
 * <h2>Shard addressing</h2>
 * <p>Each fragment is identified by a {@code (fileId, shardIndex)} pair.
 * The manifest translates these to a wire-level {@code shardId} string of the
 * form {@code "<fileId>/shard-<index>"} which maps naturally to a directory
 * hierarchy on the storage node's filesystem.
 *
 * <h2>Thread safety</h2>
 * <p>Instances are <em>not</em> thread-safe once published. They are intended
 * to be fully populated before sharing with the Auditor.
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

    /** fileId that every fragment in this manifest belongs to. */
    private final String fileId;

    /** Maps ShardKey → expected SHA-256 hex digest (kept for Phase 2 compat). */
    private final Map<ShardKey, String> hashMap = new HashMap<>();

    // Phase 3B — PoR secret key and per-block tags

    /** PoR secret key (k_prf, alpha, u[]). Never sent to nodes. Null until upload. */
    private PorSecretKey secretKey;

    /** Per-block PRF tags. tags.get(i) holds sigma_i for block i. */
    private final List<PorTag> tags = new ArrayList<>();

    // Erasure coding parameters (set by ErasureClient at upload time)

    /** Number of data fragments (k). -1 if not yet configured. */
    private int dataShards = -1;

    /** Total fragment count (n = data + parity). -1 if not yet configured. */
    private int totalShards = -1;

    /** Original file length in bytes, before zero-padding for codec alignment. */
    private long originalFileLength = -1;

    /** Fragment size in bytes (set via {@link #setCodingMetadata}). -1 if not set. */
    private int fragmentSize = -1;

    /**
     * Ordered list of {@code "host:port"} strings, one per fragment index.
     * {@code nodeAddresses.get(i)} is the node that holds fragment {@code i}.
     */
    private final List<String> nodeAddresses = new ArrayList<>();

    /** Optional FingerprintedCrossChecksum (used by older Phase 3B integrity path). */
    private FingerprintedCrossChecksum fingerprintCrossChecksum;

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
    // PoR tag API (Phase 3B)
    // -----------------------------------------------------------------------

    /**
     * Stores the PoR secret key generated at upload time.
     *
     * @param sk the client's secret key (must not be null)
     */
    public void setSecretKey(PorSecretKey sk) {
        this.secretKey = Objects.requireNonNull(sk, "secretKey must not be null");
    }

    /** Returns the stored PoR secret key, or {@code null} if not yet set. */
    public PorSecretKey getSecretKey() { return secretKey; }

    /**
     * Records the PRF tag for one block fragment.
     *
     * @param blockIndex 0-based block index
     * @param sigma      computed tag value in Z_p
     */
    public void registerTag(int blockIndex, long sigma) {
        tags.add(new PorTag(blockIndex, sigma));
    }

    /**
     * Returns the tag for the given block index.
     *
     * @param blockIndex 0-based block index
     * @return the corresponding {@link PorTag}
     * @throws IllegalArgumentException if no tag is registered for this index
     */
    public PorTag getTag(int blockIndex) {
        return tags.stream()
                .filter(t -> t.blockIndex() == blockIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No PoR tag for block " + blockIndex));
    }

    /** Returns an unmodifiable view of all PoR tags (in registration order). */
    public List<PorTag> allTags() {
        return Collections.unmodifiableList(tags);
    }

    // -----------------------------------------------------------------------
    // Phase 3 — erasure coding metadata setters
    // -----------------------------------------------------------------------

    /**
     * Records the erasure coding parameters and original file length.
     * Must be called exactly once, before the manifest is shared with the Auditor.
     *
     * @param dataShards         number of data fragments (k)
     * @param totalShards        total fragments including parity (n)
     * @param originalFileLength original file size in bytes (before codec zero-padding)
     */
    public void setRsMetadata(int dataShards, int totalShards, long originalFileLength) {
        if (dataShards < 1 || totalShards <= dataShards) {
            throw new IllegalArgumentException(
                    "Invalid codec params: dataShards=" + dataShards + ", totalShards=" + totalShards);
        }
        if (originalFileLength < 0) {
            throw new IllegalArgumentException("originalFileLength must be >= 0");
        }
        this.dataShards         = dataShards;
        this.totalShards        = totalShards;
        this.originalFileLength = originalFileLength;
    }

    /**
     * Alternative to {@link #setRsMetadata} that also records the per-fragment byte size.
     * Used by the {@code FingerprintedCrossChecksum} integrity path.
     *
     * @param sourceFragments    number of data/source fragments (k)
     * @param totalFragments     total fragments including parity (n)
     * @param originalFileLength original file size in bytes
     * @param fragmentSize       size of each fragment in bytes
     */
    public void setCodingMetadata(int sourceFragments, int totalFragments,
                                  long originalFileLength, int fragmentSize) {
        setRsMetadata(sourceFragments, totalFragments, originalFileLength);
        this.fragmentSize = fragmentSize;
    }

    /**
     * Stores a {@link FingerprintedCrossChecksum} for this file.
     * Used by the homomorphic-fingerprint integrity path.
     *
     * @param fpcc the fingerprinted cross-checksum (must not be null)
     */
    public void setFingerprintCrossChecksum(FingerprintedCrossChecksum fpcc) {
        this.fingerprintCrossChecksum = Objects.requireNonNull(fpcc, "fpcc must not be null");
    }

    /** Returns the stored {@link FingerprintedCrossChecksum}, or {@code null} if not set. */
    public FingerprintedCrossChecksum getFingerprintCrossChecksum() {
        return fingerprintCrossChecksum;
    }

    /**
     * Verifies a fragment byte array against the stored {@link FingerprintedCrossChecksum}.
     *
     * @param fragmentIndex zero-based fragment index
     * @param fragmentBytes raw bytes of the candidate fragment
     * @param codec         the erasure codec used during encoding
     * @return {@code true} if the fragment passes both the hash and fingerprint checks
     * @throws IllegalStateException if no {@link FingerprintedCrossChecksum} has been set
     */
    public boolean verifyFragment(int fragmentIndex, byte[] fragmentBytes, LinearErasureCodec codec) {
        if (fingerprintCrossChecksum == null) {
            throw new IllegalStateException(
                    "No FingerprintedCrossChecksum set — call setFingerprintCrossChecksum() first");
        }
        return fingerprintCrossChecksum.verifyFragment(fragmentIndex, fragmentBytes, codec);
    }

    /**
     * Appends a node address for one fragment (in fragment-index order).
     * Call this once per fragment during the upload loop.
     *
     * @param hostPort {@code "host:port"} string for the node hosting this fragment
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

    /** Returns the number of fragments registered in this manifest. */
    public int shardCount() { return Math.max(hashMap.size(), tags.size()); }

    /** Returns the number of data fragments (k). Returns -1 if codec metadata not yet set. */
    public int getDataShards() { return dataShards; }

    /** Alias for {@link #getDataShards()} — number of source/data fragments (k). */
    public int getSourceFragments() { return dataShards; }

    /** Returns the total fragment count (n). Returns -1 if codec metadata not yet set. */
    public int getTotalShards() { return totalShards; }

    /** Alias for {@link #getTotalShards()} — total fragment count (n). */
    public int getTotalFragments() { return totalShards; }

    /** Returns the per-fragment byte size, or -1 if not set via {@link #setCodingMetadata}. */
    public int getFragmentSize() { return fragmentSize; }

    /** Returns the original file length in bytes (before codec zero-padding). */
    public long getOriginalFileLength() { return originalFileLength; }

    /**
     * Returns the {@code "host:port"} address of the node holding fragment {@code index}.
     *
     * @param shardIndex zero-based fragment index
     * @return host:port string
     * @throws IndexOutOfBoundsException if {@code shardIndex} is out of range
     */
    public String getNodeAddress(int shardIndex) {
        return nodeAddresses.get(shardIndex);
    }

    /** Returns an unmodifiable view of all node addresses (in fragment-index order). */
    public List<String> getNodeAddresses() {
        return Collections.unmodifiableList(nodeAddresses);
    }

    /** Returns an unmodifiable snapshot of the internal hash map (for backward compat). */
    public Map<ShardKey, String> allEntries() {
        return Collections.unmodifiableMap(hashMap);
    }

    // -----------------------------------------------------------------------
    // Persistence — save / load (simple line-based text format)
    // -----------------------------------------------------------------------

    /**
     * Persists this manifest to a local file.
     *
     * <p>Format (one key=value per line, {@code #} comment lines ignored):
     * <pre>
     * fileId=...
     * dataShards=4
     * totalShards=6
     * originalFileLength=65536
     * nodeAddress.0=localhost:7100
     * ...
     * tag.0=&lt;sigma_0 decimal&gt;
     * ...
     * # porkey file is written separately as &lt;fileId&gt;.porkey
     * </pre>
     *
     * @param path file path to write to (parent directories will be created)
     * @throws IOException on write error
     */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent() == null
                ? path.toAbsolutePath().getParent() : path.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path))) {
            pw.println("# ShardManifest (Phase 3B PoR) — auto-generated");
            pw.printf("fileId=%s%n", fileId);
            pw.printf("dataShards=%d%n", dataShards);
            pw.printf("totalShards=%d%n", totalShards);
            pw.printf("originalFileLength=%d%n", originalFileLength);
            if (fragmentSize >= 0) pw.printf("fragmentSize=%d%n", fragmentSize);
            for (int i = 0; i < nodeAddresses.size(); i++) {
                pw.printf("nodeAddress.%d=%s%n", i, nodeAddresses.get(i));
            }
            // SHA-256 hashes (Phase 2 compat — may be empty)
            for (int i = 0; i < totalShards; i++) {
                ShardKey key = new ShardKey(fileId, i);
                String hash = hashMap.get(key);
                if (hash != null) pw.printf("hash.%d=%s%n", i, hash);
            }
            // PoR tags (Phase 3B)
            for (PorTag tag : tags) {
                pw.printf("tag.%d=%d%n", tag.blockIndex(), tag.sigma());
            }
            // FingerprintedCrossChecksum (optional, homomorphic path)
            if (fingerprintCrossChecksum != null) {
                pw.printf("fpcc.fingerprintBytes=%d%n", fingerprintCrossChecksum.fingerprintBytes());
                pw.printf("fpcc.seed=%s%n", HashUtil.bytesToHex(fingerprintCrossChecksum.seed()));
                String[] cc = fingerprintCrossChecksum.crossChecksum();
                for (int i = 0; i < cc.length; i++) pw.printf("fpcc.cc.%d=%s%n", i, cc[i]);
                byte[][] sfp = fingerprintCrossChecksum.sourceFingerprints();
                for (int i = 0; i < sfp.length; i++) {
                    pw.printf("fpcc.sfp.%d=%s%n", i, HashUtil.bytesToHex(sfp[i]));
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
            int dataShards = -1, totalShards = -1, fragSize = -1;
            long originalFileLength = -1;
            List<String> nodeAddresses = new ArrayList<>();
            Map<Integer, String> hashes = new HashMap<>();
            Map<Integer, Long> porTags = new HashMap<>();
            // FPCC fields
            int fpccFingerprintBytes = -1;
            byte[] fpccSeed = null;
            Map<Integer, String> fpccCc = new HashMap<>();
            Map<Integer, byte[]> fpccSfp = new HashMap<>();

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();

                switch (key) {
                    case "fileId"              -> fileId = val;
                    case "dataShards"          -> dataShards = Integer.parseInt(val);
                    case "totalShards"         -> totalShards = Integer.parseInt(val);
                    case "originalFileLength"  -> originalFileLength = Long.parseLong(val);
                    case "fragmentSize"        -> fragSize = Integer.parseInt(val);
                    case "fpcc.fingerprintBytes" -> fpccFingerprintBytes = Integer.parseInt(val);
                    case "fpcc.seed"           -> fpccSeed = hexToBytes(val);
                    default -> {
                        if (key.startsWith("nodeAddress.")) {
                            nodeAddresses.add(val);
                        } else if (key.startsWith("hash.")) {
                            int idx = Integer.parseInt(key.substring("hash.".length()));
                            hashes.put(idx, val);
                        } else if (key.startsWith("tag.")) {
                            int idx = Integer.parseInt(key.substring("tag.".length()));
                            porTags.put(idx, Long.parseLong(val));
                        } else if (key.startsWith("fpcc.cc.")) {
                            int idx = Integer.parseInt(key.substring("fpcc.cc.".length()));
                            fpccCc.put(idx, val);
                        } else if (key.startsWith("fpcc.sfp.")) {
                            int idx = Integer.parseInt(key.substring("fpcc.sfp.".length()));
                            fpccSfp.put(idx, hexToBytes(val));
                        }
                    }
                }
            }

            if (fileId == null) throw new IllegalArgumentException("Manifest missing 'fileId'");
            ShardManifest m = new ShardManifest(fileId);
            m.setRsMetadata(dataShards, totalShards, originalFileLength);
            if (fragSize >= 0) m.fragmentSize = fragSize;
            for (String addr : nodeAddresses) m.addNodeAddress(addr);
            for (Map.Entry<Integer, String> e : hashes.entrySet()) {
                m.hashMap.put(new ShardKey(fileId, e.getKey()), e.getValue());
            }
            for (Map.Entry<Integer, Long> e : porTags.entrySet()) {
                m.registerTag(e.getKey(), e.getValue());
            }
            // Reconstruct FPCC if all required fields are present
            if (fpccSeed != null && fpccFingerprintBytes > 0
                    && !fpccCc.isEmpty() && !fpccSfp.isEmpty()) {
                int n = fpccCc.size();
                int s = fpccSfp.size();
                String[] cc  = new String[n];
                for (Map.Entry<Integer, String> e : fpccCc.entrySet())  cc[e.getKey()]  = e.getValue();
                byte[][] sfp = new byte[s][];
                for (Map.Entry<Integer, byte[]> e : fpccSfp.entrySet()) sfp[e.getKey()] = e.getValue();
                m.fingerprintCrossChecksum =
                        new FingerprintedCrossChecksum(cc, sfp, fpccSeed, fpccFingerprintBytes);
            }
            return m;
        }
    }

    @Override
    public String toString() {
        return "ShardManifest{fileId='" + fileId + "', fragments=" + shardCount()
                + ", dataShards=" + dataShards + ", totalShards=" + totalShards
                + ", originalLength=" + originalFileLength
                + ", tags=" + tags.size()
                + ", hasKey=" + (secretKey != null) + "}";
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Decodes a lowercase hex string produced by {@link HashUtil#bytesToHex} back to bytes. */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}

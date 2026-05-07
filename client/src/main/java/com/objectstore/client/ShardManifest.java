package com.objectstore.client;

import com.objectstore.common.HashUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Client-held manifest that records metadata for a stored file's fragments.
 * 
 * This is client-owned and never stored on untrusted nodes. It tracks:
 *   - Which nodes hold which fragments
 *   - The FPCC for integrity verification
 *   - Erasure coding parameters (k, n, original file length)
 */
public class ShardManifest {

    private final String fileId;
    private int dataShards = -1;
    private int totalShards = -1;
    private long originalFileLength = -1;
    private int fragmentSize = -1;
    private final List<String> nodeAddresses = new ArrayList<>();
    private FingerprintedCrossChecksum fingerprintCrossChecksum;

    public ShardManifest(String fileId) {
        Objects.requireNonNull(fileId);
        if (fileId.isBlank()) throw new IllegalArgumentException("fileId must not be blank");
        this.fileId = fileId;
    }

    // -- Builder methods (called during upload) --

    /** Records erasure coding parameters. */
    public void setRsMetadata(int dataShards, int totalShards, long originalFileLength) {
        this.dataShards         = dataShards;
        this.totalShards        = totalShards;
        this.originalFileLength = originalFileLength;
    }

    /** Sets both coding metadata and fragment size. */
    public void setCodingMetadata(int sourceFragments, int totalFragments,
                                  long originalFileLength, int fragmentSize) {
        setRsMetadata(sourceFragments, totalFragments, originalFileLength);
        this.fragmentSize = fragmentSize;
    }

    /** Stores the FPCC for this file. */
    public void setFingerprintCrossChecksum(FingerprintedCrossChecksum fpcc) {
        this.fingerprintCrossChecksum = Objects.requireNonNull(fpcc);
    }

    /** Adds a node address for the next fragment (in order). */
    public void addNodeAddress(String hostPort) {
        nodeAddresses.add(Objects.requireNonNull(hostPort));
    }

    // -- Accessors --

    public String shardId(int shardIndex) {
        return fileId + "/shard-" + shardIndex;
    }

    public String getFileId()             { return fileId; }
    public int    getDataShards()         { return dataShards; }
    public int    getSourceFragments()    { return dataShards; }
    public int    getTotalShards()        { return totalShards; }
    public int    getTotalFragments()     { return totalShards; }
    public int    getFragmentSize()       { return fragmentSize; }
    public long   getOriginalFileLength() { return originalFileLength; }
    public String getNodeAddress(int i)   { return nodeAddresses.get(i); }
    public List<String> getNodeAddresses() { return Collections.unmodifiableList(nodeAddresses); }

    public FingerprintedCrossChecksum getFingerprintCrossChecksum() {
        return fingerprintCrossChecksum;
    }

    /** Verifies a fragment against the stored FPCC. */
    public boolean verifyFragment(int fragmentIndex, byte[] fragmentBytes, LinearErasureCodec codec) {
        if (fingerprintCrossChecksum == null)
            throw new IllegalStateException("No FPCC set");
        return fingerprintCrossChecksum.verifyFragment(fragmentIndex, fragmentBytes, codec);
    }

    // -- Save/Load (simple text format) --

    /** Saves the manifest to a file. */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path))) {
            pw.println("# ShardManifest");
            pw.printf("fileId=%s%n", fileId);
            pw.printf("dataShards=%d%n", dataShards);
            pw.printf("totalShards=%d%n", totalShards);
            pw.printf("originalFileLength=%d%n", originalFileLength);
            if (fragmentSize >= 0) pw.printf("fragmentSize=%d%n", fragmentSize);

            for (int i = 0; i < nodeAddresses.size(); i++)
                pw.printf("nodeAddress.%d=%s%n", i, nodeAddresses.get(i));

            // Save FPCC if present
            if (fingerprintCrossChecksum != null) {
                pw.printf("fpcc.fingerprintBytes=%d%n", fingerprintCrossChecksum.fingerprintBytes());
                pw.printf("fpcc.seed=%s%n", HashUtil.bytesToHex(fingerprintCrossChecksum.seed()));
                String[] cc = fingerprintCrossChecksum.crossChecksum();
                for (int i = 0; i < cc.length; i++) pw.printf("fpcc.cc.%d=%s%n", i, cc[i]);
                byte[][] sfp = fingerprintCrossChecksum.sourceFingerprints();
                for (int i = 0; i < sfp.length; i++)
                    pw.printf("fpcc.sfp.%d=%s%n", i, HashUtil.bytesToHex(sfp[i]));
            }
        }
    }

    /** Loads a manifest from a file. */
    public static ShardManifest load(Path path) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String fileId = null;
            int dataShards = -1, totalShards = -1, fragSize = -1;
            long originalFileLength = -1;
            List<String> nodes = new ArrayList<>();
            int fpccFpBytes = -1;
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
                    case "fpcc.fingerprintBytes" -> fpccFpBytes = Integer.parseInt(val);
                    case "fpcc.seed"           -> fpccSeed = hexToBytes(val);
                    default -> {
                        if (key.startsWith("nodeAddress.")) nodes.add(val);
                        else if (key.startsWith("fpcc.cc."))
                            fpccCc.put(Integer.parseInt(key.substring("fpcc.cc.".length())), val);
                        else if (key.startsWith("fpcc.sfp."))
                            fpccSfp.put(Integer.parseInt(key.substring("fpcc.sfp.".length())), hexToBytes(val));
                    }
                }
            }

            if (fileId == null) throw new IllegalArgumentException("Manifest missing 'fileId'");
            ShardManifest m = new ShardManifest(fileId);
            m.setRsMetadata(dataShards, totalShards, originalFileLength);
            if (fragSize >= 0) m.fragmentSize = fragSize;
            for (String addr : nodes) m.addNodeAddress(addr);

            // Reconstruct FPCC if present
            if (fpccSeed != null && fpccFpBytes > 0 && !fpccCc.isEmpty() && !fpccSfp.isEmpty()) {
                String[] cc = new String[fpccCc.size()];
                for (var e : fpccCc.entrySet()) cc[e.getKey()] = e.getValue();
                byte[][] sfp = new byte[fpccSfp.size()][];
                for (var e : fpccSfp.entrySet()) sfp[e.getKey()] = e.getValue();
                m.fingerprintCrossChecksum =
                        new FingerprintedCrossChecksum(cc, sfp, fpccSeed, fpccFpBytes);
            }
            return m;
        }
    }

    @Override
    public String toString() {
        return "ShardManifest{fileId='" + fileId + "', dataShards=" + dataShards
                + ", totalShards=" + totalShards + ", originalLength=" + originalFileLength + "}";
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}

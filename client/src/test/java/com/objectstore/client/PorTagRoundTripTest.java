package com.objectstore.client;

import com.objectstore.node.StorageNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 7 end-to-end tag round-trip integration test.
 *
 * <p>Proves the full upload path is self-consistent before Phase 4 challenge
 * wiring begins:
 * <ol>
 *   <li>Encode a file with {@link LinearErasureCodec}.</li>
 *   <li>Compute a PRF tag with {@link PorTagEngine} and a fresh {@link PorSecretKey}.</li>
 *   <li>STORE each fragment + tag to a live embedded {@link StorageNode}.</li>
 *   <li>Read back the {@code .tag} file for each block from the node's data directory.</li>
 *   <li>Verify:
 *     <ul>
 *       <li>The raw bytes in the {@code .tag} file decode to the same sigma that was sent.</li>
 *       <li>The tag equation {@code σ == f_k(i) + Σⱼ m_{i,j}·u_j (mod p)} holds for
 *           the fragment bytes that were stored — confirming the tag engine, the storage
 *           node, and the wire format are all self-consistent.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
class PorTagRoundTripTest {

    private static final int DATA_SHARDS   = LinearErasureCodec.SOURCE_FRAGMENTS;   // 4
    private static final int PARITY_SHARDS = LinearErasureCodec.PARITY_FRAGMENTS;   // 2
    private static final int TOTAL_SHARDS  = LinearErasureCodec.TOTAL_FRAGMENTS;    // 6

    // -----------------------------------------------------------------------
    // Step 7 — end-to-end tag round-trip
    // -----------------------------------------------------------------------

    @Test
    void endToEnd_tagRoundTrip_allShardsPass(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        // 1. Encode a representative file
        byte[] fileData = (
            "Step 7 integration test payload — Shacham-Waters PoR tag round-trip. " +
            "This string is long enough to span multiple codec sectors per fragment."
        ).getBytes(StandardCharsets.UTF_8);

        LinearErasureCodec codec   = new LinearErasureCodec(DATA_SHARDS, PARITY_SHARDS);
        LinearErasureCodec.EncodedBlock encoded = codec.encode(fileData);
        byte[][] fragments = encoded.encodedFragments();
        assertEquals(TOTAL_SHARDS, fragments.length, "Codec must produce exactly n fragments");

        // 2. Generate secret key and compute tags
        PorSecretKey sk    = PorSecretKey.generate();
        long[]       sigmas = new long[TOTAL_SHARDS];
        String       fileId = "por-roundtrip-test";

        for (int i = 0; i < TOTAL_SHARDS; i++) {
            sigmas[i] = PorTagEngine.computeTag(sk, i, fragments[i]);
        }

        // 3. STORE each fragment + tag to the live node
        for (int i = 0; i < TOTAL_SHARDS; i++) {
            String shardId = fileId + "/shard-" + i;
            try (NodeConnection conn = new NodeConnection("localhost", port)) {
                conn.store(shardId, fragments[i], sigmas[i]);
            }
        }

        // 4 & 5. For every block: read .tag file, verify bytes and tag equation
        for (int i = 0; i < TOTAL_SHARDS; i++) {
            String shardId = fileId + "/shard-" + i;

            // -- 4a. .tag file must exist --
            Path tagFile = tempDir.resolve(shardId + ".tag");
            assertTrue(Files.exists(tagFile),
                    "StorageNode must create a .tag file for shard " + i);

            // -- 4b. .tag bytes must decode to the sent sigma --
            byte[] tagBytes = Files.readAllBytes(tagFile);
            assertEquals(8, tagBytes.length,
                    ".tag file must be 8 bytes for shard " + i);
            long storedSigma = decodeLong(tagBytes);
            assertEquals(sigmas[i], storedSigma,
                    "Sigma stored in .tag file must equal sigma sent to STORE for shard " + i);

            // -- 5. tag equation must hold for the stored fragment --
            // Re-derive sigma from the raw fragment (same bytes that were STOREd)
            long recomputed = PorTagEngine.prf(sk.prf_k(), i);
            long[] sectors  = PorTagEngine.toSectors(fragments[i], sk.u().length);
            for (int j = 0; j < sectors.length; j++) {
                recomputed = PorTagEngine.addMod(recomputed,
                        PorTagEngine.mulMod(sectors[j], sk.u()[j]));
            }
            assertEquals(recomputed, storedSigma,
                    "Tag equation σ = f_k(i) + Σⱼ m_{i,j}·u_j (mod p) must hold for shard " + i);
        }
    }

    @Test
    void endToEnd_tagRoundTrip_smallFile(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        // Edge case: tiny file (1 byte) — codec zero-pads; tags must still be consistent
        byte[]        fileData  = new byte[]{0x42};
        LinearErasureCodec codec = new LinearErasureCodec(DATA_SHARDS, PARITY_SHARDS);
        byte[][] fragments = codec.encode(fileData).encodedFragments();

        PorSecretKey sk     = PorSecretKey.generate();
        String       fileId = "small-file-test";
        long[]       sigmas = new long[TOTAL_SHARDS];

        for (int i = 0; i < TOTAL_SHARDS; i++) {
            sigmas[i] = PorTagEngine.computeTag(sk, i, fragments[i]);
            try (NodeConnection conn = new NodeConnection("localhost", port)) {
                conn.store(fileId + "/shard-" + i, fragments[i], sigmas[i]);
            }
        }

        for (int i = 0; i < TOTAL_SHARDS; i++) {
            Path tagFile = tempDir.resolve(fileId + "/shard-" + i + ".tag");
            long stored  = decodeLong(Files.readAllBytes(tagFile));
            assertEquals(sigmas[i], stored,
                    "Small-file tag must round-trip for shard " + i);

            // Verify equation
            long expected = PorTagEngine.prf(sk.prf_k(), i);
            long[] sectors = PorTagEngine.toSectors(fragments[i], sk.u().length);
            for (int j = 0; j < sectors.length; j++) {
                expected = PorTagEngine.addMod(expected,
                        PorTagEngine.mulMod(sectors[j], sk.u()[j]));
            }
            assertEquals(expected, stored,
                    "Tag equation must hold even for zero-padded tiny file, shard " + i);
        }
    }

    @Test
    void endToEnd_tagRoundTrip_differentKeysProduceDifferentTags(@TempDir Path tempDir)
            throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        byte[] fileData = "Key-differentiation test payload for shard tags.".getBytes();
        LinearErasureCodec codec = new LinearErasureCodec(DATA_SHARDS, PARITY_SHARDS);
        byte[][] fragments = codec.encode(fileData).encodedFragments();

        PorSecretKey sk1 = PorSecretKey.generate();
        PorSecretKey sk2 = PorSecretKey.generate();

        long sigma1_0 = PorTagEngine.computeTag(sk1, 0, fragments[0]);
        long sigma2_0 = PorTagEngine.computeTag(sk2, 0, fragments[0]);

        // Two independently generated keys must (with overwhelming probability) differ
        assertNotEquals(sigma1_0, sigma2_0,
                "Different secret keys must produce different tags for the same fragment");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Starts a StorageNode in a daemon thread; polls until it accepts connections. */
    private static void startNodeBackground(int port, Path dataDir) throws InterruptedException {
        StorageNode node = new StorageNode(port, dataDir);
        Thread t = new Thread(() -> {
            try { node.start(); } catch (IOException e) { /* daemon exits cleanly */ }
        }, "test-storage-node-" + port);
        t.setDaemon(true);
        t.start();

        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            try (var s = new java.net.Socket("localhost", port)) {
                break;
            } catch (IOException ignored) {
                Thread.sleep(50);
            }
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static long decodeLong(byte[] bytes) {
        long v = 0;
        for (byte b : bytes) {
            v = (v << 8) | (b & 0xFFL);
        }
        return v;
    }
}

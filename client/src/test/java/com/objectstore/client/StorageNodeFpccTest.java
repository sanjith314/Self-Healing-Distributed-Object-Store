package com.objectstore.client;

import com.objectstore.common.GaloisField256;
import com.objectstore.common.HashUtil;
import com.objectstore.common.HomomorphicFingerprint;
import com.objectstore.common.Protocol;
import com.objectstore.node.StorageNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 Step 1 unit tests — verifies that {@link StorageNode} correctly
 * accepts a STORE with valid FPCC data and rejects one with corrupted bytes.
 *
 * <p>Spins up an embedded {@link StorageNode}, issues STORE commands via
 * {@link NodeConnection}, and checks the on-disk result.
 */
class StorageNodeFpccTest {

    // -----------------------------------------------------------------------
    // Test 1 — valid FPCC is accepted and data is written
    // -----------------------------------------------------------------------

    @Test
    void store_withValidFpcc_acceptsAndWritesData(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        byte[] fragment = "hello-fpcc-fragment".getBytes();
        Protocol.FpccFragmentData fpcc = buildFpcc(fragment);

        try (NodeConnection conn = new NodeConnection("localhost", port)) {
            conn.store("fpcc-test/shard-0", fragment, 0L, fpcc);
        }

        Path dataFile = tempDir.resolve("fpcc-test/shard-0");
        assertTrue(Files.exists(dataFile), "data file must be written on valid FPCC");
        assertArrayEquals(fragment, Files.readAllBytes(dataFile));
    }

    // -----------------------------------------------------------------------
    // Test 2 — corrupted fragment (wrong bytes) is rejected
    // -----------------------------------------------------------------------

    @Test
    void store_withCorruptedFragment_rejectsStore(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        byte[] original  = "correct-fragment-data-for-fpcc".getBytes();
        byte[] corrupted = original.clone();
        corrupted[0] ^= (byte) 0xFF;   // flip a bit

        // Build FPCC for the ORIGINAL bytes, but send the CORRUPTED bytes
        Protocol.FpccFragmentData fpcc = buildFpcc(original);

        try (NodeConnection conn = new NodeConnection("localhost", port)) {
            assertThrows(NodeConnection.StorageException.class,
                    () -> conn.store("fpcc-test/shard-corrupt", corrupted, 0L, fpcc),
                    "Node must reject corrupted fragment that fails FPCC hash check");
        }

        Path dataFile = tempDir.resolve("fpcc-test/shard-corrupt");
        assertFalse(Files.exists(dataFile), "data file must NOT be written when FPCC fails");
    }

    // -----------------------------------------------------------------------
    // Test 3 — no FPCC (null) is accepted for backward compat
    // -----------------------------------------------------------------------

    @Test
    void store_withNullFpcc_acceptsWithoutVerification(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        byte[] fragment = "legacy-no-fpcc-fragment".getBytes();

        try (NodeConnection conn = new NodeConnection("localhost", port)) {
            conn.store("fpcc-test/shard-legacy", fragment, 0L, null);
        }

        Path dataFile = tempDir.resolve("fpcc-test/shard-legacy");
        assertTrue(Files.exists(dataFile), "legacy store without FPCC must still be accepted");
        assertArrayEquals(fragment, Files.readAllBytes(dataFile));
    }

    // -----------------------------------------------------------------------
    // Test 4 — wrong fingerprint but correct hash is rejected
    // -----------------------------------------------------------------------

    @Test
    void store_wrongFingerprint_rejectsStore(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        byte[] fragment = "fragment-with-wrong-fp".getBytes();
        // Build valid FPCC but corrupt the expected fingerprint
        Protocol.FpccFragmentData validFpcc = buildFpcc(fragment);
        byte[] badFp = validFpcc.expectedFingerprint().clone();
        badFp[0] ^= (byte) 0xFF;
        Protocol.FpccFragmentData tampered = new Protocol.FpccFragmentData(
                validFpcc.fingerprintBytes(),
                validFpcc.seed(),
                validFpcc.expectedHash(),
                badFp);

        try (NodeConnection conn = new NodeConnection("localhost", port)) {
            assertThrows(NodeConnection.StorageException.class,
                    () -> conn.store("fpcc-test/shard-badfp", fragment, 0L, tampered),
                    "Node must reject fragment whose fingerprint does not match");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal {@link Protocol.FpccFragmentData} for a single standalone fragment.
     * For a standalone fragment (identity coding row = [1]), the expected fingerprint
     * equals the raw fragment fingerprint.
     */
    static Protocol.FpccFragmentData buildFpcc(byte[] fragment) {
        String hash = HashUtil.sha256Hex(fragment);
        // Derive seed from the single-element cross-checksum array
        byte[] seed = HomomorphicFingerprint.deriveSeed(new String[]{hash});
        int fpBytes = HomomorphicFingerprint.DEFAULT_FINGERPRINT_BYTES;
        HomomorphicFingerprint fp = new HomomorphicFingerprint(seed, fpBytes);
        // Identity coding row [1] → encodeFingerprint = scalarMultiply(fp, 1) = fp(fragment)
        byte[] codingRow = new byte[]{1};
        byte[][] sourceFps = new byte[][]{fp.fingerprint(fragment)};
        byte[] expectedFp = fp.encodeFingerprint(codingRow, sourceFps);
        return new Protocol.FpccFragmentData(fpBytes, seed, hash, expectedFp);
    }

    /** Starts a StorageNode in a daemon thread and waits until it accepts connections. */
    static void startNodeBackground(int port, Path dataDir) throws InterruptedException {
        StorageNode node = new StorageNode(port, dataDir);
        Thread t = new Thread(() -> {
            try { node.start(); } catch (IOException e) { /* daemon exits cleanly */ }
        }, "test-storage-node-" + port);
        t.setDaemon(true);
        t.start();

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (var s = new java.net.Socket("localhost", port)) {
                break;
            } catch (IOException ignored) {
                Thread.sleep(50);
            }
        }
    }

    /** Finds an ephemeral TCP port that is currently free. */
    static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}

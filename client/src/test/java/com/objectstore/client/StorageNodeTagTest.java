package com.objectstore.client;

import com.objectstore.node.StorageNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 6 unit test — verifies that {@link StorageNode} correctly writes the
 * 8-byte PoR tag (sigma) to a companion {@code .tag} file whenever a
 * {@code STORE} command is received with a sigma appended.
 *
 * <p>Approach: spin up an embedded {@code StorageNode} on a random free port,
 * STORE one fragment with a known sigma via {@link NodeConnection}, then read
 * the {@code <shardId>.tag} file from the node's data directory and confirm
 * the bytes decode to the original sigma value.
 */
class StorageNodeTagTest {

    // -----------------------------------------------------------------------
    // Step 6 — STORE writes correct bytes to .tag file
    // -----------------------------------------------------------------------

    @Test
    void store_writesTagFile_withCorrectSigmaBytes(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        String shardId = "unit-test/shard-0";
        byte[] fragment = "hello-world-fragment-step6".getBytes();
        long   sigma    = 0xDEADBEEFCAFEBABEL;

        try (NodeConnection conn = new NodeConnection("localhost", port)) {
            conn.store(shardId, fragment, sigma);
        }

        // The tag file lives at <dataDir>/<shardId>.tag
        Path tagFile = tempDir.resolve(shardId + ".tag");
        assertTrue(Files.exists(tagFile), ".tag file must be created by StorageNode");

        byte[] tagBytes = Files.readAllBytes(tagFile);
        assertEquals(8, tagBytes.length, ".tag file must be exactly 8 bytes");

        long readSigma = decodeLong(tagBytes);
        assertEquals(sigma, readSigma,
                "Sigma decoded from .tag file must equal the sigma sent in STORE");
    }

    @Test
    void store_zeroSigma_writesEightZeroBytes(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        String shardId = "unit-test/shard-1";
        byte[] fragment = "another-fragment".getBytes();

        // store() without sigma defaults to sigma=0
        try (NodeConnection conn = new NodeConnection("localhost", port)) {
            conn.store(shardId, fragment);
        }

        Path tagFile = tempDir.resolve(shardId + ".tag");
        assertTrue(Files.exists(tagFile), ".tag file must always be written");
        assertEquals(0L, decodeLong(Files.readAllBytes(tagFile)),
                "Default store (sigma=0) must write 8 zero bytes");
    }

    @Test
    void store_maxSigma_roundTrips(@TempDir Path tempDir) throws Exception {
        int port = findFreePort();
        startNodeBackground(port, tempDir);

        String shardId    = "unit-test/shard-max";
        byte[] fragment   = new byte[64];
        long   maxSigma   = PorSecretKey.P - 1;   // largest valid Z_p value

        try (NodeConnection conn = new NodeConnection("localhost", port)) {
            conn.store(shardId, fragment, maxSigma);
        }

        Path tagFile = tempDir.resolve(shardId + ".tag");
        assertEquals(maxSigma, decodeLong(Files.readAllBytes(tagFile)),
                "Max valid sigma (p-1) must survive the .tag round-trip");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Starts a StorageNode in a daemon thread and waits until it accepts connections. */
    private static void startNodeBackground(int port, Path dataDir) throws InterruptedException {
        StorageNode node = new StorageNode(port, dataDir);
        Thread t = new Thread(() -> {
            try { node.start(); } catch (IOException e) { /* daemon exits cleanly */ }
        }, "test-storage-node-" + port);
        t.setDaemon(true);
        t.start();

        // Poll until the port is open (max 3 s)
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            try (var s = new java.net.Socket("localhost", port)) {
                break; // connected — node is ready
            } catch (IOException ignored) {
                Thread.sleep(50);
            }
        }
    }

    /** Finds an ephemeral TCP port that is currently free. */
    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Decodes an 8-byte big-endian long from {@code bytes}. */
    private static long decodeLong(byte[] bytes) {
        long v = 0;
        for (byte b : bytes) {
            v = (v << 8) | (b & 0xFFL);
        }
        return v;
    }
}

package com.objectstore.client;

import com.objectstore.node.StorageNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 — AVID-FP integration tests (Hendricks, Ganger &amp; Reiter, PODC 2007, Section 4).
 *
 * <p>Spins up an embedded 6-node cluster with full server-to-server echo/ready
 * wiring. Each {@link StorageNode} knows the addresses of its 5 peers so it can
 * run the AVID-FP consensus protocol.
 *
 * <p>System parameters: {@code n=6, m=4, f=1} (tolerates 1 Byzantine server).
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>{@link #disperse_retrieve_cleanRoundTrip} — normal disperse → retrieve with all
 *       6 nodes healthy; verifies byte-for-byte reconstruction.</li>
 *   <li>{@link #disperse_retrieve_oneNodeDown} — one node is never started; the remaining
 *       5 still reach the delivery quorum ({@code 2f+1 = 3}) and retrieve succeeds.</li>
 *   <li>{@link #byzantineNode_storedWrongFragment_retrieveStillSucceeds} — one node stores
 *       a tampered fragment; AVID-FP RETRIEVE discards it via FPCC mismatch and recovers
 *       using the remaining {@code m=4} consistent fragments.</li>
 * </ol>
 */
class AvidFpTest {

    private static final int N = LinearErasureCodec.TOTAL_FRAGMENTS;    // 6
    private static final int M = LinearErasureCodec.SOURCE_FRAGMENTS;   // 4
    private static final int F = 1;                                       // fault tolerance

    // -----------------------------------------------------------------------
    // Test 1 — clean round-trip: all 6 nodes up
    // -----------------------------------------------------------------------

    @Test
    void disperse_retrieve_cleanRoundTrip(@TempDir Path tempDir) throws Exception {
        List<Integer> ports = allocatePorts(N);
        List<Path>    dirs  = createDirs(tempDir, N);
        startCluster(ports, dirs);

        byte[] original = "Hello from AVID-FP! This is the test content for the clean round trip."
                .getBytes(StandardCharsets.UTF_8);
        Path srcFile = tempDir.resolve("input.bin");
        Files.write(srcFile, original);

        List<ErasureClient.NodeAddress> nodes = toAddresses(ports);
        AvidFpClient avid = new AvidFpClient(M, F);

        int okCount = avid.disperse(srcFile, "avid-clean-test", nodes);
        assertTrue(okCount >= 2 * F + 1,
                "At least 2f+1=" + (2 * F + 1) + " servers must confirm delivery");

        Path outFile = tempDir.resolve("output.bin");
        avid.retrieve("avid-clean-test", nodes, original.length, outFile);
        avid.shutdown();

        assertArrayEquals(original, Files.readAllBytes(outFile),
                "Retrieved bytes must exactly match original");
    }

    // -----------------------------------------------------------------------
    // Test 2 — one node offline: quorum still reached (5 of 6 respond)
    // -----------------------------------------------------------------------

    @Test
    void disperse_retrieve_oneNodeDown(@TempDir Path tempDir) throws Exception {
        List<Integer> ports = allocatePorts(N);
        List<Path>    dirs  = createDirs(tempDir, N);

        // Start only nodes 0..4; node 5 is "down" (never started)
        List<Integer> livePorts = ports.subList(0, N - 1);
        List<Path>    liveDirs  = dirs.subList(0, N - 1);
        startCluster(livePorts, liveDirs);
        // Node 5's port is allocated but no server starts on it — connections will fail

        byte[] original = buildTestData(2048);
        Path srcFile = tempDir.resolve("input.bin");
        Files.write(srcFile, original);

        List<ErasureClient.NodeAddress> nodes = toAddresses(ports);
        AvidFpClient avid = new AvidFpClient(M, F);

        // Disperse: only 5 nodes respond; node 5 times out
        // 5 responses >= 2f+1=3 → delivery quorum reached
        int okCount = avid.disperse(srcFile, "avid-nodedown-test", nodes);
        assertTrue(okCount >= 2 * F + 1,
                "5 live nodes must still satisfy delivery quorum 2f+1=3");

        // Retrieve: node 5 will fail; 5 live nodes respond; f+1=2 must agree on FPCC
        // and m=4 consistent fragments must be available for reconstruction
        Path outFile = tempDir.resolve("output.bin");
        avid.retrieve("avid-nodedown-test", nodes, original.length, outFile);
        avid.shutdown();

        assertArrayEquals(original, Files.readAllBytes(outFile),
                "File must be reconstructed successfully with one node down");
    }

    // -----------------------------------------------------------------------
    // Test 3 — Byzantine node: stored wrong fragment; FPCC filters it out
    // -----------------------------------------------------------------------

    @Test
    void byzantineNode_storedWrongFragment_retrieveStillSucceeds(@TempDir Path tempDir)
            throws Exception {
        List<Integer> ports = allocatePorts(N);
        List<Path>    dirs  = createDirs(tempDir, N);
        startCluster(ports, dirs);

        byte[] original = buildTestData(4096);
        Path srcFile = tempDir.resolve("input.bin");
        Files.write(srcFile, original);

        List<ErasureClient.NodeAddress> nodes = toAddresses(ports);
        AvidFpClient avid = new AvidFpClient(M, F);

        // Disperse normally — all 6 nodes confirm delivery
        int okCount = avid.disperse(srcFile, "avid-byzantine-test", nodes);
        assertTrue(okCount >= 2 * F + 1, "All 6 nodes must confirm delivery initially");

        // Simulate Byzantine behaviour: corrupt shard-2 on node 2's disk
        // The AVID-FP RETRIEVE path uses FPCC to detect this and excludes node 2's fragment
        corruptFragmentOnDisk(dirs.get(2), "avid-byzantine-test", 2);

        // Retrieve: node 2's fragment will fail FPCC verification → treated as erasure
        // Remaining m=4 consistent fragments from nodes 0,1,3,4 → successful reconstruction
        Path outFile = tempDir.resolve("output.bin");
        avid.retrieve("avid-byzantine-test", nodes, original.length, outFile);
        avid.shutdown();

        assertArrayEquals(original, Files.readAllBytes(outFile),
                "File must be reconstructed despite one Byzantine (corrupted-fragment) node");
    }

    // -----------------------------------------------------------------------
    // Cluster startup helpers
    // -----------------------------------------------------------------------

    /**
     * Allocates {@code n} free TCP ports.
     */
    private static List<Integer> allocatePorts(int n) throws IOException {
        List<Integer> ports = new ArrayList<>(n);
        List<ServerSocket> sockets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ServerSocket s = new ServerSocket(0);
            ports.add(s.getLocalPort());
            sockets.add(s);
        }
        for (ServerSocket s : sockets) s.close(); // release so nodes can bind
        return ports;
    }

    /**
     * Creates {@code n} data directories under {@code base}.
     */
    private static List<Path> createDirs(Path base, int n) throws IOException {
        List<Path> dirs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Path d = base.resolve("node-" + i);
            Files.createDirectories(d);
            dirs.add(d);
        }
        return dirs;
    }

    /**
     * Starts {@code ports.size()} {@link StorageNode} instances, each configured
     * with the addresses of all its peers. Waits until all nodes accept connections.
     */
    private static void startCluster(List<Integer> ports, List<Path> dirs) throws InterruptedException {
        for (int i = 0; i < ports.size(); i++) {
            int port = ports.get(i);
            Path dir = dirs.get(i);

            // Build peer list: all ports except self
            List<String> peers = new ArrayList<>();
            for (int p : ports) {
                if (p != port) {
                    peers.add("localhost:" + p);
                }
            }

            StorageNode node = new StorageNode(port, dir, peers, M, F);
            Thread t = new Thread(() -> {
                try { node.start(); } catch (IOException e) { /* daemon exits cleanly */ }
            }, "test-node-" + port);
            t.setDaemon(true);
            t.start();
        }

        // Wait until all nodes are reachable
        long deadline = System.currentTimeMillis() + 15_000;
        for (int port : ports) {
            while (System.currentTimeMillis() < deadline) {
                try (var s = new java.net.Socket("localhost", port)) {
                    break;
                } catch (IOException ignored) {
                    Thread.sleep(50);
                }
            }
        }
    }

    /**
     * Converts a list of port numbers to {@link ErasureClient.NodeAddress} objects.
     */
    private static List<ErasureClient.NodeAddress> toAddresses(List<Integer> ports) {
        List<ErasureClient.NodeAddress> addrs = new ArrayList<>(ports.size());
        for (int p : ports) {
            addrs.add(new ErasureClient.NodeAddress("localhost", p));
        }
        return addrs;
    }

    /**
     * Flips the middle byte of the on-disk fragment file to simulate a
     * Byzantine node returning corrupted data on RETRIEVE_AVID.
     */
    private static void corruptFragmentOnDisk(Path nodeDataDir, String fileId, int shardIndex)
            throws IOException {
        Path file = nodeDataDir.resolve(fileId + "/shard-" + shardIndex);
        assertTrue(Files.exists(file),
                "Fragment file must exist before corruption: " + file);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= (byte) 0xFF;
        Files.write(file, bytes);
    }

    /**
     * Builds deterministic test data of the given size.
     */
    private static byte[] buildTestData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }
}

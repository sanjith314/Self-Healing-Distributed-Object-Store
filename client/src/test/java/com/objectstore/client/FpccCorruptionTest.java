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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 Step 4 end-to-end test — verifies the full self-healing detection loop:
 *
 * <ol>
 *   <li>Encode a file with {@link LinearErasureCodec} ({@code (6,4)} config).</li>
 *   <li>Compute FPCC and upload all fragments with FPCC attached.</li>
 *   <li>Confirm clean download succeeds and bytes match.</li>
 *   <li>Corrupt one fragment on disk (flip a byte in the {@code .data} file).</li>
 *   <li>Re-download: the corrupted fragment is rejected by FPCC → treated as erasure.</li>
 *   <li>Reconstruction succeeds using the remaining {@code k} healthy fragments.</li>
 *   <li>Repeat with TWO corrupted fragments (within fault-tolerance limit).</li>
 *   <li>Confirm that with THREE corrupted fragments (exceeding limit) download fails.</li>
 * </ol>
 */
class FpccCorruptionTest {

    private static final int TOTAL_NODES = LinearErasureCodec.TOTAL_FRAGMENTS;  // 6
    private static final int DATA_FRAGS  = LinearErasureCodec.SOURCE_FRAGMENTS; // 4

    // -----------------------------------------------------------------------
    // Test 1 — clean round-trip with FPCC
    // -----------------------------------------------------------------------

    @Test
    void upload_download_cleanRoundTrip(@TempDir Path tempDir) throws Exception {
        List<Path> dataDirs = createDataDirs(tempDir, TOTAL_NODES);
        List<Integer> ports = startCluster(dataDirs);
        List<ErasureClient.NodeAddress> nodes = toAddresses(ports);

        byte[] original = "The quick brown fox jumps over the lazy dog.".getBytes(StandardCharsets.UTF_8);
        Path srcFile = tempDir.resolve("input.bin");
        Files.write(srcFile, original);

        ErasureClient client = new ErasureClient();
        ShardManifest manifest = client.upload(srcFile, "roundtrip-test", nodes);

        assertNotNull(manifest.getFingerprintCrossChecksum(),
                "Manifest must carry FPCC after upload");

        Path outFile = tempDir.resolve("output.bin");
        client.download("roundtrip-test", manifest, outFile);
        client.shutdown();

        assertArrayEquals(original, Files.readAllBytes(outFile),
                "Downloaded bytes must exactly match original");
    }

    // -----------------------------------------------------------------------
    // Test 2 — single fragment corrupted on disk → FPCC detects → RS reconstructs
    // -----------------------------------------------------------------------

    @Test
    void singleCorruptedFragment_detectedByFpcc_reconstructedByRS(@TempDir Path tempDir)
            throws Exception {
        List<Path> dataDirs = createDataDirs(tempDir, TOTAL_NODES);
        List<Integer> ports = startCluster(dataDirs);
        List<ErasureClient.NodeAddress> nodes = toAddresses(ports);

        byte[] original = buildTestData(4096);
        Path srcFile = tempDir.resolve("input.bin");
        Files.write(srcFile, original);

        ErasureClient client = new ErasureClient();
        ShardManifest manifest = client.upload(srcFile, "corrupt-1-test", nodes);

        // Corrupt fragment 2 on disk (flip one byte in the .data file)
        corruptFragment(dataDirs.get(2), "corrupt-1-test", 2);

        Path outFile = tempDir.resolve("output.bin");
        client.download("corrupt-1-test", manifest, outFile);
        client.shutdown();

        assertArrayEquals(original, Files.readAllBytes(outFile),
                "File must be fully recovered despite one corrupted fragment");
    }

    // -----------------------------------------------------------------------
    // Test 3 — two fragments corrupted → still within (6,4) tolerance of 2
    // -----------------------------------------------------------------------

    @Test
    void twoCorruptedFragments_withinTolerance_reconstructedByRS(@TempDir Path tempDir)
            throws Exception {
        List<Path> dataDirs = createDataDirs(tempDir, TOTAL_NODES);
        List<Integer> ports = startCluster(dataDirs);
        List<ErasureClient.NodeAddress> nodes = toAddresses(ports);

        byte[] original = buildTestData(8192);
        Path srcFile = tempDir.resolve("input.bin");
        Files.write(srcFile, original);

        ErasureClient client = new ErasureClient();
        ShardManifest manifest = client.upload(srcFile, "corrupt-2-test", nodes);

        // Corrupt fragments 0 and 5 (first data and last parity)
        corruptFragment(dataDirs.get(0), "corrupt-2-test", 0);
        corruptFragment(dataDirs.get(5), "corrupt-2-test", 5);

        Path outFile = tempDir.resolve("output.bin");
        client.download("corrupt-2-test", manifest, outFile);
        client.shutdown();

        assertArrayEquals(original, Files.readAllBytes(outFile),
                "File must be recovered with exactly 2 corruptions (at the tolerance limit)");
    }

    // -----------------------------------------------------------------------
    // Test 4 — three corruptions exceed (6,4) tolerance → reconstruction fails
    // -----------------------------------------------------------------------

    @Test
    void threeCorruptedFragments_exceedsTolerance_throwsInsufficientFragments(
            @TempDir Path tempDir) throws Exception {
        List<Path> dataDirs = createDataDirs(tempDir, TOTAL_NODES);
        List<Integer> ports = startCluster(dataDirs);
        List<ErasureClient.NodeAddress> nodes = toAddresses(ports);

        byte[] original = buildTestData(2048);
        Path srcFile = tempDir.resolve("input.bin");
        Files.write(srcFile, original);

        ErasureClient client = new ErasureClient();
        ShardManifest manifest = client.upload(srcFile, "corrupt-3-test", nodes);

        corruptFragment(dataDirs.get(1), "corrupt-3-test", 1);
        corruptFragment(dataDirs.get(3), "corrupt-3-test", 3);
        corruptFragment(dataDirs.get(4), "corrupt-3-test", 4);

        Path outFile = tempDir.resolve("output-fail.bin");
        assertThrows(LinearErasureCodec.InsufficientFragmentsException.class,
                () -> client.download("corrupt-3-test", manifest, outFile),
                "Three corruptions must exceed tolerance and throw InsufficientFragmentsException");
        client.shutdown();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Creates N sub-directories inside tempDir for node data storage. */
    private static List<Path> createDataDirs(Path base, int n) throws IOException {
        List<Path> dirs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Path d = base.resolve("node-" + i);
            Files.createDirectories(d);
            dirs.add(d);
        }
        return dirs;
    }

    /** Starts N StorageNodes on ephemeral ports, returns their port numbers. */
    private static List<Integer> startCluster(List<Path> dataDirs) throws Exception {
        List<Integer> ports = new ArrayList<>(dataDirs.size());
        for (Path dir : dataDirs) {
            int port = findFreePort();
            ports.add(port);
            StorageNode node = new StorageNode(port, dir);
            Thread t = new Thread(() -> {
                try { node.start(); } catch (IOException e) { /* daemon */ }
            }, "test-node-" + port);
            t.setDaemon(true);
            t.start();
        }
        // Wait until all nodes are reachable
        long deadline = System.currentTimeMillis() + 10_000;
        for (int port : ports) {
            while (System.currentTimeMillis() < deadline) {
                try (var s = new java.net.Socket("localhost", port)) {
                    break;
                } catch (IOException ignored) {
                    Thread.sleep(30);
                }
            }
        }
        return ports;
    }

    /** Converts port numbers to NodeAddress list. */
    private static List<ErasureClient.NodeAddress> toAddresses(List<Integer> ports) {
        List<ErasureClient.NodeAddress> addrs = new ArrayList<>();
        for (int p : ports) addrs.add(new ErasureClient.NodeAddress("localhost", p));
        return addrs;
    }

    /**
     * Flips a byte in the on-disk data file for the given fragment.
     * The StorageNode stores files as {@code <dataDir>/<fileId>/shard-<index>}.
     */
    private static void corruptFragment(Path nodeDataDir, String fileId, int shardIndex)
            throws IOException {
        Path file = nodeDataDir.resolve(fileId + "/shard-" + shardIndex);
        assertTrue(Files.exists(file), "Shard file must exist before corruption: " + file);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= (byte) 0xFF;  // flip middle byte
        Files.write(file, bytes);
    }

    /** Builds deterministic test data of the given size. */
    private static byte[] buildTestData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}

package com.objectstore.client;

import com.objectstore.common.HashUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Entry point for the distributed object store client.
 *
 * <p>Supports the following sub-commands via the command line:
 *
 * <pre>
 * # Phase 1 — raw round-trip (no hashing)
 * java -jar client.jar upload   &lt;file&gt; &lt;shardId&gt; &lt;host&gt; &lt;port&gt;
 * java -jar client.jar download &lt;shardId&gt; &lt;outputFile&gt; &lt;host&gt; &lt;port&gt;
 * java -jar client.jar demo     &lt;file&gt; &lt;shardId&gt; &lt;host&gt; &lt;port&gt;
 *
 * # Phase 2 — hash-verified round-trip + corruption detection
 * java -jar client.jar hash-demo &lt;file&gt; &lt;shardId&gt; &lt;host&gt; &lt;port&gt;
 * </pre>
 *
 * <p>The {@code demo} sub-command performs the raw Phase 1 round-trip.
 * The {@code hash-demo} sub-command performs the Phase 2 round-trip:
 * SHA-256 is fingerprinted before upload, the node's stored hash is
 * verified via {@code GETHASH}, and the downloaded bytes are re-verified
 * client-side. A corruption detection demo (byte flip) is also run.
 */
public class Client {

    private static final Logger LOG = Logger.getLogger(Client.class.getName());

    // -----------------------------------------------------------------------
    // Core operations
    // -----------------------------------------------------------------------

    /**
     * Reads {@code filePath} from disk and uploads it to the node as
     * {@code shardId}.
     *
     * @param filePath path to the local file to upload
     * @param shardId  identifier the node will store this data under
     * @param host     storage node hostname / IP
     * @param port     storage node TCP port
     * @throws IOException on file-read or network error
     */
    public void upload(Path filePath, String shardId, String host, int port) throws IOException {
        byte[] data = Files.readAllBytes(filePath);
        LOG.info(String.format("Uploading '%s' (%d bytes) as shard '%s' to %s:%d",
                filePath, data.length, shardId, host, port));

        try (NodeConnection conn = new NodeConnection(host, port)) {
            conn.store(shardId, data);
        }

        LOG.info("Upload complete: shard='" + shardId + "'");
    }

    /**
     * Downloads {@code shardId} from the node and writes it to {@code outputPath}.
     *
     * @param shardId    shard to download
     * @param outputPath where to write the downloaded bytes
     * @param host       storage node hostname / IP
     * @param port       storage node TCP port
     * @throws IOException on network or file-write error
     */
    public void download(String shardId, Path outputPath, String host, int port) throws IOException {
        LOG.info(String.format("Downloading shard '%s' from %s:%d → '%s'", shardId, host, port, outputPath));

        try (NodeConnection conn = new NodeConnection(host, port)) {
            byte[] data = conn.retrieve(shardId);
            Files.write(outputPath, data);
            LOG.info(String.format("Download complete: shard='%s'  bytes=%d", shardId, data.length));
        }
    }

    /**
     * Performs a full upload → download → verify round-trip.
     *
     * <p>Reads {@code filePath}, uploads it, downloads it back, and asserts
     * byte-for-byte equality. Returns {@code true} if the data is identical,
     * {@code false} otherwise.
     *
     * @param filePath source file to test with
     * @param shardId  shard identifier to use for this test
     * @param host     storage node hostname / IP
     * @param port     storage node TCP port
     * @return {@code true} if upload → download round-trip is lossless
     * @throws IOException on any network or file error
     */
    public boolean roundTripVerify(Path filePath, String shardId, String host, int port) throws IOException {
        System.out.println("=== Phase 1 Round-Trip Verification ===");
        System.out.printf("  Source file : %s%n", filePath.toAbsolutePath());
        System.out.printf("  Shard ID    : %s%n", shardId);
        System.out.printf("  Node        : %s:%d%n%n", host, port);

        // Step 1: Read original bytes
        byte[] original = Files.readAllBytes(filePath);
        System.out.printf("[1/3] Read %d bytes from disk.%n", original.length);

        // Step 2: Upload
        try (NodeConnection conn = new NodeConnection(host, port)) {
            conn.store(shardId, original);
        }
        System.out.println("[2/3] STORE   → OK");

        // Step 3: Download
        byte[] downloaded;
        try (NodeConnection conn = new NodeConnection(host, port)) {
            downloaded = conn.retrieve(shardId);
        }
        System.out.printf("[3/3] RETRIEVE → OK  (%d bytes)%n", downloaded.length);

        // Step 4: Verify
        boolean match = Arrays.equals(original, downloaded);
        System.out.println();
        if (match) {
            System.out.println("✅  SUCCESS — downloaded bytes are bit-for-bit identical to the originals.");
        } else {
            System.out.println("❌  FAILURE — downloaded bytes differ from the originals!");
            System.out.printf("    Original  : %d bytes%n", original.length);
            System.out.printf("    Downloaded: %d bytes%n", downloaded.length);
        }
        System.out.println("=".repeat(40));
        return match;
    }

    // -----------------------------------------------------------------------
    // Phase 2 — hash‑verified round‑trip
    // -----------------------------------------------------------------------

    /**
     * Phase 2 end-to-end demo: upload → GETHASH verification → download →
     * client-side SHA-256 verification → corruption detection test.
     *
     * <p>Steps:
     * <ol>
     *   <li>Read the file and compute its SHA-256 locally.</li>
     *   <li>Register the shard in a {@link ShardManifest}.</li>
     *   <li>Upload the shard to the storage node.</li>
     *   <li>Ask the node to recompute its hash via {@code GETHASH} and compare
     *       against the manifest.</li>
     *   <li>Download the shard and re-verify the hash client-side.</li>
     *   <li>Run a corruption-detection sub-test: flip one byte in the received
     *       data and confirm the manifest catches it.</li>
     * </ol>
     *
     * @param filePath source file
     * @param shardIndex shard index (typically 0 for a single-shard Phase 2 demo)
     * @param fileId logical file identifier to use in the manifest
     * @param host   storage node hostname
     * @param port   storage node TCP port
     * @return {@code true} if every check passed
     */
    public boolean hashVerifyRoundTrip(
            Path filePath, int shardIndex, String fileId,
            String host, int port) throws IOException {

        System.out.println();
        System.out.println("=" .repeat(55));
        System.out.println(" Phase 2 — Hash-Verified Round-Trip");
        System.out.println("=" .repeat(55));
        System.out.printf("  File     : %s%n", filePath.toAbsolutePath());
        System.out.printf("  File ID  : %s%n", fileId);
        System.out.printf("  Shard idx: %d%n", shardIndex);
        System.out.printf("  Node     : %s:%d%n%n", host, port);

        // ---- 1. Read + fingerprint ----------------------------------------
        byte[] original = Files.readAllBytes(filePath);
        ShardManifest manifest = new ShardManifest(fileId);
        String expectedHex = manifest.registerShard(shardIndex, original);
        System.out.printf("[1/5] Computed SHA-256: %s%n", expectedHex);
        System.out.printf("      (%d bytes read from disk)%n", original.length);

        // ---- 2. Upload -------------------------------------------------------
        String shardId = manifest.shardId(shardIndex);
        try (NodeConnection conn = new NodeConnection(host, port)) {
            conn.store(shardId, original);
        }
        System.out.printf("%n[2/5] STORE   → OK  (shardId='%s')%n", shardId);

        // ---- 3. GETHASH from node -------------------------------------------
        String nodeHash;
        try (NodeConnection conn = new NodeConnection(host, port)) {
            nodeHash = conn.getHash(shardId);
        }
        if (nodeHash == null) {
            System.out.printf("%n[3/5] GETHASH → ERROR (node returned STATUS_ERROR)%n");
            System.out.println("\u274c  FAIL — node could not compute hash.");
            return false;
        }
        boolean nodeHashMatch = expectedHex.equals(nodeHash);
        System.out.printf("%n[3/5] GETHASH → %s%n", nodeHash);
        System.out.printf("      Expected : %s%n", expectedHex);
        System.out.printf("      Match    : %s%n", nodeHashMatch ? "✅ YES" : "❌ NO");
        if (!nodeHashMatch) {
            System.out.println("\n❌  FAIL — stored bytes are already corrupt on the node!");
            return false;
        }

        // ---- 4. Download + client-side verify --------------------------------
        byte[] downloaded;
        try (NodeConnection conn = new NodeConnection(host, port)) {
            downloaded = conn.retrieve(shardId);
        }
        boolean clientVerify = manifest.verify(shardIndex, downloaded);
        System.out.printf("%n[4/5] RETRIEVE → OK  (%d bytes)%n", downloaded.length);
        System.out.printf("      Client SHA-256 verify : %s%n",
                clientVerify ? "✅ PASS" : "❌ FAIL");

        // ---- 5. Corruption-detection sub-test --------------------------------
        System.out.printf("%n[5/5] Corruption-detection test:%n");
        byte[] corrupted = Arrays.copyOf(downloaded, downloaded.length);
        corrupted[0] ^= (byte) 0xFF;  // flip all bits in the first byte
        boolean corruptDetected = !manifest.verify(shardIndex, corrupted);
        System.out.printf("      Flipped byte 0: 0x%02X → 0x%02X%n",
                downloaded[0] & 0xFF, corrupted[0] & 0xFF);
        System.out.printf("      Corruption detected : %s%n",
                corruptDetected ? "✅ YES (PASS)" : "❌ NOT DETECTED (FAIL)");

        boolean allPassed = nodeHashMatch && clientVerify && corruptDetected;
        System.out.println();
        if (allPassed) {
            System.out.println("✅  ALL Phase 2 checks PASSED.");
        } else {
            System.out.println("❌  One or more Phase 2 checks FAILED.");
        }
        System.out.println("=".repeat(55));
        return allPassed;
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        Client client = new Client();
        String subCommand = args[0].toLowerCase();

        try {
            switch (subCommand) {

                case "upload" -> {
                    // upload <file> <shardId> <host> <port>
                    requireArgs(args, 5, "upload");
                    Path   file    = Paths.get(args[1]);
                    String shardId = args[2];
                    String host    = args[3];
                    int    port    = Integer.parseInt(args[4]);
                    client.upload(file, shardId, host, port);
                }

                case "download" -> {
                    // download <shardId> <outputFile> <host> <port>
                    requireArgs(args, 5, "download");
                    String shardId    = args[1];
                    Path   outputPath = Paths.get(args[2]);
                    String host       = args[3];
                    int    port       = Integer.parseInt(args[4]);
                    client.download(shardId, outputPath, host, port);
                }

                case "demo" -> {
                    // demo <file> <shardId> <host> <port>
                    requireArgs(args, 5, "demo");
                    Path   file    = Paths.get(args[1]);
                    String shardId = args[2];
                    String host    = args[3];
                    int    port    = Integer.parseInt(args[4]);
                    boolean ok = client.roundTripVerify(file, shardId, host, port);
                    System.exit(ok ? 0 : 1);
                }

                case "hash-demo" -> {
                    // hash-demo <file> <shardId-or-fileId> <host> <port>
                    requireArgs(args, 5, "hash-demo");
                    Path   file    = Paths.get(args[1]);
                    String fileId  = args[2];
                    String host    = args[3];
                    int    port    = Integer.parseInt(args[4]);
                    boolean ok = client.hashVerifyRoundTrip(file, 0, fileId, host, port);
                    System.exit(ok ? 0 : 1);
                }

                default -> {
                    System.err.println("Unknown sub-command: " + subCommand);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: port must be an integer. " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void requireArgs(String[] args, int required, String subCmd) {
        if (args.length < required) {
            System.err.println("Error: '" + subCmd + "' requires " + (required - 1) + " arguments.");
            printUsage();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("""
                Usage:
                  java -jar client.jar upload   <file> <shardId> <host> <port>
                  java -jar client.jar download <shardId> <outputFile> <host> <port>
                  java -jar client.jar demo     <file> <shardId> <host> <port>

                Examples:
                  java -jar client.jar demo testfile.bin shard-0 localhost 7000
                """);
    }
}

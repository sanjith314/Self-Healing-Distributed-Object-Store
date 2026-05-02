package com.objectstore.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multi-node upload / download engine for the distributed object store.
 *
 * <p>Implements the Phase 3 upload and download paths:
 *
 * <h2>Upload</h2>
 * <ol>
 *   <li>Read the file bytes and run {@link LinearErasureCodec} encoding to produce
 *       {@code dataShards + parityShards} equal-size fragments.</li>
 *   <li>Generate a {@link PorSecretKey} and compute a PRF tag
 *       {@code σᵢ = f_k(i) + Σⱼ m_{i,j}·u_j (mod p)} for every fragment.</li>
 *   <li>Register node addresses and tags in the {@link ShardManifest}.</li>
 *   <li>Fan out all {@code n} STORE commands in parallel, one per node,
 *       appending the 8-byte sigma to each STORE payload.</li>
 *   <li>Return the fully-populated manifest to the caller (kept client-side).</li>
 * </ol>
 *
 * <h2>Download</h2>
 * <ol>
 *   <li>Fan out all {@code n} RETRIEVE commands in parallel.</li>
 *   <li>Treat node failures (IOException) as <em>erasures</em> — {@code present[i] = false}.</li>
 *   <li>Abort with {@link LinearErasureCodec.InsufficientFragmentsException} if fewer
 *       than {@code k} fragments are healthy.</li>
 *   <li>Pass fragments + erasure mask to {@link LinearErasureCodec#decode}.</li>
 *   <li>Strip zero-padding using the manifest's recorded original file length.</li>
 *   <li>Write the reconstructed bytes to the output path and return it.</li>
 * </ol>
 *
 * <h2>NodeAddress</h2>
 * <p>Node addresses are supplied as {@link NodeAddress} records containing
 * a host string and a port integer.  The convenience factory
 * {@link NodeAddress#parse(String)} accepts {@code "host:port"} strings
 * (e.g. {@code "localhost:7100"}).
 */
public class ErasureClient {

    private static final Logger LOG = Logger.getLogger(ErasureClient.class.getName());

    /** Thread pool for parallel node I/O — one thread per fragment in the worst case. */
    private final ExecutorService executor;

    /** Erasure coding parameters — (6, 4): 4 data + 2 parity fragments. */
    private final int dataShards;
    private final int parityShards;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /** Creates an ErasureClient with the default (6, 4) erasure coding parameters. */
    public ErasureClient() {
        this(LinearErasureCodec.SOURCE_FRAGMENTS, LinearErasureCodec.PARITY_FRAGMENTS);
    }

    /**
     * Creates an ErasureClient with custom RS parameters.
     *
     * @param dataShards   number of data shards (k)
     * @param parityShards number of parity shards (n - k)
     */
    public ErasureClient(int dataShards, int parityShards) {
        this.dataShards   = dataShards;
        this.parityShards = parityShards;
        this.executor     = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "erasure-io");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // Upload
    // -----------------------------------------------------------------------

    /**
     * Encodes {@code filePath} with {@link LinearErasureCodec} and distributes all fragments
     * across the given nodes in parallel.
     *
     * <p>Exactly one fragment is sent to each node: fragment {@code i} → {@code nodes.get(i)}.
     * The number of nodes must equal {@code dataShards + parityShards}.
     *
     * @param filePath     source file to encode and distribute
     * @param fileId       logical file identifier (used as shard-ID prefix in the manifest)
     * @param nodes        ordered list of node addresses (one per fragment)
     * @return the populated {@link ShardManifest} (caller must keep this client-side)
     * @throws IOException                  if the file cannot be read or any node STORE fails
     * @throws IllegalArgumentException     if {@code nodes.size() != dataShards + parityShards}
     */
    public ShardManifest upload(Path filePath, String fileId, List<NodeAddress> nodes)
            throws IOException {

        int totalShards = dataShards + parityShards;
        if (nodes.size() != totalShards) {
            throw new IllegalArgumentException(
                    "Expected " + totalShards + " nodes but got " + nodes.size());
        }

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println(" Phase 4 — FPCC Upload (Hendricks-Ganger-Reiter)");
        System.out.println("=".repeat(60));
        System.out.printf("  File        : %s%n", filePath.toAbsolutePath());
        System.out.printf("  File ID     : %s%n", fileId);
        System.out.printf("  Codec params: (%d data + %d parity = %d total fragments)%n",
                dataShards, parityShards, totalShards);

        // ---- 1. Read file & encode ------------------------------------------
        byte[] data = Files.readAllBytes(filePath);
        long   originalLength = data.length;
        System.out.printf("%n[1/3] Read %d bytes from disk.%n", originalLength);

        LinearErasureCodec codec = new LinearErasureCodec(dataShards, parityShards);
        LinearErasureCodec.EncodedBlock encoded = codec.encode(data);
        byte[][] shards = encoded.encodedFragments();
        int shardSize = shards[0].length;
        System.out.printf("[1/3] Encoded → %d fragments of %d bytes each (%.1f%% overhead).%n",
                totalShards, shardSize,
                100.0 * (totalShards * shardSize - originalLength) / originalLength);

        // ---- 2. Build manifest & compute PoR tags + FPCC --------------------
        PorSecretKey sk = PorSecretKey.generate();
        ShardManifest manifest = new ShardManifest(fileId);
        manifest.setRsMetadata(dataShards, totalShards, originalLength);
        manifest.setSecretKey(sk);

        // Compute FPCC from source + encoded fragments
        FingerprintedCrossChecksum fpcc =
                FingerprintedCrossChecksum.create(encoded.sourceFragments(), shards, codec);
        manifest.setFingerprintCrossChecksum(fpcc);

        for (int i = 0; i < totalShards; i++) {
            manifest.addNodeAddress(nodes.get(i).toHostPort());
            long sigma = PorTagEngine.computeTag(sk, i, shards[i]);
            manifest.registerTag(i, sigma);
            LOG.fine(String.format("  fragment %d → %s  σ=%d", i, nodes.get(i).toHostPort(), sigma));
        }
        System.out.printf("[2/3] Manifest built: %d tags + FPCC computed.%n", totalShards);

        // ---- 3. Fan-out STORE in parallel ------------------------------------
        System.out.printf("[3/3] Uploading %d shards in parallel...%n", totalShards);
        List<CompletableFuture<Void>> futures = new ArrayList<>(totalShards);

        for (int i = 0; i < totalShards; i++) {
            final int     idx     = i;
            final byte[]  shard   = shards[i];
            final NodeAddress node = nodes.get(i);
            final String  shardId = manifest.shardId(i);
            final long    sigma   = manifest.getTag(i).sigma();
            // Pre-compute per-fragment FPCC data for the node to verify on receipt
            final com.objectstore.common.Protocol.FpccFragmentData fpccFrag =
                    new com.objectstore.common.Protocol.FpccFragmentData(
                            fpcc.fingerprintBytes(),
                            fpcc.seed(),
                            fpcc.expectedHash(i),
                            fpcc.expectedEncodedFingerprint(i, codec));

            futures.add(CompletableFuture.runAsync(() -> {
                try (NodeConnection conn = new NodeConnection(node.host(), node.port())) {
                    conn.store(shardId, shard, sigma, fpccFrag);
                    System.out.printf("  ✅ fragment %d → %s  (%d bytes, σ=%d, fpcc=yes)%n",
                            idx, node.toHostPort(), shard.length, sigma);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "STORE failed for fragment " + idx + " on " + node.toHostPort(), e);
                }
            }, executor));
        }

        // Wait for all uploads, collect errors
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException("One or more shard uploads failed: " + cause.getMessage(), cause);
        }

        System.out.println();
        System.out.printf("✅  Upload complete — manifest: %s%n%n", manifest);
        System.out.println("=".repeat(60));

        return manifest;
    }

    // -----------------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------------

    /**
     * Downloads and reconstructs a file from at least {@code k} healthy shards.
     *
     * <p>Node addresses are read from the manifest's {@code nodeAddresses} list,
     * so the caller does not need to supply them separately.
     *
     * @param fileId     logical file identifier (must match the uploaded fileId)
     * @param manifest   the client-held manifest from the upload phase
     * @param outputPath where to write the reconstructed file
     * @return {@code outputPath} on success
     * @throws IOException on unrecoverable I/O error
     * @throws LinearErasureCodec.InsufficientFragmentsException if fewer than {@code k} fragments
     *         are healthy enough to allow reconstruction
     */
    public Path download(String fileId, ShardManifest manifest, Path outputPath)
            throws IOException {

        int total = manifest.getTotalShards();
        int k     = manifest.getDataShards();

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println(" Phase 4 — FPCC Download (Hendricks-Ganger-Reiter)");
        System.out.println("=".repeat(60));
        System.out.printf("  File ID    : %s%n", fileId);
        System.out.printf("  Output     : %s%n", outputPath.toAbsolutePath());
        System.out.printf("  Codec      : (need %d of %d fragments)%n%n", k, total);

        // ---- 1. Fan-out RETRIEVE in parallel ---------------------------------
        byte[][] shards  = new byte[total][];
        boolean[] present = new boolean[total];
        AtomicInteger goodCount = new AtomicInteger(0);

        // Load FPCC from manifest for client-side verification
        final FingerprintedCrossChecksum fpcc = manifest.getFingerprintCrossChecksum();
        final LinearErasureCodec codec = new LinearErasureCodec(k, total - k);

        List<CompletableFuture<Void>> futures = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            final int idx = i;
            final String hostPort = manifest.getNodeAddress(i);
            final String shardId  = manifest.shardId(i);
            final NodeAddress node = NodeAddress.parse(hostPort);

            futures.add(CompletableFuture.runAsync(() -> {
                try (NodeConnection conn = new NodeConnection(node.host(), node.port())) {
                    byte[] bytes = conn.retrieve(shardId);

                    // Phase 4: FPCC client-side verification.
                    // If the manifest has an FPCC, verify the downloaded fragment.
                    // A failed check → treat as erasure (not an exception).
                    if (fpcc != null) {
                        if (!fpcc.verifyFragment(idx, bytes, codec)) {
                            System.out.printf("  ⚠️  fragment %d [%s] — FPCC FAILED (hash or fingerprint mismatch, treating as erasure)%n",
                                    idx, hostPort);
                            // present[idx] stays false — erasure
                            return;
                        }
                    }

                    shards[idx]  = bytes;
                    present[idx] = true;
                    goodCount.incrementAndGet();
                    System.out.printf("  ✅ fragment %d [%s] — OK (%d bytes)%n",
                            idx, hostPort, bytes.length);

                } catch (IOException e) {
                    System.out.printf("  ⚠️  fragment %d [%s] — UNREACHABLE (%s)%n",
                            idx, hostPort, e.getMessage());
                    // present[idx] remains false — treated as erasure
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        System.out.printf("%n[2/3] Retrieved %d / %d fragments successfully.%n", goodCount.get(), total);

        // ---- 2. Guard: quorum check -----------------------------------------
        if (goodCount.get() < k) {
            throw new LinearErasureCodec.InsufficientFragmentsException(
                    "Only " + goodCount.get() + " healthy fragments available; need " + k
                    + " to reconstruct. System is in an unrecoverable state.");
        }

        // ---- 3. LinearErasureCodec reconstruction ---------------------------
        System.out.println("[3/3] Running LinearErasureCodec reconstruction...");
        byte[] reconstructed = codec.decode(shards, present, manifest.getOriginalFileLength());

        // ---- 4. Write output -------------------------------------------------
        Files.createDirectories(outputPath.getParent() == null
                ? outputPath.toAbsolutePath().getParent() : outputPath.getParent());
        Files.write(outputPath, reconstructed);

        System.out.printf("%n✅  Reconstruction complete — wrote %d bytes to '%s'%n",
                reconstructed.length, outputPath);
        System.out.println("=".repeat(60));

        return outputPath;
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    /** Shuts down the internal thread pool. Call when the client is done. */
    public void shutdown() {
        executor.shutdown();
    }

    // -----------------------------------------------------------------------
    // NodeAddress record
    // -----------------------------------------------------------------------

    /**
     * Immutable holder for a storage node's hostname and TCP port.
     *
     * @param host hostname or IP address
     * @param port TCP port
     */
    public record NodeAddress(String host, int port) {

        /**
         * Parses a {@code "host:port"} string into a {@link NodeAddress}.
         *
         * @param hostPort string in the form {@code "localhost:7100"}
         * @return parsed address
         * @throws IllegalArgumentException if the format is invalid
         */
        public static NodeAddress parse(String hostPort) {
            int colon = hostPort.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "Expected 'host:port', got: '" + hostPort + "'");
            }
            String h = hostPort.substring(0, colon);
            int    p;
            try {
                p = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Port is not an integer in '" + hostPort + "'", e);
            }
            return new NodeAddress(h, p);
        }

        /** Returns the {@code "host:port"} string representation. */
        public String toHostPort() {
            return host + ":" + port;
        }
    }
}

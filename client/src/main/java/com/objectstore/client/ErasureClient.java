package com.objectstore.client;

import com.objectstore.common.Protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-node upload/download engine using erasure coding + FPCC verification.
 *
 * Upload: encode file → compute FPCC → distribute fragments to n nodes in parallel.
 * Download: fetch fragments in parallel → verify each with FPCC → decode from k healthy ones.
 */
public class ErasureClient {

    private final ExecutorService executor;
    private final int dataShards;
    private final int parityShards;

    public ErasureClient() {
        this(LinearErasureCodec.SOURCE_FRAGMENTS, LinearErasureCodec.PARITY_FRAGMENTS);
    }

    public ErasureClient(int dataShards, int parityShards) {
        this.dataShards   = dataShards;
        this.parityShards = parityShards;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "erasure-io");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Encodes the file and distributes fragments with FPCC to all nodes.
     * Returns a manifest the client should keep locally.
     */
    public ShardManifest upload(Path filePath, String fileId, List<NodeAddress> nodes)
            throws IOException {

        int totalShards = dataShards + parityShards;
        if (nodes.size() != totalShards)
            throw new IllegalArgumentException("Expected " + totalShards + " nodes, got " + nodes.size());

        System.out.println("\n" + "=".repeat(60));
        System.out.println(" Uploading with FPCC Integrity Verification");
        System.out.println("=".repeat(60));
        System.out.printf("  File   : %s%n", filePath.toAbsolutePath());
        System.out.printf("  FileId : %s%n", fileId);
        System.out.printf("  Codec  : (%d data + %d parity = %d total)%n", dataShards, parityShards, totalShards);

        // 1. Read and encode
        byte[] data = Files.readAllBytes(filePath);
        long originalLength = data.length;
        System.out.printf("%n[1/3] Read %d bytes.%n", originalLength);

        LinearErasureCodec codec = new LinearErasureCodec(dataShards, parityShards);
        LinearErasureCodec.EncodedBlock encoded = codec.encode(data);
        byte[][] shards = encoded.encodedFragments();
        System.out.printf("[1/3] Encoded → %d fragments of %d bytes each.%n", totalShards, shards[0].length);

        // 2. Build manifest and compute FPCC
        ShardManifest manifest = new ShardManifest(fileId);
        manifest.setRsMetadata(dataShards, totalShards, originalLength);

        FingerprintedCrossChecksum fpcc =
                FingerprintedCrossChecksum.create(encoded.sourceFragments(), shards, codec);
        manifest.setFingerprintCrossChecksum(fpcc);

        for (int i = 0; i < totalShards; i++)
            manifest.addNodeAddress(nodes.get(i).toHostPort());

        System.out.println("[2/3] FPCC computed.");

        // 3. Upload fragments in parallel
        System.out.printf("[3/3] Uploading %d fragments in parallel...%n", totalShards);
        List<CompletableFuture<Void>> futures = new ArrayList<>(totalShards);

        for (int i = 0; i < totalShards; i++) {
            final int idx = i;
            final byte[] shard = shards[i];
            final NodeAddress node = nodes.get(i);
            final String shardId = manifest.shardId(i);

            // Build per-fragment FPCC data for the node to verify on receipt
            final Protocol.FpccFragmentData fpccFrag = new Protocol.FpccFragmentData(
                    fpcc.fingerprintBytes(), fpcc.seed(),
                    fpcc.expectedHash(i), fpcc.expectedEncodedFingerprint(i, codec));

            futures.add(CompletableFuture.runAsync(() -> {
                try (NodeConnection conn = new NodeConnection(node.host(), node.port())) {
                    conn.store(shardId, shard, fpccFrag);
                    System.out.printf("  ✅ fragment %d → %s%n", idx, node.toHostPort());
                } catch (IOException e) {
                    throw new RuntimeException("STORE failed for fragment " + idx, e);
                }
            }, executor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException("Upload failed: " + cause.getMessage(), cause);
        }

        System.out.printf("%n✅  Upload complete.%n");
        System.out.println("=".repeat(60));
        return manifest;
    }

    /**
     * Downloads and reconstructs a file from at least k healthy fragments.
     * Verifies each fragment with the FPCC — failed fragments become erasures.
     */
    public Path download(String fileId, ShardManifest manifest, Path outputPath)
            throws IOException {

        int total = manifest.getTotalShards();
        int k     = manifest.getDataShards();

        System.out.println("\n" + "=".repeat(60));
        System.out.println(" Downloading with FPCC Integrity Verification");
        System.out.println("=".repeat(60));
        System.out.printf("  FileId : %s%n", fileId);
        System.out.printf("  Codec  : (need %d of %d fragments)%n%n", k, total);

        byte[][] shards   = new byte[total][];
        boolean[] present = new boolean[total];
        AtomicInteger goodCount = new AtomicInteger(0);

        final FingerprintedCrossChecksum fpcc = manifest.getFingerprintCrossChecksum();
        final LinearErasureCodec codec = new LinearErasureCodec(k, total - k);

        // Fetch all fragments in parallel
        List<CompletableFuture<Void>> futures = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            final int idx = i;
            final String hostPort = manifest.getNodeAddress(i);
            final String shardId  = manifest.shardId(i);
            final NodeAddress node = NodeAddress.parse(hostPort);

            futures.add(CompletableFuture.runAsync(() -> {
                try (NodeConnection conn = new NodeConnection(node.host(), node.port())) {
                    byte[] bytes = conn.retrieve(shardId);

                    // Verify fragment with FPCC
                    if (fpcc != null && !fpcc.verifyFragment(idx, bytes, codec)) {
                        System.out.printf("  ⚠️  fragment %d — FPCC FAILED (treating as erasure)%n", idx);
                        return;
                    }

                    shards[idx]  = bytes;
                    present[idx] = true;
                    goodCount.incrementAndGet();
                    System.out.printf("  ✅ fragment %d — OK (%d bytes)%n", idx, bytes.length);
                } catch (IOException e) {
                    System.out.printf("  ⚠️  fragment %d — UNREACHABLE (%s)%n", idx, e.getMessage());
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        System.out.printf("%nRetrieved %d / %d fragments.%n", goodCount.get(), total);

        if (goodCount.get() < k)
            throw new LinearErasureCodec.InsufficientFragmentsException(
                    "Only " + goodCount.get() + " fragments available; need " + k);

        // Reconstruct
        System.out.println("Reconstructing from healthy fragments...");
        byte[] reconstructed = codec.decode(shards, present, manifest.getOriginalFileLength());

        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.write(outputPath, reconstructed);

        System.out.printf("%n✅  Wrote %d bytes to '%s'%n", reconstructed.length, outputPath);
        System.out.println("=".repeat(60));
        return outputPath;
    }

    public void shutdown() { executor.shutdown(); }

    /** Simple host:port record for a storage node address. */
    public record NodeAddress(String host, int port) {
        public static NodeAddress parse(String hostPort) {
            int colon = hostPort.lastIndexOf(':');
            if (colon < 0) throw new IllegalArgumentException("Expected 'host:port', got: '" + hostPort + "'");
            return new NodeAddress(hostPort.substring(0, colon),
                                   Integer.parseInt(hostPort.substring(colon + 1)));
        }
        public String toHostPort() { return host + ":" + port; }
    }
}

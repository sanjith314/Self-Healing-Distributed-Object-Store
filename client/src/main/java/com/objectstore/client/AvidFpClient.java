package com.objectstore.client;

import com.objectstore.common.Protocol;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * AVID-FP client — implements the Asynchronous Verifiable Information Dispersal
 * protocol from Hendricks, Ganger & Reiter (PODC 2007), Section 4.
 *
 * System parameters: n = m + 2f servers.
 * Default: n=6, m=4 (source fragments), f=1 (Byzantine fault tolerance).
 *
 * Disperse: encode → compute FPCC → send to all n servers → wait for 2f+1 confirmations.
 * Retrieve: fetch from all n servers → find FPCC agreed by f+1 → verify fragments → decode.
 */
public class AvidFpClient {

    private static final long MAX_FRAGMENT_BYTES = 512L * 1024 * 1024;

    private final int m; // source fragments = k
    private final int f; // Byzantine fault tolerance
    private final ExecutorService executor;

    public AvidFpClient() { this(LinearErasureCodec.SOURCE_FRAGMENTS, 1); }

    public AvidFpClient(int m, int f) {
        this.m = m;
        this.f = f;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "avid-fp-io");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Encodes and disperses a file to all n servers using the AVID-FP protocol.
     * Blocks until at least 2f+1 servers confirm delivery.
     */
    public int disperse(Path filePath, String fileId, List<ErasureClient.NodeAddress> nodes)
            throws IOException {

        int n = nodes.size();
        int parity = n - m;

        System.out.println("\n" + "=".repeat(60));
        System.out.println(" AVID-FP DISPERSE");
        System.out.println("=".repeat(60));
        System.out.printf("  File   : %s%n", filePath.toAbsolutePath());
        System.out.printf("  n=%d  m=%d  f=%d  (need 2f+1=%d confirmations)%n%n", n, m, f, 2 * f + 1);

        // Encode and compute FPCC
        byte[] data = Files.readAllBytes(filePath);
        LinearErasureCodec codec = new LinearErasureCodec(m, parity);
        LinearErasureCodec.EncodedBlock encoded = codec.encode(data);
        byte[][] fragments = encoded.encodedFragments();

        FingerprintedCrossChecksum fpcc =
                FingerprintedCrossChecksum.create(encoded.sourceFragments(), fragments, codec);
        Protocol.FullFpccData fullFpcc = new Protocol.FullFpccData(
                fpcc.crossChecksum(), fpcc.sourceFingerprints(), fpcc.seed(), fpcc.fingerprintBytes());

        System.out.printf("[1/2] Encoded → %d fragments, FPCC computed.%n", n);

        // Send DISPERSE to all servers in parallel
        int[] okCount = {0};
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final byte[] frag = fragments[i];
            final ErasureClient.NodeAddress node = nodes.get(i);

            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    boolean ok = sendDisperse(node.host(), node.port(), fileId, idx, fullFpcc, frag);
                    System.out.printf("  %s shard=%d  node=%s%n",
                            ok ? "✅" : "⚠️ ", idx, node.toHostPort());
                    return ok;
                } catch (Exception e) {
                    System.out.printf("  ⚠️  shard=%d  node=%s  (%s)%n", idx, node.toHostPort(), e.getMessage());
                    return false;
                }
            }, executor));
        }

        for (CompletableFuture<Boolean> fut : futures) {
            try { if (fut.join()) synchronized (okCount) { okCount[0]++; } }
            catch (Exception ignored) {}
        }

        System.out.printf("%n[2/2] %d / %d servers confirmed.%n", okCount[0], n);
        if (okCount[0] < 2 * f + 1)
            throw new IOException("DISPERSE failed: only " + okCount[0] + "/" + n + " confirmed.");

        System.out.println("✅  DISPERSE complete.\n" + "=".repeat(60));
        return okCount[0];
    }

    /**
     * Retrieves and reconstructs a file from the AVID-FP servers.
     * Finds the FPCC agreed upon by >= f+1 servers, verifies fragments, and decodes.
     */
    public Path retrieve(String fileId, List<ErasureClient.NodeAddress> nodes,
                         long originalLen, Path outputPath) throws IOException {

        int n = nodes.size();
        int parity = n - m;

        System.out.println("\n" + "=".repeat(60));
        System.out.println(" AVID-FP RETRIEVE");
        System.out.println("=".repeat(60));
        System.out.printf("  FileId : %s%n  n=%d  m=%d  f=%d%n%n", fileId, n, m, f);

        // Fetch from all servers in parallel
        List<ServerResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final ErasureClient.NodeAddress node = nodes.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    ServerResponse resp = sendRetrieveAvid(node.host(), node.port(), fileId, idx);
                    if (resp != null) {
                        responses.add(resp);
                        System.out.printf("  ✅ shard=%d  node=%s%n", idx, node.toHostPort());
                    } else {
                        System.out.printf("  ⚠️  shard=%d  node=%s (not ready)%n", idx, node.toHostPort());
                    }
                } catch (Exception e) {
                    System.out.printf("  ⚠️  shard=%d  node=%s (%s)%n", idx, node.toHostPort(), e.getMessage());
                }
            }, executor));
        }
        futures.forEach(CompletableFuture::join);
        System.out.printf("%n[1/3] Got %d responses.%n", responses.size());

        // Find FPCC agreed by >= f+1 servers
        Map<String, List<ServerResponse>> groups = new LinkedHashMap<>();
        for (ServerResponse r : responses) {
            String key = fpccKey(r.fpcc());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        Protocol.FullFpccData canonicalFpcc = null;
        List<ServerResponse> canonicalGroup = null;
        for (var entry : groups.entrySet()) {
            if (entry.getValue().size() >= f + 1) {
                canonicalFpcc = entry.getValue().get(0).fpcc();
                canonicalGroup = entry.getValue();
                break;
            }
        }
        if (canonicalFpcc == null)
            throw new IOException("No FPCC agreed by >= " + (f + 1) + " servers.");

        System.out.printf("[2/3] Found canonical FPCC (%d servers agree).%n", canonicalGroup.size());

        // Verify and select consistent fragments
        LinearErasureCodec codec = new LinearErasureCodec(m, parity);
        FingerprintedCrossChecksum fpcc = new FingerprintedCrossChecksum(
                canonicalFpcc.crossChecksum(), canonicalFpcc.fingerprints(),
                canonicalFpcc.seed(), canonicalFpcc.fingerprintBytes());

        byte[][] fragments = new byte[n][];
        boolean[] present  = new boolean[n];
        int goodCount = 0;

        for (ServerResponse r : canonicalGroup) {
            int idx = r.shardIndex();
            if (fpcc.verifyFragment(idx, r.fragment(), codec)) {
                fragments[idx] = r.fragment();
                present[idx] = true;
                goodCount++;
            }
        }

        if (goodCount < m)
            throw new LinearErasureCodec.InsufficientFragmentsException(
                    "Only " + goodCount + " consistent fragments; need " + m);

        System.out.printf("[3/3] Decoding from %d consistent fragments.%n", goodCount);
        byte[] reconstructed = codec.decode(fragments, present, originalLen);

        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.write(outputPath, reconstructed);

        System.out.printf("%n✅  Wrote %d bytes to '%s'%n", reconstructed.length, outputPath);
        System.out.println("=".repeat(60));
        return outputPath;
    }

    // -- Wire helpers --

    private record ServerResponse(int shardIndex, Protocol.FullFpccData fpcc, byte[] fragment) {}

    private boolean sendDisperse(String host, int port, String fileId,
                                 int shardIndex, Protocol.FullFpccData fullFpcc,
                                 byte[] fragment) throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            out.writeByte(Protocol.CMD_DISPERSE);
            Protocol.writeString(out, fileId);
            out.writeInt(shardIndex);
            Protocol.writeFullFpcc(out, fullFpcc);
            Protocol.writeBytes(out, fragment);
            out.flush();
            socket.setSoTimeout(35_000);
            return in.readByte() == Protocol.STATUS_OK;
        }
    }

    private ServerResponse sendRetrieveAvid(String host, int port, String fileId, int shardIndex)
            throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            out.writeByte(Protocol.CMD_RETRIEVE_AVID);
            Protocol.writeString(out, fileId);
            out.flush();
            if (in.readByte() != Protocol.STATUS_OK) return null;
            Protocol.FullFpccData fpcc = Protocol.readFullFpcc(in);
            byte[] fragment = Protocol.readBytes(in, MAX_FRAGMENT_BYTES);
            return new ServerResponse(shardIndex, fpcc, fragment);
        }
    }

    private static String fpccKey(Protocol.FullFpccData fpcc) {
        StringBuilder sb = new StringBuilder();
        for (String cc : fpcc.crossChecksum()) sb.append(cc);
        return sb.toString();
    }

    public void shutdown() { executor.shutdown(); }
}

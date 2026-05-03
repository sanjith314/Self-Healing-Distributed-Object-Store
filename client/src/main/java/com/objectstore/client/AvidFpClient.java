package com.objectstore.client;

import com.objectstore.common.Protocol;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AVID-FP client implementing the Asynchronous Verifiable Information Dispersal
 * protocol with Fingerprinted Cross-Checksums from Hendricks, Ganger &amp; Reiter
 * (PODC 2007), Section 4.
 *
 * <p>System parameters: {@code n = m + 2f} servers where {@code m} source fragments
 * and {@code f} is the Byzantine fault tolerance. Default: {@code n=6, m=4, f=1}.
 *
 * <h2>Disperse</h2>
 * <ol>
 *   <li>Encode the file into {@code n} fragments using {@link LinearErasureCodec}.</li>
 *   <li>Compute a {@link FingerprintedCrossChecksum} over all fragments.</li>
 *   <li>Send CMD_DISPERSE to all {@code n} servers in parallel (fragment + full FPCC).</li>
 *   <li>Wait for {@code 2f+1} STATUS_OK responses (delivery quorum).</li>
 * </ol>
 *
 * <h2>Retrieve</h2>
 * <ol>
 *   <li>Send CMD_RETRIEVE_AVID to all {@code n} servers in parallel.</li>
 *   <li>Collect {@code (verified_fpcc, fragment)} pairs from responding servers.</li>
 *   <li>Find an FPCC agreed upon by at least {@code f+1} servers (Byzantine quorum).</li>
 *   <li>Select {@code m} fragments consistent with the agreed FPCC.</li>
 *   <li>Reconstruct using {@link LinearErasureCodec#decode}.</li>
 * </ol>
 */
public class AvidFpClient {

    private static final Logger LOG = Logger.getLogger(AvidFpClient.class.getName());

    private static final long MAX_FRAGMENT_BYTES = 512L * 1024 * 1024;

    private final int m;  // source fragments = k
    private final int f;  // Byzantine fault tolerance
    private final ExecutorService executor;

    public AvidFpClient() {
        this(LinearErasureCodec.SOURCE_FRAGMENTS, 1);
    }

    public AvidFpClient(int m, int f) {
        this.m        = m;
        this.f        = f;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "avid-fp-io");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // Disperse
    // -----------------------------------------------------------------------

    /**
     * Encodes and disperses the file to all {@code n} servers.
     * Blocks until the delivery quorum ({@code 2f+1} STATUS_OK) is reached.
     *
     * @param filePath  the file to disperse
     * @param fileId    logical identifier used as the fragment key on each server
     * @param nodes     ordered list of all {@code n} server addresses
     * @return number of servers that confirmed delivery (>= {@code 2f+1})
     * @throws IOException if the file cannot be read or the quorum cannot be reached
     */
    public int disperse(Path filePath, String fileId, List<ErasureClient.NodeAddress> nodes)
            throws IOException {

        int n = nodes.size();
        int parityFragments = n - m;

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println(" AVID-FP DISPERSE (Hendricks-Ganger-Reiter PODC 2007)");
        System.out.println("=".repeat(60));
        System.out.printf("  File   : %s%n", filePath.toAbsolutePath());
        System.out.printf("  FileId : %s%n", fileId);
        System.out.printf("  n=%d  m=%d  f=%d  (need 2f+1=%d OK for delivery)%n%n", n, m, f, 2 * f + 1);

        // 1. Encode
        byte[] data = Files.readAllBytes(filePath);
        LinearErasureCodec codec = new LinearErasureCodec(m, parityFragments);
        LinearErasureCodec.EncodedBlock encoded = codec.encode(data);
        byte[][] fragments = encoded.encodedFragments();

        // 2. Compute full FPCC
        FingerprintedCrossChecksum fpcc =
                FingerprintedCrossChecksum.create(encoded.sourceFragments(), fragments, codec);

        Protocol.FullFpccData fullFpcc = new Protocol.FullFpccData(
                fpcc.crossChecksum(),
                fpcc.sourceFingerprints(),
                fpcc.seed(),
                fpcc.fingerprintBytes());

        System.out.printf("[1/2] Encoded → %d fragments, FPCC computed.%n", n);

        // 3. Fan-out DISPERSE to all n servers in parallel
        int deliveryQuorum = 2 * f + 1;
        int[] okCount = {0};
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final byte[] fragment = fragments[i];
            final ErasureClient.NodeAddress node = nodes.get(i);

            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    boolean ok = sendDisperse(node.host(), node.port(), fileId, idx, fullFpcc, fragment);
                    if (ok) {
                        System.out.printf("  ✅ DISPERSE confirmed  shard=%d  node=%s%n",
                                idx, node.toHostPort());
                    } else {
                        System.out.printf("  ⚠️  DISPERSE failed     shard=%d  node=%s%n",
                                idx, node.toHostPort());
                    }
                    return ok;
                } catch (Exception e) {
                    System.out.printf("  ⚠️  DISPERSE error     shard=%d  node=%s  (%s)%n",
                            idx, node.toHostPort(), e.getMessage());
                    return false;
                }
            }, executor));
        }

        // Wait for all disperses and count OK responses
        for (CompletableFuture<Boolean> f : futures) {
            try {
                if (f.join()) {
                    synchronized (okCount) { okCount[0]++; }
                }
            } catch (Exception ignored) { /* already handled above */ }
        }

        System.out.printf("%n[2/2] Delivery quorum: %d / %d servers confirmed (need %d).%n",
                okCount[0], n, deliveryQuorum);

        if (okCount[0] < deliveryQuorum) {
            throw new IOException(String.format(
                    "AVID-FP DISPERSE failed: only %d/%d servers confirmed; need %d (2f+1).",
                    okCount[0], n, deliveryQuorum));
        }

        System.out.println("✅  DISPERSE complete.");
        System.out.println("=".repeat(60));
        return okCount[0];
    }

    // -----------------------------------------------------------------------
    // Retrieve
    // -----------------------------------------------------------------------

    /**
     * Retrieves and reconstructs the file from the servers.
     *
     * <p>Collects {@code (verified_fpcc, fragment)} pairs, finds an FPCC agreed upon
     * by at least {@code f+1} servers (Byzantine-safe ground truth), selects
     * {@code m} consistent fragments, and runs {@link LinearErasureCodec#decode}.
     *
     * @param fileId      logical file identifier
     * @param nodes       ordered list of all {@code n} server addresses
     * @param originalLen original file length in bytes (stored in the manifest at disperse time)
     * @param outputPath  where to write the reconstructed file
     * @return {@code outputPath} on success
     * @throws IOException if reconstruction fails (not enough consistent fragments)
     */
    public Path retrieve(String fileId, List<ErasureClient.NodeAddress> nodes,
                         long originalLen, Path outputPath) throws IOException {

        int n = nodes.size();
        int parityFragments = n - m;
        int fpccQuorum = f + 1;  // must agree on FPCC

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println(" AVID-FP RETRIEVE (Hendricks-Ganger-Reiter PODC 2007)");
        System.out.println("=".repeat(60));
        System.out.printf("  FileId : %s%n  n=%d  m=%d  f=%d  (need f+1=%d FPCC agreement)%n%n",
                fileId, n, m, f, fpccQuorum);

        // 1. Fan-out RETRIEVE_AVID to all n servers in parallel
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
                        System.out.printf("  ✅ RETRIEVE_AVID OK    shard=%d  node=%s%n",
                                idx, node.toHostPort());
                    } else {
                        System.out.printf("  ⚠️  RETRIEVE_AVID failed shard=%d  node=%s%n",
                                idx, node.toHostPort());
                    }
                } catch (Exception e) {
                    System.out.printf("  ⚠️  RETRIEVE_AVID error  shard=%d  node=%s  (%s)%n",
                            idx, node.toHostPort(), e.getMessage());
                }
            }, executor));
        }
        futures.forEach(CompletableFuture::join);

        System.out.printf("%n[1/3] Got %d responses.%n", responses.size());

        // 2. Find the FPCC agreed upon by ≥ f+1 servers (canonical FPCC)
        // Group responses by FPCC identity key (sha256 of all crossChecksum entries)
        Map<String, List<ServerResponse>> groups = new LinkedHashMap<>();
        for (ServerResponse r : responses) {
            String key = fpccKey(r.fpcc());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        Protocol.FullFpccData canonicalFpcc = null;
        List<ServerResponse> canonicalGroup = null;
        for (Map.Entry<String, List<ServerResponse>> e : groups.entrySet()) {
            if (e.getValue().size() >= fpccQuorum) {
                canonicalFpcc = e.getValue().get(0).fpcc();
                canonicalGroup = e.getValue();
                break;
            }
        }

        if (canonicalFpcc == null) {
            throw new IOException(String.format(
                    "AVID-FP RETRIEVE: no FPCC agreed upon by ≥ f+1=%d servers.", fpccQuorum));
        }
        System.out.printf("[2/3] Canonical FPCC found (%d servers agree).%n", canonicalGroup.size());

        // 3. Select m consistent fragments using the canonical FPCC
        LinearErasureCodec codec = new LinearErasureCodec(m, parityFragments);
        FingerprintedCrossChecksum fpcc = fpccFromProtocol(canonicalFpcc, codec);

        byte[][] fragments = new byte[n][];
        boolean[] present  = new boolean[n];
        int goodCount = 0;

        for (ServerResponse r : canonicalGroup) {
            int idx = r.shardIndex();
            if (fpcc.verifyFragment(idx, r.fragment(), codec)) {
                fragments[idx] = r.fragment();
                present[idx]   = true;
                goodCount++;
                System.out.printf("  ✅ fragment %d verified against canonical FPCC%n", idx);
            } else {
                System.out.printf("  ⚠️  fragment %d FPCC mismatch — discarded%n", idx);
            }
        }

        if (goodCount < m) {
            throw new LinearErasureCodec.InsufficientFragmentsException(
                    "AVID-FP RETRIEVE: only " + goodCount + " consistent fragments; need " + m);
        }

        System.out.printf("[3/3] Decoding with %d consistent fragments (need %d).%n", goodCount, m);
        byte[] reconstructed = codec.decode(fragments, present, originalLen);

        Files.createDirectories(outputPath.toAbsolutePath().getParent() == null
                ? Path.of(".") : outputPath.toAbsolutePath().getParent());
        Files.write(outputPath, reconstructed);

        System.out.printf("%n✅  RETRIEVE complete — wrote %d bytes to '%s'%n",
                reconstructed.length, outputPath);
        System.out.println("=".repeat(60));
        return outputPath;
    }

    // -----------------------------------------------------------------------
    // Wire helpers
    // -----------------------------------------------------------------------

    /** Per-server RETRIEVE_AVID response: the server's shard index, full FPCC, and fragment bytes. */
    private record ServerResponse(int shardIndex, Protocol.FullFpccData fpcc, byte[] fragment) {}

    /**
     * Sends CMD_DISPERSE to a single server and returns {@code true} if the server
     * responded with STATUS_OK (delivery quorum reached on that server).
     */
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

            // Block until the server delivers (which may take up to AVID_TIMEOUT_SECONDS)
            socket.setSoTimeout(35_000);
            byte status = in.readByte();
            return status == Protocol.STATUS_OK;
        }
    }

    /**
     * Sends CMD_RETRIEVE_AVID to a single server and returns the response, or
     * {@code null} if the server is not ready or the request fails.
     */
    private ServerResponse sendRetrieveAvid(String host, int port, String fileId, int shardIndex)
            throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

            out.writeByte(Protocol.CMD_RETRIEVE_AVID);
            Protocol.writeString(out, fileId);
            out.flush();

            byte status = in.readByte();
            if (status != Protocol.STATUS_OK) {
                return null;
            }

            Protocol.FullFpccData fpcc = Protocol.readFullFpcc(in);
            byte[] fragment = Protocol.readBytes(in, MAX_FRAGMENT_BYTES);
            return new ServerResponse(shardIndex, fpcc, fragment);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a canonical string key for an FPCC, computed as the concatenation
     * of all crossChecksum hex digests. Two FPCCs are considered equal iff their
     * keys match.
     */
    private static String fpccKey(Protocol.FullFpccData fpcc) {
        StringBuilder sb = new StringBuilder();
        for (String cc : fpcc.crossChecksum()) {
            sb.append(cc);
        }
        return sb.toString();
    }

    /**
     * Reconstructs a {@link FingerprintedCrossChecksum} from the protocol record,
     * suitable for calling {@link FingerprintedCrossChecksum#verifyFragment}.
     */
    private static FingerprintedCrossChecksum fpccFromProtocol(
            Protocol.FullFpccData p, LinearErasureCodec codec) {
        return new FingerprintedCrossChecksum(
                p.crossChecksum(), p.fingerprints(), p.seed(), p.fingerprintBytes());
    }

    public void shutdown() {
        executor.shutdown();
    }
}

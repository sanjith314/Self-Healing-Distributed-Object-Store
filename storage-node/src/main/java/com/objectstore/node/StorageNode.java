package com.objectstore.node;

import com.objectstore.common.Protocol;
import com.objectstore.common.HashUtil;
import com.objectstore.common.HomomorphicFingerprint;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TCP storage node for the distributed object store.
 *
 * <p>Handles three protocol layers:
 * <ul>
 *   <li><b>Phase 1–4</b>: {@code STORE}, {@code RETRIEVE}, {@code GETHASH} — simple
 *       client-driven shard storage with optional FPCC verification.</li>
 *   <li><b>Phase 5 (AVID-FP)</b>: {@code DISPERSE}, {@code ECHO}, {@code READY},
 *       {@code RETRIEVE_AVID} — server-to-server echo/ready consensus from
 *       Hendricks, Ganger &amp; Reiter (PODC 2007), Section 4.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * java -jar storage-node.jar [port] [dataDir]
 *   [--peers host:port,host:port,...]   # peer server addresses (others in cluster)
 *   [--m 4]                             # source fragments (default 4)
 *   [--f 1]                             # fault tolerance (default 1)
 * </pre>
 */
public class StorageNode {

    private static final Logger LOG = Logger.getLogger(StorageNode.class.getName());

    private static final int DEFAULT_PORT = 7100;

    /** Maximum shard size accepted: 512 MB. */
    private static final long MAX_SHARD_BYTES = 512L * 1024 * 1024;

    /** Timeout waiting for AVID-FP delivery quorum (seconds). */
    private static final int AVID_TIMEOUT_SECONDS = 30;

    private final int port;
    private final Path dataDir;
    private final List<String> peerAddresses;   // addresses of the OTHER n-1 servers
    private final int m;                         // source-fragment count (= k)
    private final int f;                         // max faulty servers
    private final String selfAddress;            // "localhost:<port>"
    private final ExecutorService threadPool;

    /** AVID-FP per-file state, keyed by fileId. */
    private final Map<String, FpccState> avidState = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public StorageNode(int port, Path dataDir) {
        this(port, dataDir, List.of(), 4, 1);
    }

    public StorageNode(int port, Path dataDir, List<String> peerAddresses, int m, int f) {
        this.port          = port;
        this.dataDir       = dataDir;
        this.peerAddresses = List.copyOf(peerAddresses);
        this.m             = m;
        this.f             = f;
        this.selfAddress   = "localhost:" + port;
        this.threadPool    = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "node-handler");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // AVID-FP per-file state
    // -----------------------------------------------------------------------

    private static final class FpccState {
        final String fileId;
        volatile byte[] fragment;
        volatile Protocol.FullFpccData fullFpcc;
        volatile int shardIndex = -1;

        final Set<String> echoSet  = ConcurrentHashMap.newKeySet();
        final Set<String> readySet = ConcurrentHashMap.newKeySet();
        volatile boolean readySent  = false;
        volatile boolean delivered  = false;
        final CompletableFuture<Void> deliveryFuture = new CompletableFuture<>();

        FpccState(String fileId) { this.fileId = fileId; }
    }

    // -----------------------------------------------------------------------
    // Server loop
    // -----------------------------------------------------------------------

    public void start() throws IOException {
        Files.createDirectories(dataDir);
        LOG.info(String.format("StorageNode starting on port %d  |  data dir: %s  |  peers: %s",
                port, dataDir.toAbsolutePath(), peerAddresses));

        try (ServerSocket server = new ServerSocket(port)) {
            while (true) {
                Socket client = server.accept();
                threadPool.submit(() -> handleConnection(client));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Per-connection handler
    // -----------------------------------------------------------------------

    private void handleConnection(Socket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        try (socket;
             DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            byte command = in.readByte();

            switch (command) {
                case Protocol.CMD_STORE         -> handleStore(in, out, remote);
                case Protocol.CMD_RETRIEVE      -> handleRetrieve(in, out, remote);
                case Protocol.CMD_GETHASH       -> handleGetHash(in, out, remote);
                case Protocol.CMD_DISPERSE      -> handleDisperse(in, out, remote);
                case Protocol.CMD_ECHO          -> handleEcho(in, out, remote);
                case Protocol.CMD_READY         -> handleReady(in, out, remote);
                case Protocol.CMD_RETRIEVE_AVID -> handleRetrieveAvid(in, out, remote);
                default -> {
                    LOG.warning("Unknown command 0x" + Integer.toHexString(command & 0xFF) + " from " + remote);
                    out.writeByte(Protocol.STATUS_ERROR);
                    out.flush();
                }
            }

        } catch (IOException e) {
            LOG.log(Level.WARNING, "I/O error handling connection from " + remote, e);
        }
    }

    // -----------------------------------------------------------------------
    // STORE handler (Phase 1–4)
    // -----------------------------------------------------------------------

    private void handleStore(DataInputStream in, DataOutputStream out, String remote) throws IOException {
        String shardId = Protocol.readString(in);
        byte[] data    = Protocol.readBytes(in, MAX_SHARD_BYTES);

        long sigma = 0L;
        try {
            sigma = in.readLong();
        } catch (EOFException eof) {
            LOG.fine("[STORE] No sigma in payload for shard '" + shardId + "' (legacy client)");
        }

        Protocol.FpccFragmentData fpcc = Protocol.readFpccFragment(in);

        LOG.info(String.format("[STORE] shard='%s'  bytes=%d  σ=%d  fpcc=%s  from=%s",
                shardId, data.length, sigma, fpcc != null ? "yes" : "no", remote));

        if (fpcc != null) {
            String actualHash = HashUtil.sha256Hex(data);
            if (!actualHash.equals(fpcc.expectedHash())) {
                LOG.warning(String.format(
                        "[STORE] REJECTED shard='%s' — hash mismatch: expected=%s actual=%s",
                        shardId, fpcc.expectedHash(), actualHash));
                out.writeByte(Protocol.STATUS_ERROR);
                out.flush();
                return;
            }

            byte[] actualFp = new HomomorphicFingerprint(fpcc.seed(), fpcc.fingerprintBytes())
                    .fingerprint(data);
            if (!Arrays.equals(actualFp, fpcc.expectedFingerprint())) {
                LOG.warning(String.format(
                        "[STORE] REJECTED shard='%s' — fingerprint mismatch", shardId));
                out.writeByte(Protocol.STATUS_ERROR);
                out.flush();
                return;
            }
            LOG.info("[STORE] FPCC verified for shard='" + shardId + "'");
        }

        try {
            Path target = shardPath(shardId);
            Files.createDirectories(target.getParent());
            Files.write(target, data);

            Path tagPath = Path.of(target + ".tag");
            byte[] tagBytes = new byte[8];
            long sigmaVal = sigma;
            for (int i = 7; i >= 0; i--) {
                tagBytes[i] = (byte) (sigmaVal & 0xFF);
                sigmaVal >>= 8;
            }
            Files.write(tagPath, tagBytes);

            out.writeByte(Protocol.STATUS_OK);
            LOG.info(String.format("[STORE] OK  shard='%s'  .tag written", shardId));
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "[STORE] Failed to write shard '" + shardId + "'", e);
            out.writeByte(Protocol.STATUS_ERROR);
        }
        out.flush();
    }

    // -----------------------------------------------------------------------
    // RETRIEVE handler (Phase 1–4)
    // -----------------------------------------------------------------------

    private void handleRetrieve(DataInputStream in, DataOutputStream out, String remote) throws IOException {
        String shardId = Protocol.readString(in);
        LOG.info(String.format("[RETRIEVE] shard='%s'  from=%s", shardId, remote));

        Path target = shardPath(shardId);
        if (!Files.exists(target)) {
            LOG.warning("[RETRIEVE] Not found: shard='" + shardId + "'");
            out.writeByte(Protocol.STATUS_ERROR);
            out.flush();
            return;
        }

        try {
            byte[] data = Files.readAllBytes(target);
            out.writeByte(Protocol.STATUS_OK);
            Protocol.writeBytes(out, data);
            out.flush();
            LOG.info(String.format("[RETRIEVE] OK  shard='%s'  bytes=%d", shardId, data.length));
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "[RETRIEVE] Failed to read shard '" + shardId + "'", e);
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    // GETHASH handler (Phase 2)
    // -----------------------------------------------------------------------

    private void handleGetHash(DataInputStream in, DataOutputStream out, String remote) throws IOException {
        String shardId = Protocol.readString(in);
        LOG.info(String.format("[GETHASH] shard='%s'  from=%s", shardId, remote));

        Path target = shardPath(shardId);
        if (!Files.exists(target)) {
            LOG.warning("[GETHASH] Not found: shard='" + shardId + "'");
            out.writeByte(Protocol.STATUS_ERROR);
            out.flush();
            return;
        }

        try {
            byte[] data    = Files.readAllBytes(target);
            String hexHash = HashUtil.sha256Hex(data);
            out.writeByte(Protocol.STATUS_OK);
            Protocol.writeString(out, hexHash);
            out.flush();
            LOG.info(String.format("[GETHASH] OK  shard='%s'  hash=%s", shardId, hexHash));
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "[GETHASH] Failed to read shard '" + shardId + "'", e);
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    // AVID-FP — DISPERSE handler (Phase 5)
    // -----------------------------------------------------------------------

    /**
     * Receives a DISPERSE request from the client:
     * <ol>
     *   <li>Reads fileId, shardIndex, full FPCC, and the fragment bytes.</li>
     *   <li>Verifies the fragment hash matches {@code crossChecksum[shardIndex]}.</li>
     *   <li>Stores the fragment to disk.</li>
     *   <li>Adds self to EchoSet; sends CMD_ECHO to all peers (background threads).</li>
     *   <li>Blocks until the delivery quorum ({@code |ReadySet| >= 2f+1}) is reached.</li>
     *   <li>Writes STATUS_OK to the client and returns (socket closed by caller).</li>
     * </ol>
     */
    private void handleDisperse(DataInputStream in, DataOutputStream out, String remote)
            throws IOException {
        String fileId  = Protocol.readString(in);
        int shardIndex = in.readInt();
        Protocol.FullFpccData fullFpcc = Protocol.readFullFpcc(in);
        byte[] fragment = Protocol.readBytes(in, MAX_SHARD_BYTES);

        LOG.info(String.format("[DISPERSE] fileId='%s'  shard=%d  from=%s", fileId, shardIndex, remote));

        // Verify fragment hash against crossChecksum[shardIndex]
        String actualHash = HashUtil.sha256Hex(fragment);
        String expectedHash = fullFpcc.crossChecksum()[shardIndex];
        if (!actualHash.equals(expectedHash)) {
            LOG.warning(String.format("[DISPERSE] REJECTED fileId='%s' shard=%d — hash mismatch", fileId, shardIndex));
            out.writeByte(Protocol.STATUS_ERROR);
            out.flush();
            return;
        }

        // Store fragment to disk
        String shardId = fileId + "/shard-" + shardIndex;
        Path target = shardPath(shardId);
        Files.createDirectories(target.getParent());
        Files.write(target, fragment);

        // Initialize AVID state and add self to EchoSet
        FpccState state = avidState.computeIfAbsent(fileId, FpccState::new);
        synchronized (state) {
            state.fragment   = fragment;
            state.fullFpcc   = fullFpcc;
            state.shardIndex = shardIndex;
            state.echoSet.add(selfAddress);   // self-echo: server has verified its own fragment
        }

        LOG.info(String.format("[DISPERSE] OK shard stored  fileId='%s'  shard=%d", fileId, shardIndex));

        // Send CMD_ECHO to all peers (background threads)
        for (String peer : peerAddresses) {
            final Protocol.FullFpccData fpcc = fullFpcc;
            threadPool.submit(() -> sendEcho(peer, fileId, fpcc));
        }

        // In case ECHOs from peers arrived before this DISPERSE was processed
        checkEchoQuorum(state, fileId);

        // Block until delivery quorum (or timeout)
        try {
            state.deliveryFuture.get(AVID_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            out.writeByte(Protocol.STATUS_OK);
            LOG.info("[DISPERSE] DELIVERED  fileId='" + fileId + "'");
        } catch (TimeoutException e) {
            LOG.warning("[DISPERSE] Timed out waiting for READY quorum  fileId='" + fileId + "'");
            out.writeByte(Protocol.STATUS_ERROR);
        } catch (ExecutionException | InterruptedException e) {
            LOG.warning("[DISPERSE] Error waiting for quorum  fileId='" + fileId + "'  " + e.getMessage());
            out.writeByte(Protocol.STATUS_ERROR);
        }
        out.flush();
    }

    // -----------------------------------------------------------------------
    // AVID-FP — ECHO handler (Phase 5)
    // -----------------------------------------------------------------------

    /**
     * Handles an ECHO message from a peer server.
     * Adds the sender to EchoSet; if echo quorum ({@code m+f}) is reached and
     * this server has not yet sent READY, broadcasts CMD_READY to all peers.
     */
    private void handleEcho(DataInputStream in, DataOutputStream out, String remote)
            throws IOException {
        String sender  = Protocol.readString(in);
        String fileId  = Protocol.readString(in);
        Protocol.FullFpccData fullFpcc = Protocol.readFullFpcc(in);

        out.writeByte(Protocol.STATUS_OK);
        out.flush();

        LOG.fine(String.format("[ECHO] from=%s  fileId='%s'", sender, fileId));

        FpccState state = avidState.computeIfAbsent(fileId, FpccState::new);
        state.echoSet.add(sender);
        synchronized (state) {
            if (state.fullFpcc == null) {
                state.fullFpcc = fullFpcc;
            }
        }

        checkEchoQuorum(state, fileId);
    }

    // -----------------------------------------------------------------------
    // AVID-FP — READY handler (Phase 5)
    // -----------------------------------------------------------------------

    /**
     * Handles a READY message from a peer server.
     * <ul>
     *   <li>Adds sender to ReadySet.</li>
     *   <li>If {@code |ReadySet| >= f+1} and READY not yet sent: broadcasts READY (ready-chain).</li>
     *   <li>If {@code |ReadySet| >= 2f+1}: marks file as delivered, unblocking the DISPERSE handler.</li>
     * </ul>
     */
    private void handleReady(DataInputStream in, DataOutputStream out, String remote)
            throws IOException {
        String sender  = Protocol.readString(in);
        String fileId  = Protocol.readString(in);
        Protocol.FullFpccData fullFpcc = Protocol.readFullFpcc(in);

        out.writeByte(Protocol.STATUS_OK);
        out.flush();

        LOG.fine(String.format("[READY] from=%s  fileId='%s'", sender, fileId));

        FpccState state = avidState.computeIfAbsent(fileId, FpccState::new);
        state.readySet.add(sender);
        synchronized (state) {
            if (state.fullFpcc == null) {
                state.fullFpcc = fullFpcc;
            }
            // Ready-chain amplification: f+1 READY messages → also send READY
            if (!state.readySent && state.readySet.size() >= f + 1) {
                triggerReady(state, fileId);
            }
            checkReadyQuorum(state);
        }
    }

    // -----------------------------------------------------------------------
    // AVID-FP — RETRIEVE_AVID handler (Phase 5)
    // -----------------------------------------------------------------------

    /**
     * Returns the verified full FPCC and this server's fragment to the client,
     * but only if the delivery quorum has already been reached.
     *
     * <p>Wire response on OK:
     * <pre>
     * STATUS_OK | FullFpccData | fragment bytes (length-prefixed)
     * </pre>
     */
    private void handleRetrieveAvid(DataInputStream in, DataOutputStream out, String remote)
            throws IOException {
        String fileId = Protocol.readString(in);
        LOG.info(String.format("[RETRIEVE_AVID] fileId='%s'  from=%s", fileId, remote));

        FpccState state = avidState.get(fileId);
        if (state == null || !state.delivered || state.fragment == null || state.fullFpcc == null) {
            LOG.warning("[RETRIEVE_AVID] Not ready  fileId='" + fileId + "'");
            out.writeByte(Protocol.STATUS_ERROR);
            out.flush();
            return;
        }

        out.writeByte(Protocol.STATUS_OK);
        Protocol.writeFullFpcc(out, state.fullFpcc);
        Protocol.writeBytes(out, state.fragment);
        out.flush();
        LOG.info(String.format("[RETRIEVE_AVID] OK  fileId='%s'  bytes=%d", fileId, state.fragment.length));
    }

    // -----------------------------------------------------------------------
    // AVID-FP quorum logic (called within synchronized(state) or atomically)
    // -----------------------------------------------------------------------

    /**
     * Checks whether the echo quorum has been reached and, if so, triggers READY.
     * May be called from multiple threads; the actual READY broadcast is guarded
     * by the {@code readySent} flag inside {@code triggerReady}.
     */
    private void checkEchoQuorum(FpccState state, String fileId) {
        synchronized (state) {
            if (!state.readySent && state.echoSet.size() >= m + f) {
                triggerReady(state, fileId);
            }
        }
    }

    /**
     * Broadcasts CMD_READY to all peers, adds self to ReadySet, sets the
     * {@code readySent} flag, and checks the delivery quorum.
     * Must be called within {@code synchronized(state)}.
     */
    private void triggerReady(FpccState state, String fileId) {
        state.readySent = true;
        state.readySet.add(selfAddress);   // count self
        LOG.info(String.format("[READY] sending READY to %d peers  fileId='%s'  echoSet=%d",
                peerAddresses.size(), fileId, state.echoSet.size()));
        final Protocol.FullFpccData fpcc = state.fullFpcc;
        for (String peer : peerAddresses) {
            threadPool.submit(() -> sendReady(peer, fileId, fpcc));
        }
        checkReadyQuorum(state);
    }

    /**
     * Delivers the file if the delivery quorum ({@code 2f+1}) is reached.
     * Must be called within {@code synchronized(state)}.
     */
    private void checkReadyQuorum(FpccState state) {
        if (!state.delivered && state.readySet.size() >= 2 * f + 1) {
            state.delivered = true;
            state.deliveryFuture.complete(null);
            LOG.info(String.format("[AVID] DELIVERED  fileId='%s'  readySet=%d",
                    state.fileId, state.readySet.size()));
        }
    }

    // -----------------------------------------------------------------------
    // AVID-FP peer messaging helpers
    // -----------------------------------------------------------------------

    private void sendEcho(String peerAddr, String fileId, Protocol.FullFpccData fullFpcc) {
        String[] parts = peerAddr.split(":");
        String host = parts[0];
        int peerPort = Integer.parseInt(parts[1]);
        try (Socket socket = new Socket(host, peerPort);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream  ack = new DataInputStream(socket.getInputStream())) {
            out.writeByte(Protocol.CMD_ECHO);
            Protocol.writeString(out, selfAddress);
            Protocol.writeString(out, fileId);
            Protocol.writeFullFpcc(out, fullFpcc);
            out.flush();
            ack.readByte();
            LOG.fine("[ECHO] sent to " + peerAddr + "  fileId='" + fileId + "'");
        } catch (IOException e) {
            LOG.warning("[ECHO] Failed to send to " + peerAddr + "  " + e.getMessage());
        }
    }

    private void sendReady(String peerAddr, String fileId, Protocol.FullFpccData fullFpcc) {
        String[] parts = peerAddr.split(":");
        String host = parts[0];
        int peerPort = Integer.parseInt(parts[1]);
        try (Socket socket = new Socket(host, peerPort);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream  ack = new DataInputStream(socket.getInputStream())) {
            out.writeByte(Protocol.CMD_READY);
            Protocol.writeString(out, selfAddress);
            Protocol.writeString(out, fileId);
            Protocol.writeFullFpcc(out, fullFpcc);
            out.flush();
            ack.readByte();
            LOG.fine("[READY] sent to " + peerAddr + "  fileId='" + fileId + "'");
        } catch (IOException e) {
            LOG.warning("[READY] Failed to send to " + peerAddr + "  " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Path shardPath(String shardId) throws IOException {
        Path resolved = dataDir.resolve(shardId).normalize();
        if (!resolved.startsWith(dataDir.normalize())) {
            throw new IOException("Rejected potentially traversal shardId: " + shardId);
        }
        return resolved;
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        int  port    = DEFAULT_PORT;
        Path dataDir = Paths.get("data");
        List<String> peers = new ArrayList<>();
        int m = 4;
        int f = 1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--peers" -> peers.addAll(Arrays.asList(args[++i].split(",")));
                case "--m"     -> m = Integer.parseInt(args[++i]);
                case "--f"     -> f = Integer.parseInt(args[++i]);
                default        -> {
                    if (i == 0) port    = Integer.parseInt(args[i]);
                    if (i == 1) dataDir = Paths.get(args[i]);
                }
            }
        }

        new StorageNode(port, dataDir, peers, m, f).start();
    }
}

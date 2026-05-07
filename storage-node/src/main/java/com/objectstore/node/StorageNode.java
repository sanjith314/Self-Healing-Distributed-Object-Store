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
 * Handles two protocol layers:
 *   - Basic: STORE, RETRIEVE, GETHASH — client-driven shard storage with optional FPCC verification
 *   - AVID-FP: DISPERSE, ECHO, READY, RETRIEVE_AVID — server-to-server echo/ready consensus
 *
 * Usage:
 *   java -jar storage-node.jar [port] [dataDir]
 *     [--peers host:port,host:port,...]
 *     [--m 4]   # source fragments (default 4)
 *     [--f 1]   # fault tolerance (default 1)
 */
public class StorageNode {

    private static final Logger LOG = Logger.getLogger(StorageNode.class.getName());
    private static final int DEFAULT_PORT = 7100;
    private static final long MAX_SHARD_BYTES = 512L * 1024 * 1024;
    private static final int AVID_TIMEOUT_SECONDS = 30;

    private final int port;
    private final Path dataDir;
    private final List<String> peerAddresses;
    private final int m;  // source fragments (k)
    private final int f;  // Byzantine fault tolerance
    private final String selfAddress;
    private final ExecutorService threadPool;

    // AVID-FP state per file
    private final Map<String, FpccState> avidState = new ConcurrentHashMap<>();

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

    // -- AVID-FP per-file state --

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

    // -- Server loop --

    public void start() throws IOException {
        Files.createDirectories(dataDir);
        LOG.info(String.format("StorageNode on port %d | data: %s | peers: %s",
                port, dataDir.toAbsolutePath(), peerAddresses));

        try (ServerSocket server = new ServerSocket(port)) {
            while (true) {
                Socket client = server.accept();
                threadPool.submit(() -> handleConnection(client));
            }
        }
    }

    private void handleConnection(Socket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        try (socket;
             DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            byte command = in.readByte();
            switch (command) {
                case Protocol.CMD_STORE         -> handleStore(in, out);
                case Protocol.CMD_RETRIEVE      -> handleRetrieve(in, out);
                case Protocol.CMD_GETHASH       -> handleGetHash(in, out);
                case Protocol.CMD_DISPERSE      -> handleDisperse(in, out);
                case Protocol.CMD_ECHO          -> handleEcho(in, out);
                case Protocol.CMD_READY         -> handleReady(in, out);
                case Protocol.CMD_RETRIEVE_AVID -> handleRetrieveAvid(in, out);
                default -> { out.writeByte(Protocol.STATUS_ERROR); out.flush(); }
            }

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error handling connection from " + remote, e);
        }
    }

    // -- STORE handler --
    // Stores a fragment to disk. If FPCC data is attached, verifies the fragment first.

    private void handleStore(DataInputStream in, DataOutputStream out) throws IOException {
        String shardId = Protocol.readString(in);
        byte[] data = Protocol.readBytes(in, MAX_SHARD_BYTES);

        // Read optional FPCC verification data
        Protocol.FpccFragmentData fpcc = Protocol.readFpccFragment(in);

        if (fpcc != null) {
            // Verify hash
            String actualHash = HashUtil.sha256Hex(data);
            if (!actualHash.equals(fpcc.expectedHash())) {
                LOG.warning("[STORE] REJECTED '" + shardId + "' — hash mismatch");
                out.writeByte(Protocol.STATUS_ERROR);
                out.flush();
                return;
            }
            // Verify homomorphic fingerprint
            byte[] actualFp = new HomomorphicFingerprint(fpcc.seed(), fpcc.fingerprintBytes())
                    .fingerprint(data);
            if (!Arrays.equals(actualFp, fpcc.expectedFingerprint())) {
                LOG.warning("[STORE] REJECTED '" + shardId + "' — fingerprint mismatch");
                out.writeByte(Protocol.STATUS_ERROR);
                out.flush();
                return;
            }
        }

        // Write fragment to disk
        Path target = shardPath(shardId);
        Files.createDirectories(target.getParent());
        Files.write(target, data);

        out.writeByte(Protocol.STATUS_OK);
        out.flush();
        LOG.info("[STORE] OK '" + shardId + "' (" + data.length + " bytes)");
    }

    // -- RETRIEVE handler --

    private void handleRetrieve(DataInputStream in, DataOutputStream out) throws IOException {
        String shardId = Protocol.readString(in);
        Path target = shardPath(shardId);

        if (!Files.exists(target)) {
            out.writeByte(Protocol.STATUS_ERROR);
            out.flush();
            return;
        }

        byte[] data = Files.readAllBytes(target);
        out.writeByte(Protocol.STATUS_OK);
        Protocol.writeBytes(out, data);
        out.flush();
    }

    // -- GETHASH handler --
    // Recomputes SHA-256 from stored bytes on the fly (never caches hashes).

    private void handleGetHash(DataInputStream in, DataOutputStream out) throws IOException {
        String shardId = Protocol.readString(in);
        Path target = shardPath(shardId);

        if (!Files.exists(target)) {
            out.writeByte(Protocol.STATUS_ERROR);
            out.flush();
            return;
        }

        byte[] data = Files.readAllBytes(target);
        out.writeByte(Protocol.STATUS_OK);
        Protocol.writeString(out, HashUtil.sha256Hex(data));
        out.flush();
    }

    // -----------------------------------------------------------------------
    // AVID-FP handlers (server-to-server consensus protocol)
    // -----------------------------------------------------------------------

    // DISPERSE: receive fragment from client → verify → store → echo to peers → wait for quorum

    private void handleDisperse(DataInputStream in, DataOutputStream out) throws IOException {
        String fileId  = Protocol.readString(in);
        int shardIndex = in.readInt();
        Protocol.FullFpccData fullFpcc = Protocol.readFullFpcc(in);
        byte[] fragment = Protocol.readBytes(in, MAX_SHARD_BYTES);

        // Verify fragment hash
        String actualHash = HashUtil.sha256Hex(fragment);
        if (!actualHash.equals(fullFpcc.crossChecksum()[shardIndex])) {
            out.writeByte(Protocol.STATUS_ERROR);
            out.flush();
            return;
        }

        // Store to disk
        String shardId = fileId + "/shard-" + shardIndex;
        Path target = shardPath(shardId);
        Files.createDirectories(target.getParent());
        Files.write(target, fragment);

        // Initialize AVID state
        FpccState state = avidState.computeIfAbsent(fileId, FpccState::new);
        synchronized (state) {
            state.fragment   = fragment;
            state.fullFpcc   = fullFpcc;
            state.shardIndex = shardIndex;
            state.echoSet.add(selfAddress);
        }

        // Send ECHO to all peers
        for (String peer : peerAddresses)
            threadPool.submit(() -> sendEcho(peer, fileId, fullFpcc));

        checkEchoQuorum(state, fileId);

        // Block until delivery quorum
        try {
            state.deliveryFuture.get(AVID_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            out.writeByte(Protocol.STATUS_OK);
        } catch (TimeoutException | ExecutionException | InterruptedException e) {
            out.writeByte(Protocol.STATUS_ERROR);
        }
        out.flush();
    }

    // ECHO: peer tells us they verified their fragment

    private void handleEcho(DataInputStream in, DataOutputStream out) throws IOException {
        String sender = Protocol.readString(in);
        String fileId = Protocol.readString(in);
        Protocol.FullFpccData fullFpcc = Protocol.readFullFpcc(in);

        out.writeByte(Protocol.STATUS_OK);
        out.flush();

        FpccState state = avidState.computeIfAbsent(fileId, FpccState::new);
        state.echoSet.add(sender);
        synchronized (state) {
            if (state.fullFpcc == null) state.fullFpcc = fullFpcc;
        }
        checkEchoQuorum(state, fileId);
    }

    // READY: peer is ready to deliver

    private void handleReady(DataInputStream in, DataOutputStream out) throws IOException {
        String sender = Protocol.readString(in);
        String fileId = Protocol.readString(in);
        Protocol.FullFpccData fullFpcc = Protocol.readFullFpcc(in);

        out.writeByte(Protocol.STATUS_OK);
        out.flush();

        FpccState state = avidState.computeIfAbsent(fileId, FpccState::new);
        state.readySet.add(sender);
        synchronized (state) {
            if (state.fullFpcc == null) state.fullFpcc = fullFpcc;
            // Ready-chain: f+1 READY messages trigger our own READY
            if (!state.readySent && state.readySet.size() >= f + 1)
                triggerReady(state, fileId);
            checkReadyQuorum(state);
        }
    }

    // RETRIEVE_AVID: client retrieves fragment + verified FPCC after delivery

    private void handleRetrieveAvid(DataInputStream in, DataOutputStream out) throws IOException {
        String fileId = Protocol.readString(in);
        FpccState state = avidState.get(fileId);

        if (state == null || !state.delivered || state.fragment == null || state.fullFpcc == null) {
            out.writeByte(Protocol.STATUS_ERROR);
            out.flush();
            return;
        }

        out.writeByte(Protocol.STATUS_OK);
        Protocol.writeFullFpcc(out, state.fullFpcc);
        Protocol.writeBytes(out, state.fragment);
        out.flush();
    }

    // -- Quorum logic --

    private void checkEchoQuorum(FpccState state, String fileId) {
        synchronized (state) {
            if (!state.readySent && state.echoSet.size() >= m + f)
                triggerReady(state, fileId);
        }
    }

    private void triggerReady(FpccState state, String fileId) {
        state.readySent = true;
        state.readySet.add(selfAddress);
        final Protocol.FullFpccData fpcc = state.fullFpcc;
        for (String peer : peerAddresses)
            threadPool.submit(() -> sendReady(peer, fileId, fpcc));
        checkReadyQuorum(state);
    }

    private void checkReadyQuorum(FpccState state) {
        if (!state.delivered && state.readySet.size() >= 2 * f + 1) {
            state.delivered = true;
            state.deliveryFuture.complete(null);
        }
    }

    // -- Peer messaging --

    private void sendEcho(String peerAddr, String fileId, Protocol.FullFpccData fullFpcc) {
        String[] parts = peerAddr.split(":");
        try (Socket socket = new Socket(parts[0], Integer.parseInt(parts[1]));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream  ack = new DataInputStream(socket.getInputStream())) {
            out.writeByte(Protocol.CMD_ECHO);
            Protocol.writeString(out, selfAddress);
            Protocol.writeString(out, fileId);
            Protocol.writeFullFpcc(out, fullFpcc);
            out.flush();
            ack.readByte();
        } catch (IOException e) {
            LOG.warning("[ECHO] Failed to send to " + peerAddr);
        }
    }

    private void sendReady(String peerAddr, String fileId, Protocol.FullFpccData fullFpcc) {
        String[] parts = peerAddr.split(":");
        try (Socket socket = new Socket(parts[0], Integer.parseInt(parts[1]));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream  ack = new DataInputStream(socket.getInputStream())) {
            out.writeByte(Protocol.CMD_READY);
            Protocol.writeString(out, selfAddress);
            Protocol.writeString(out, fileId);
            Protocol.writeFullFpcc(out, fullFpcc);
            out.flush();
            ack.readByte();
        } catch (IOException e) {
            LOG.warning("[READY] Failed to send to " + peerAddr);
        }
    }

    // -- Helpers --

    private Path shardPath(String shardId) throws IOException {
        Path resolved = dataDir.resolve(shardId).normalize();
        if (!resolved.startsWith(dataDir.normalize()))
            throw new IOException("Rejected traversal shardId: " + shardId);
        return resolved;
    }

    // -- Entry point --

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        Path dataDir = Paths.get("data");
        List<String> peers = new ArrayList<>();
        int m = 4, f = 1;

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

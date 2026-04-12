package com.objectstore.node;

import com.objectstore.common.Protocol;
import com.objectstore.common.HashUtil;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TCP storage node for the distributed object store.
 *
 * <p>Listens on a configurable port and handles one request per connection:
 * <ul>
 *   <li>{@code STORE  shardId data} — persists raw bytes to {@code ./data/<shardId>}</li>
 *   <li>{@code RETRIEVE shardId}    — streams stored bytes back to the caller</li>
 * </ul>
 *
 * <p>Nodes are intentionally "dumb": they store bytes and return bytes. All
 * integrity logic lives in the client and (later) the auditor.
 *
 * <h2>Usage</h2>
 * <pre>
 * java -jar storage-node.jar [port]        # default port: 7000
 * java -jar storage-node.jar [port] [dataDir]  # optional custom data directory
 * </pre>
 */
public class StorageNode {

    private static final Logger LOG = Logger.getLogger(StorageNode.class.getName());

    /** Default port if none is specified on the command line (7000 is taken by macOS AirPlay). */
    private static final int DEFAULT_PORT = 7100;

    /** Maximum shard size accepted: 512 MB. */
    private static final long MAX_SHARD_BYTES = 512L * 1024 * 1024;

    private final int port;
    private final Path dataDir;
    private final ExecutorService threadPool;

    public StorageNode(int port, Path dataDir) {
        this.port = port;
        this.dataDir = dataDir;
        // One thread per client connection; unbounded so we never drop connections.
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "node-handler");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // Server loop
    // -----------------------------------------------------------------------

    /**
     * Starts listening and blocks forever (or until the JVM exits).
     */
    public void start() throws IOException {
        Files.createDirectories(dataDir);
        LOG.info(String.format("StorageNode starting on port %d  |  data dir: %s", port, dataDir.toAbsolutePath()));

        try (ServerSocket server = new ServerSocket(port)) {
            // NOSONAR — infinite loop intentional for a server process
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
                case Protocol.CMD_STORE    -> handleStore(in, out, remote);
                case Protocol.CMD_RETRIEVE -> handleRetrieve(in, out, remote);
                case Protocol.CMD_GETHASH  -> handleGetHash(in, out, remote);
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
    // STORE handler
    // -----------------------------------------------------------------------

    private void handleStore(DataInputStream in, DataOutputStream out, String remote) throws IOException {
        String shardId = Protocol.readString(in);
        byte[] data    = Protocol.readBytes(in, MAX_SHARD_BYTES);

        LOG.info(String.format("[STORE] shard='%s'  bytes=%d  from=%s", shardId, data.length, remote));

        try {
            Path target = shardPath(shardId);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            out.writeByte(Protocol.STATUS_OK);
            LOG.info(String.format("[STORE] OK  shard='%s'", shardId));
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "[STORE] Failed to write shard '" + shardId + "'", e);
            out.writeByte(Protocol.STATUS_ERROR);
        }
        out.flush();
    }

    // -----------------------------------------------------------------------
    // RETRIEVE handler
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
            // Can't change the status byte now; signal error by closing
            throw e;
        }
    }

    // -----------------------------------------------------------------------
    // GETHASH handler (Phase 2)
    // -----------------------------------------------------------------------

    /**
     * Handles a {@code GETHASH} request.
     *
     * <p>Reads the shard from disk, recomputes its SHA-256 hash on the fly,
     * and returns the hex digest. The hash is <em>never</em> cached: forcing
     * a fresh computation prevents a Byzantine node from serving a pre-stored
     * fake hash for corrupted data.
     *
     * <p>Wire response on OK:
     * <pre>
     * STATUS_OK (1 byte) | hash-string-length (4 bytes) | hex-digest (64 bytes)
     * </pre>
     */
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
            byte[] data   = Files.readAllBytes(target);
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
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the filesystem path for a given shard ID.
     *
     * Shard IDs use forward slashes as separators so they map naturally to
     * a directory hierarchy (e.g. {@code "file-abc/shard-0"}).
     * The path is validated to prevent directory traversal.
     */
    private Path shardPath(String shardId) throws IOException {
        // Normalise once to strip ".." fragments
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
        int  port    = (args.length >= 1) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path dataDir = (args.length >= 2) ? Paths.get(args[1])        : Paths.get("data");

        new StorageNode(port, dataDir).start();
    }
}

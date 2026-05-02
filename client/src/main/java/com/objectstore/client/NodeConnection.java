package com.objectstore.client;

import com.objectstore.common.Protocol;

import java.io.*;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * Manages a single TCP connection to a {@code StorageNode}.
 *
 * <p>A new {@code NodeConnection} should be created for each request; the
 * underlying socket is closed automatically via {@link AutoCloseable}.
 *
 * <h2>Example — store</h2>
 * <pre>
 * try (NodeConnection conn = new NodeConnection("localhost", 7000)) {
 *     conn.store("shard-0", fileBytes);
 * }
 * </pre>
 *
 * <h2>Example — retrieve</h2>
 * <pre>
 * try (NodeConnection conn = new NodeConnection("localhost", 7000)) {
 *     byte[] data = conn.retrieve("shard-0");
 * }
 * </pre>
 */
public class NodeConnection implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NodeConnection.class.getName());

    /** Maximum shard size we will accept from a node: 512 MB. */
    private static final long MAX_SHARD_BYTES = 512L * 1024 * 1024;

    private final String host;
    private final int    port;
    private final Socket socket;
    private final DataInputStream  in;
    private final DataOutputStream out;

    /**
     * Opens a TCP connection to the given node.
     *
     * @param host hostname or IP of the storage node
     * @param port TCP port the storage node is listening on
     * @throws IOException if the connection cannot be established
     */
    public NodeConnection(String host, int port) throws IOException {
        this.host   = host;
        this.port   = port;
        this.socket = new Socket(host, port);
        this.in     = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out    = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Sends a {@code STORE} request without a PoR tag (backward-compatible, sigma = 0).
     *
     * @param shardId unique shard identifier
     * @param data    raw shard bytes to persist
     * @throws IOException      on network or I/O error
     * @throws StorageException if the node returns STATUS_ERROR
     */
    public void store(String shardId, byte[] data) throws IOException {
        store(shardId, data, 0L);
    }

    /**
     * Sends a {@code STORE} request with a Shacham-Waters PRF tag.
     *
     * <p>Wire format (after the existing CMD_STORE header):
     * <pre>
     *   [1 byte]  CMD_STORE
     *   [string]  shardId   (length-prefixed UTF-8)
     *   [bytes]   data      (length-prefixed byte array)
     *   [8 bytes] sigma     (big-endian long, Z_p tag value)
     * </pre>
     * Nodes that do not understand the extended format will attempt to read a
     * fifth field they do not expect; updating StorageNode to always read the
     * sigma is the cleanest fix (Phase 3B Step 6).
     *
     * @param shardId unique shard identifier
     * @param data    raw shard bytes to persist
     * @param sigma   PoR tag value σᵢ ∈ [0, p)
     * @throws IOException      on network or I/O error
     * @throws StorageException if the node returns STATUS_ERROR
     */
    public void store(String shardId, byte[] data, long sigma) throws IOException {
        LOG.fine(String.format("[STORE] shard='%s'  bytes=%d  σ=%d  →  %s:%d",
                shardId, data.length, sigma, host, port));

        out.writeByte(Protocol.CMD_STORE);
        Protocol.writeString(out, shardId);
        Protocol.writeBytes(out, data);
        out.writeLong(sigma);   // 8-byte PoR tag appended after data
        out.writeByte(0x00);    // no FPCC
        out.flush();

        byte status = in.readByte();
        if (status != Protocol.STATUS_OK) {
            throw new StorageException("STORE failed for shard '" + shardId + "' on " + host + ":" + port);
        }
        LOG.fine("[STORE] OK  shard='" + shardId + "'  σ=" + sigma);
    }

    /**
     * Sends a {@code STORE} request with a PoR tag and FPCC per-fragment verification data.
     *
     * <p>Wire format:
     * <pre>
     *   [1 byte]  CMD_STORE
     *   [string]  shardId
     *   [bytes]   data
     *   [8 bytes] sigma
     *   [FPCC]    has-fpcc(1) + fingerprintBytes(4) + seed(bytes) + expectedHash(string) + expectedFp(bytes)
     * </pre>
     *
     * @param shardId  unique shard identifier
     * @param data     raw shard bytes to persist
     * @param sigma    PoR tag value σᵢ (0 if not used)
     * @param fpcc     per-fragment FPCC verification data; {@code null} sends has-fpcc=0x00
     * @throws IOException      on network or I/O error
     * @throws StorageException if the node returns STATUS_ERROR (e.g. FPCC verification failed)
     */
    public void store(String shardId, byte[] data, long sigma, Protocol.FpccFragmentData fpcc)
            throws IOException {
        LOG.fine(String.format("[STORE] shard='%s'  bytes=%d  σ=%d  fpcc=%s  →  %s:%d",
                shardId, data.length, sigma, fpcc != null ? "yes" : "no", host, port));

        out.writeByte(Protocol.CMD_STORE);
        Protocol.writeString(out, shardId);
        Protocol.writeBytes(out, data);
        out.writeLong(sigma);
        if (fpcc != null) {
            Protocol.writeFpccFragment(out, fpcc);
        } else {
            out.writeByte(0x00);  // no FPCC
        }
        out.flush();

        byte status = in.readByte();
        if (status != Protocol.STATUS_OK) {
            throw new StorageException("STORE failed for shard '" + shardId + "' on " + host + ":" + port
                    + " (node rejected the fragment — FPCC mismatch or I/O error)");
        }
        LOG.fine("[STORE] OK  shard='" + shardId + "'");
    }

    /**
     * Sends a {@code RETRIEVE} request and returns the stored bytes.
     *
     * @param shardId unique shard identifier
     * @return the raw bytes stored under {@code shardId}
     * @throws IOException      on network or I/O error
     * @throws StorageException if the node returns STATUS_ERROR (e.g. shard not found)
     */
    public byte[] retrieve(String shardId) throws IOException {
        LOG.fine(String.format("[RETRIEVE] shard='%s'  →  %s:%d", shardId, host, port));

        // Build request
        out.writeByte(Protocol.CMD_RETRIEVE);
        Protocol.writeString(out, shardId);
        out.flush();

        // Read response
        byte status = in.readByte();
        if (status != Protocol.STATUS_OK) {
            throw new StorageException("RETRIEVE failed for shard '" + shardId + "' on " + host + ":" + port
                    + " — shard not found or node error");
        }

        byte[] data = Protocol.readBytes(in, MAX_SHARD_BYTES);
        LOG.fine(String.format("[RETRIEVE] OK  shard='%s'  bytes=%d", shardId, data.length));
        return data;
    }

    /**
     * Sends a {@code GETHASH} request and returns the SHA-256 hex digest that
     * the node recomputes on the fly from its stored bytes (Phase 2).
     *
     * <p>Returns {@code null} if the node responds with STATUS_ERROR (e.g. the
     * shard is not found or an I/O error occurred on the node). Callers should
     * treat a {@code null} response as an <em>erasure</em> for Reed-Solomon
     * purposes — consistent with the "mismatch = erasure flag" design in
     * {@code CLAUDE.md}.
     *
     * @param shardId unique shard identifier
     * @return 64-character lowercase SHA-256 hex string, or {@code null} on node error
     * @throws IOException on network or I/O error (distinct from a node STATUS_ERROR)
     */
    public String getHash(String shardId) throws IOException {
        LOG.fine(String.format("[GETHASH] shard='%s'  →  %s:%d", shardId, host, port));

        // Build request
        out.writeByte(Protocol.CMD_GETHASH);
        Protocol.writeString(out, shardId);
        out.flush();

        // Read response
        byte status = in.readByte();
        if (status != Protocol.STATUS_OK) {
            LOG.fine("[GETHASH] STATUS_ERROR  shard='" + shardId + "'  → treating as erasure");
            return null;  // caller treats this shard as absent / corrupt
        }

        String hexHash = Protocol.readString(in);
        LOG.fine(String.format("[GETHASH] OK  shard='%s'  hash=%s", shardId, hexHash));
        return hexHash;
    }

    // -----------------------------------------------------------------------
    // AutoCloseable
    // -----------------------------------------------------------------------

    @Override
    public void close() throws IOException {
        socket.close();
    }

    // -----------------------------------------------------------------------
    // Accessors (useful for logging / diagnostics)
    // -----------------------------------------------------------------------

    public String getHost() { return host; }
    public int    getPort() { return port; }

    // -----------------------------------------------------------------------
    // Exception types
    // -----------------------------------------------------------------------

    /**
     * Thrown when a storage node returns a STATUS_ERROR response.
     * This is distinct from an {@link IOException} (network failure) —
     * it means the node was reachable but could not satisfy the request.
     */
    public static class StorageException extends IOException {
        public StorageException(String message) {
            super(message);
        }
    }
}

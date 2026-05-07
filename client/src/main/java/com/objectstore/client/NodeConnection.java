package com.objectstore.client;

import com.objectstore.common.Protocol;

import java.io.*;
import java.net.Socket;

/**
 * Manages a single TCP connection to a StorageNode.
 * Create a new NodeConnection per request; the socket is auto-closed.
 */
public class NodeConnection implements AutoCloseable {

    private static final long MAX_SHARD_BYTES = 512L * 1024 * 1024;

    private final String host;
    private final int    port;
    private final Socket socket;
    private final DataInputStream  in;
    private final DataOutputStream out;

    public NodeConnection(String host, int port) throws IOException {
        this.host   = host;
        this.port   = port;
        this.socket = new Socket(host, port);
        this.in     = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out    = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /** Sends a STORE request without FPCC verification. */
    public void store(String shardId, byte[] data) throws IOException {
        store(shardId, data, null);
    }

    /**
     * Sends a STORE request with optional FPCC per-fragment verification data.
     * The node will verify the fragment against the FPCC before writing to disk.
     */
    public void store(String shardId, byte[] data, Protocol.FpccFragmentData fpcc) throws IOException {
        out.writeByte(Protocol.CMD_STORE);
        Protocol.writeString(out, shardId);
        Protocol.writeBytes(out, data);
        if (fpcc != null) {
            Protocol.writeFpccFragment(out, fpcc);
        } else {
            out.writeByte(0x00); // no FPCC
        }
        out.flush();

        byte status = in.readByte();
        if (status != Protocol.STATUS_OK)
            throw new StorageException("STORE failed for '" + shardId + "' on " + host + ":" + port);
    }

    /** Sends a RETRIEVE request and returns the stored bytes. */
    public byte[] retrieve(String shardId) throws IOException {
        out.writeByte(Protocol.CMD_RETRIEVE);
        Protocol.writeString(out, shardId);
        out.flush();

        byte status = in.readByte();
        if (status != Protocol.STATUS_OK)
            throw new StorageException("RETRIEVE failed for '" + shardId + "' on " + host + ":" + port);

        return Protocol.readBytes(in, MAX_SHARD_BYTES);
    }

    /** Sends a GETHASH request. Returns the SHA-256 hex string, or null on error. */
    public String getHash(String shardId) throws IOException {
        out.writeByte(Protocol.CMD_GETHASH);
        Protocol.writeString(out, shardId);
        out.flush();

        byte status = in.readByte();
        if (status != Protocol.STATUS_OK) return null;
        return Protocol.readString(in);
    }

    @Override
    public void close() throws IOException { socket.close(); }

    public String getHost() { return host; }
    public int    getPort() { return port; }

    /** Thrown when a node returns STATUS_ERROR. */
    public static class StorageException extends IOException {
        public StorageException(String message) { super(message); }
    }
}

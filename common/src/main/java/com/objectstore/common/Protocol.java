package com.objectstore.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wire protocol for the distributed object store.
 *
 * <p>All messages are exchanged over a plain TCP connection. A connection
 * handles exactly <em>one</em> request–response pair and is then closed by
 * the client.
 *
 * <h2>Request format</h2>
 * <pre>
 * [1 byte]   command   — CMD_STORE (0x01) | CMD_RETRIEVE (0x02)
 * [4 bytes]  shardId length in bytes (big-endian int)
 * [N bytes]  shardId encoded as UTF-8
 * -- STORE only --
 * [8 bytes]  data length in bytes (big-endian long)
 * [M bytes]  raw shard data
 * </pre>
 *
 * <h2>Response format — STORE</h2>
 * <pre>
 * [1 byte]   status — STATUS_OK (0x00) | STATUS_ERROR (0x01)
 * </pre>
 *
 * <h2>Response format — RETRIEVE</h2>
 * <pre>
 * [1 byte]   status — STATUS_OK (0x00) | STATUS_ERROR (0x01)
 * -- only on OK --
 * [8 bytes]  data length in bytes (big-endian long)
 * [M bytes]  raw shard data
 * </pre>
 */
public final class Protocol {

    // -----------------------------------------------------------------------
    // Command bytes
    // -----------------------------------------------------------------------

    /** Store a shard on the node. */
    public static final byte CMD_STORE    = 0x01;

    /** Retrieve a shard from the node. */
    public static final byte CMD_RETRIEVE = 0x02;

    // -----------------------------------------------------------------------
    // Status bytes
    // -----------------------------------------------------------------------

    /** The requested operation succeeded. */
    public static final byte STATUS_OK    = 0x00;

    /** The requested operation failed (e.g. shard not found, I/O error). */
    public static final byte STATUS_ERROR = 0x01;

    // -----------------------------------------------------------------------
    // I/O helpers
    // -----------------------------------------------------------------------

    /**
     * Writes a UTF-8 string as: [4-byte length][UTF-8 bytes].
     */
    public static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Reads a UTF-8 string that was written by {@link #writeString}.
     */
    public static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 4096) {
            throw new IOException("Implausible string length: " + len);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes a byte array as: [8-byte length][raw bytes].
     */
    public static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeLong(data.length);
        out.write(data);
    }

    /**
     * Reads a byte array that was written by {@link #writeBytes}.
     *
     * @param maxBytes upper bound guard against malformed messages
     */
    public static byte[] readBytes(DataInputStream in, long maxBytes) throws IOException {
        long len = in.readLong();
        if (len < 0 || len > maxBytes) {
            throw new IOException("Implausible data length: " + len);
        }
        byte[] data = new byte[(int) len];
        in.readFully(data);
        return data;
    }

    // Prevent instantiation — utility class only.
    private Protocol() {}
}

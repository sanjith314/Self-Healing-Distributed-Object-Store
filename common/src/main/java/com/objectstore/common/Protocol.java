package com.objectstore.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wire protocol for the distributed object store.
 *
 * <p>All messages are exchanged over a plain TCP connection. A connection
 * handles exactly <em>one</em> request–response pair and is then closed by
 * the client.
 *
 * <h2>Request format (shared header)</h2>
 * <pre>
 * [1 byte]   command   — CMD_STORE (0x01) | CMD_RETRIEVE (0x02) | CMD_GETHASH (0x03)
 *                        CMD_DISPERSE (0x05) | CMD_ECHO (0x06) | CMD_READY (0x07)
 *                        CMD_RETRIEVE_AVID (0x08)
 * [4 bytes]  shardId length in bytes (big-endian int)
 * [N bytes]  shardId encoded as UTF-8
 * -- STORE only --
 * [8 bytes]  data length in bytes (big-endian long)
 * [M bytes]  raw shard data
 * [8 bytes]  sigma   — PoR tag (big-endian long; 0 if unused)
 * [1 byte]   hasFpcc — 0x00 = no FPCC, 0x01 = FPCC present
 * -- if hasFpcc == 0x01 --
 * [4 bytes]  fingerprintBytes
 * [bytes]    seed               (length-prefixed byte array)
 * [string]   expectedHash       (length-prefixed UTF-8 hex, always 64 chars)
 * [bytes]    expectedFingerprint (length-prefixed byte array)
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
 *
 * <h2>Response format — GETHASH (Phase 2)</h2>
 * <pre>
 * [1 byte]   status — STATUS_OK (0x00) | STATUS_ERROR (0x01)
 * -- only on OK --
 * [4 bytes]  hash string length in bytes (big-endian int)
 * [H bytes]  SHA-256 hex digest encoded as UTF-8  (always 64 chars = 64 bytes)
 * </pre>
 *
 * <p><strong>Security note</strong>: The node recomputes the hash from its
 * stored bytes on every {@code GETHASH} request. It does <em>not</em> cache
 * the hash. A Byzantine node that cached a pre-computed hash could serve a
 * correct hash for corrupted data; forcing a recomputation closes that vector.
 */
public final class Protocol {

    // -----------------------------------------------------------------------
    // Command bytes
    // -----------------------------------------------------------------------

    /** Store a shard on the node. */
    public static final byte CMD_STORE    = 0x01;

    /** Retrieve a shard from the node. */
    public static final byte CMD_RETRIEVE = 0x02;

    /**
     * Returns the SHA-256 hex digest of a stored shard (Phase 2).
     *
     * <p>The node recomputes the hash on every request — it does <em>not</em>
     * store hashes. This prevents a Byzantine node from caching a fake hash
     * for corrupted data.
     */
    public static final byte CMD_GETHASH       = 0x03;

    /**
     * AVID-FP DISPERSE (Phase 5): client sends (fpcc, fragment_i) to server i.
     * Server verifies the fragment, stores it, then echoes the FPCC to peers.
     */
    public static final byte CMD_DISPERSE      = 0x05;

    /**
     * AVID-FP ECHO (Phase 5): server-to-server message propagating the FPCC.
     * Sender's address (host:port string) included so receivers can track EchoSet.
     */
    public static final byte CMD_ECHO          = 0x06;

    /**
     * AVID-FP READY (Phase 5): server-to-server message broadcast when echo quorum
     * ({@code |EchoSet| >= m+f}) or ready-chain ({@code |ReadySet| >= f+1}) is reached.
     */
    public static final byte CMD_READY         = 0x07;

    /**
     * AVID-FP RETRIEVE (Phase 5): client retrieves (verified_fpcc, fragment) from a server
     * that has reached the ready quorum.
     */
    public static final byte CMD_RETRIEVE_AVID = 0x08;

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

    // -----------------------------------------------------------------------
    // FPCC (Fingerprinted Cross-Checksum) wire helpers — Phase 4
    // -----------------------------------------------------------------------

    /**
     * Immutable per-fragment FPCC verification data sent with each STORE request.
     *
     * <p>The client pre-computes these values from the full
     * {@code FingerprintedCrossChecksum} object and passes them to
     * {@link #writeFpccFragment} so the receiving node can verify the fragment
     * before writing it to disk.
     *
     * @param fingerprintBytes  size of each fingerprint vector in bytes
     * @param seed              random oracle seed derived from all fragment hashes
     * @param expectedHash      SHA-256 hex digest of this specific fragment (cc[i])
     * @param expectedFingerprint  encoded fingerprint for this position
     *                            (= encodeFingerprint(codingRow[i], sourceFps))
     */
    public record FpccFragmentData(
            int    fingerprintBytes,
            byte[] seed,
            String expectedHash,
            byte[] expectedFingerprint) {}

    /**
     * Writes per-fragment FPCC data to the stream.
     *
     * <p>Writes a leading {@code 0x01} (has-FPCC flag) followed by the four fields.
     * Counterpart: {@link #readFpccFragment}.
     */
    public static void writeFpccFragment(DataOutputStream out, FpccFragmentData fpcc)
            throws IOException {
        out.writeByte(0x01);
        out.writeInt(fpcc.fingerprintBytes());
        writeBytes(out, fpcc.seed());
        writeString(out, fpcc.expectedHash());
        writeBytes(out, fpcc.expectedFingerprint());
    }

    /**
     * Reads the has-FPCC flag and, if present, the FPCC fields.
     *
     * @return the parsed {@link FpccFragmentData}, or {@code null} if the flag byte
     *         is {@code 0x00} (no FPCC attached — backward-compatible with Phase 3B clients)
     */
    public static FpccFragmentData readFpccFragment(DataInputStream in) throws IOException {
        byte hasFpcc;
        try {
            hasFpcc = in.readByte();
        } catch (EOFException eof) {
            return null;
        }
        if (hasFpcc == 0x00) {
            return null;
        }
        int    fpBytes     = in.readInt();
        byte[] seed        = readBytes(in, 256);
        String expHash     = readString(in);
        byte[] expFp       = readBytes(in, (long) fpBytes * 4 + 64);
        return new FpccFragmentData(fpBytes, seed, expHash, expFp);
    }

    // -----------------------------------------------------------------------
    // Full-FPCC wire helpers for AVID-FP (Phase 5)
    // -----------------------------------------------------------------------

    /**
     * The complete Fingerprinted Cross-Checksum sent in AVID-FP DISPERSE / ECHO / READY
     * messages. Carries all information peers need to verify any fragment independently.
     *
     * @param crossChecksum   SHA-256 hex digests of all n fragments, indexed 0..n-1
     * @param fingerprints    encoded fingerprint vectors for the k source fragments (fp[i])
     * @param seed            random oracle seed derived from crossChecksum[]
     * @param fingerprintBytes  size of each fingerprint vector in bytes
     */
    public record FullFpccData(
            String[] crossChecksum,
            byte[][] fingerprints,
            byte[]   seed,
            int      fingerprintBytes) {}

    /**
     * Writes a {@link FullFpccData} to the stream.
     *
     * <p>Wire format:
     * <pre>
     * [4 bytes]   n — number of entries in crossChecksum[]
     * [n strings] crossChecksum[i]   (each: 4-byte len + UTF-8)
     * [4 bytes]   k — number of fingerprints
     * [k arrays]  fingerprints[i]    (each: 8-byte len + raw bytes)
     * [bytes]     seed               (8-byte len + raw bytes)
     * [4 bytes]   fingerprintBytes
     * </pre>
     */
    public static void writeFullFpcc(DataOutputStream out, FullFpccData fpcc)
            throws IOException {
        out.writeInt(fpcc.crossChecksum().length);
        for (String cc : fpcc.crossChecksum()) {
            writeString(out, cc);
        }
        out.writeInt(fpcc.fingerprints().length);
        for (byte[] fp : fpcc.fingerprints()) {
            writeBytes(out, fp);
        }
        writeBytes(out, fpcc.seed());
        out.writeInt(fpcc.fingerprintBytes());
    }

    /**
     * Reads a {@link FullFpccData} written by {@link #writeFullFpcc}.
     */
    public static FullFpccData readFullFpcc(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 1024) {
            throw new IOException("Implausible crossChecksum length: " + n);
        }
        String[] cc = new String[n];
        for (int i = 0; i < n; i++) {
            cc[i] = readString(in);
        }

        int k = in.readInt();
        if (k < 0 || k > n) {
            throw new IOException("Implausible fingerprint count: " + k);
        }
        byte[][] fps = new byte[k][];
        for (int i = 0; i < k; i++) {
            fps[i] = readBytes(in, 4096);
        }

        byte[] seed = readBytes(in, 256);
        int fpBytes = in.readInt();
        if (fpBytes < 1 || fpBytes > 256) {
            throw new IOException("Implausible fingerprintBytes: " + fpBytes);
        }
        return new FullFpccData(cc, fps, seed, fpBytes);
    }

    // Prevent instantiation — utility class only.
    private Protocol() {}
}

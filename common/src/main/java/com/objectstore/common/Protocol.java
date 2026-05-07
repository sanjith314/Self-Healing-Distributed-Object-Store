package com.objectstore.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wire protocol for client-node and node-node communication.
 * All messages are sent over plain TCP — one request/response per connection.
 */
public final class Protocol {

    // -- Command bytes --
    public static final byte CMD_STORE         = 0x01;
    public static final byte CMD_RETRIEVE      = 0x02;
    public static final byte CMD_GETHASH       = 0x03;
    public static final byte CMD_DISPERSE      = 0x05;  // AVID-FP: client → server
    public static final byte CMD_ECHO          = 0x06;  // AVID-FP: server → server
    public static final byte CMD_READY         = 0x07;  // AVID-FP: server → server
    public static final byte CMD_RETRIEVE_AVID = 0x08;  // AVID-FP: client retrieval

    // -- Status bytes --
    public static final byte STATUS_OK    = 0x00;
    public static final byte STATUS_ERROR = 0x01;

    // -- I/O helpers --

    /** Writes a UTF-8 string as [4-byte length][UTF-8 bytes]. */
    public static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** Reads a string written by writeString(). */
    public static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 4096) throw new IOException("Implausible string length: " + len);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Writes a byte array as [8-byte length][raw bytes]. */
    public static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeLong(data.length);
        out.write(data);
    }

    /** Reads a byte array written by writeBytes(). */
    public static byte[] readBytes(DataInputStream in, long maxBytes) throws IOException {
        long len = in.readLong();
        if (len < 0 || len > maxBytes) throw new IOException("Implausible data length: " + len);
        byte[] data = new byte[(int) len];
        in.readFully(data);
        return data;
    }

    // -- Per-fragment FPCC data (sent with STORE) --

    /**
     * FPCC verification data sent along with each fragment during STORE.
     * Lets the storage node verify the fragment before writing it to disk.
     */
    public record FpccFragmentData(
            int    fingerprintBytes,
            byte[] seed,
            String expectedHash,
            byte[] expectedFingerprint) {}

    /** Writes per-fragment FPCC data (flag byte 0x01 + fields). */
    public static void writeFpccFragment(DataOutputStream out, FpccFragmentData fpcc)
            throws IOException {
        out.writeByte(0x01);
        out.writeInt(fpcc.fingerprintBytes());
        writeBytes(out, fpcc.seed());
        writeString(out, fpcc.expectedHash());
        writeBytes(out, fpcc.expectedFingerprint());
    }

    /** Reads per-fragment FPCC data. Returns null if flag byte is 0x00 or missing. */
    public static FpccFragmentData readFpccFragment(DataInputStream in) throws IOException {
        byte hasFpcc;
        try { hasFpcc = in.readByte(); } catch (EOFException eof) { return null; }
        if (hasFpcc == 0x00) return null;

        int    fpBytes = in.readInt();
        byte[] seed    = readBytes(in, 256);
        String expHash = readString(in);
        byte[] expFp   = readBytes(in, (long) fpBytes * 4 + 64);
        return new FpccFragmentData(fpBytes, seed, expHash, expFp);
    }

    // -- Full FPCC data (sent with AVID-FP DISPERSE/ECHO/READY) --

    /**
     * Complete FPCC sent in the AVID-FP protocol so every server can verify any fragment.
     */
    public record FullFpccData(
            String[] crossChecksum,
            byte[][] fingerprints,
            byte[]   seed,
            int      fingerprintBytes) {}

    /** Writes a FullFpccData to the stream. */
    public static void writeFullFpcc(DataOutputStream out, FullFpccData fpcc)
            throws IOException {
        out.writeInt(fpcc.crossChecksum().length);
        for (String cc : fpcc.crossChecksum()) writeString(out, cc);
        out.writeInt(fpcc.fingerprints().length);
        for (byte[] fp : fpcc.fingerprints()) writeBytes(out, fp);
        writeBytes(out, fpcc.seed());
        out.writeInt(fpcc.fingerprintBytes());
    }

    /** Reads a FullFpccData written by writeFullFpcc(). */
    public static FullFpccData readFullFpcc(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 1024) throw new IOException("Implausible crossChecksum length: " + n);
        String[] cc = new String[n];
        for (int i = 0; i < n; i++) cc[i] = readString(in);

        int k = in.readInt();
        if (k < 0 || k > n) throw new IOException("Implausible fingerprint count: " + k);
        byte[][] fps = new byte[k][];
        for (int i = 0; i < k; i++) fps[i] = readBytes(in, 4096);

        byte[] seed = readBytes(in, 256);
        int fpBytes = in.readInt();
        return new FullFpccData(cc, fps, seed, fpBytes);
    }

    private Protocol() {}
}

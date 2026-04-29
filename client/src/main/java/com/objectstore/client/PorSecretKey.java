package com.objectstore.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * Immutable secret key for the Shacham-Waters Proof of Retrievability scheme.
 *
 * <p>Key material:
 * <ul>
 *   <li>{@code prf_k}  — 32-byte HMAC-SHA256 key used as the PRF {@code f_k(i)}</li>
 *   <li>{@code alpha}  — scalar in Z_p (currently unused in the single-sector variant;
 *       kept for API compatibility with the multi-sector formula)</li>
 *   <li>{@code u}      — array of {@code NUM_SECTORS} random scalars in Z_p, one per sector</li>
 * </ul>
 *
 * <h2>Security invariant</h2>
 * <p>This key is <em>client-owned and never sent to storage nodes</em>. Loss of the key means
 * the stored data can no longer be verified — save {@code .porkey} files to multiple offline
 * locations alongside the manifest.
 *
 * <h2>Serialisation</h2>
 * <p>{@link #save(Path)} writes a simple binary format:
 * <pre>
 *   [32 bytes] prf_k
 *   [8 bytes]  alpha  (big-endian long)
 *   [4 bytes]  s = NUM_SECTORS (big-endian int)
 *   [s * 8 bytes]  u[0]..u[s-1] (big-endian longs)
 * </pre>
 */
public record PorSecretKey(byte[] prf_k, long alpha, long[] u) {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Mersenne prime p = 2^61 - 1. All arithmetic in Z_p uses this modulus. */
    public static final long P = (1L << 61) - 1L;

    /** Number of sectors each block fragment is split into for multi-sector tagging. */
    public static final int NUM_SECTORS = 4;

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    /**
     * Generates a fresh secret key with cryptographically-random values.
     *
     * <p>All values are generated with {@link SecureRandom} and reduced modulo {@link #P}.
     */
    public static PorSecretKey generate() {
        SecureRandom rng = new SecureRandom();

        byte[] prf_k = new byte[32];
        rng.nextBytes(prf_k);

        long alpha = positiveLong(rng) % P;

        long[] u = new long[NUM_SECTORS];
        for (int j = 0; j < NUM_SECTORS; j++) {
            u[j] = positiveLong(rng) % P;
        }
        return new PorSecretKey(prf_k, alpha, u);
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /**
     * Saves this key to {@code path} in the binary format described in the class javadoc.
     *
     * @param path target file path (parent directories must exist or will be created)
     * @throws IOException if the file cannot be written
     */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent() == null
                ? path.toAbsolutePath() : path.toAbsolutePath().getParent());

        int size = 32 + 8 + 4 + u.length * 8;
        byte[] buf = new byte[size];
        int pos = 0;

        // prf_k (32 bytes)
        System.arraycopy(prf_k, 0, buf, pos, 32);
        pos += 32;

        // alpha (8 bytes big-endian)
        writeLong(buf, pos, alpha); pos += 8;

        // s (4 bytes big-endian)
        writeInt(buf, pos, u.length); pos += 4;

        // u[0..s-1] (8 bytes each, big-endian)
        for (long uj : u) {
            writeLong(buf, pos, uj); pos += 8;
        }

        Files.write(path, buf);
    }

    /**
     * Loads a key previously saved with {@link #save(Path)}.
     *
     * @param path source file path
     * @return the reconstructed {@link PorSecretKey}
     * @throws IOException if the file cannot be read or is malformed
     */
    public static PorSecretKey load(Path path) throws IOException {
        byte[] buf = Files.readAllBytes(path);
        if (buf.length < 32 + 8 + 4) {
            throw new IOException("Key file too short: " + path);
        }
        int pos = 0;

        byte[] prf_k = new byte[32];
        System.arraycopy(buf, pos, prf_k, 0, 32); pos += 32;

        long alpha = readLong(buf, pos); pos += 8;

        int s = readInt(buf, pos); pos += 4;

        if (buf.length < pos + s * 8) {
            throw new IOException("Key file truncated: expected " + s + " sector keys");
        }
        long[] u = new long[s];
        for (int j = 0; j < s; j++) {
            u[j] = readLong(buf, pos); pos += 8;
        }

        return new PorSecretKey(prf_k, alpha, u);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static long positiveLong(SecureRandom rng) {
        long v = rng.nextLong();
        return v < 0 ? v & Long.MAX_VALUE : v;
    }

    private static void writeLong(byte[] buf, int pos, long v) {
        for (int i = 7; i >= 0; i--) {
            buf[pos + i] = (byte) (v & 0xFF);
            v >>= 8;
        }
    }

    private static void writeInt(byte[] buf, int pos, int v) {
        buf[pos]     = (byte) ((v >> 24) & 0xFF);
        buf[pos + 1] = (byte) ((v >> 16) & 0xFF);
        buf[pos + 2] = (byte) ((v >>  8) & 0xFF);
        buf[pos + 3] = (byte) ( v        & 0xFF);
    }

    private static long readLong(byte[] buf, int pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[pos + i] & 0xFFL);
        }
        return v;
    }

    private static int readInt(byte[] buf, int pos) {
        return ((buf[pos] & 0xFF) << 24)
             | ((buf[pos + 1] & 0xFF) << 16)
             | ((buf[pos + 2] & 0xFF) << 8)
             |  (buf[pos + 3] & 0xFF);
    }
}

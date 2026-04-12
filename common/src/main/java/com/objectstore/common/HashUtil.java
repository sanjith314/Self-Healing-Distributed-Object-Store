package com.objectstore.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class for SHA-256 operations used throughout the distributed object store.
 *
 * <p>All hashing in the system is centralised here so that the algorithm can
 * be changed in one place if needed. The design note from {@code CLAUDE.md}
 * is important: <em>nodes do not store hashes</em> — they recompute them on
 * the fly via the {@code GETHASH} command. Storing hashes on an untrusted node
 * would allow a Byzantine node to return a fake hash for corrupted data.
 */
public final class HashUtil {

    /** The standard Java algorithm name for SHA-256. */
    private static final String ALGORITHM = "SHA-256";

    /**
     * Computes the SHA-256 digest of the given byte array.
     *
     * @param data the bytes to hash; must not be {@code null}
     * @return raw 32-byte SHA-256 digest
     */
    public static byte[] sha256(byte[] data) {
        return newDigest().digest(data);
    }

    /**
     * Computes the SHA-256 digest and encodes it as a lowercase hex string.
     *
     * <p>This is the canonical string representation used in
     * {@link com.objectstore.client.ShardManifest}.
     *
     * @param data the bytes to hash; must not be {@code null}
     * @return 64-character lowercase hex string representing the SHA-256 digest
     */
    public static String sha256Hex(byte[] data) {
        byte[] digest = sha256(data);
        return bytesToHex(digest);
    }

    /**
     * Encodes a raw byte array as a lowercase hex string.
     *
     * @param bytes the bytes to encode
     * @return lowercase hex string (2 characters per byte)
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Returns a fresh {@link MessageDigest} instance for SHA-256.
     *
     * <p>{@code MessageDigest} is <em>not</em> thread-safe, so callers that
     * need to hash from multiple threads should call this method directly.
     */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the Java SE specification.
            throw new AssertionError("SHA-256 not available on this JVM", e);
        }
    }

    // Prevent instantiation — utility class only.
    private HashUtil() {}
}

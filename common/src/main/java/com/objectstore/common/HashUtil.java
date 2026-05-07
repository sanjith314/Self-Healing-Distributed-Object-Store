package com.objectstore.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hashing utility used throughout the project.
 */
public final class HashUtil {

    private static final String ALGORITHM = "SHA-256";

    /** Returns the raw 32-byte SHA-256 digest. */
    public static byte[] sha256(byte[] data) {
        return newDigest().digest(data);
    }

    /** Returns the SHA-256 digest as a 64-char lowercase hex string. */
    public static String sha256Hex(byte[] data) {
        return bytesToHex(sha256(data));
    }

    /** Converts raw bytes to a lowercase hex string. */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    /** Creates a fresh MessageDigest for SHA-256. */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private HashUtil() {}
}

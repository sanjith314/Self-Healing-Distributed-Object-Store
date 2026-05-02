package com.objectstore.common;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Compact linear fingerprints for encoded fragments.
 *
 * <p>Coefficients are derived from a SHA-256 seed and each output byte is a linear
 * projection over GF(2^8). This gives the homomorphic property required by
 * Hendricks, Ganger &amp; Reiter (PODC 2007): fingerprinting commutes with the
 * linear erasure code.
 */
public final class HomomorphicFingerprint {

    public static final int DEFAULT_FINGERPRINT_BYTES = 16;

    private final byte[] seed;
    private final int fingerprintBytes;

    public HomomorphicFingerprint(byte[] seed) {
        this(seed, DEFAULT_FINGERPRINT_BYTES);
    }

    public HomomorphicFingerprint(byte[] seed, int fingerprintBytes) {
        if (seed == null || seed.length == 0) {
            throw new IllegalArgumentException("seed must not be null or empty");
        }
        if (fingerprintBytes < 1) {
            throw new IllegalArgumentException("fingerprintBytes must be >= 1");
        }
        this.seed = Arrays.copyOf(seed, seed.length);
        this.fingerprintBytes = fingerprintBytes;
    }

    public byte[] fingerprint(byte[] data) {
        byte[] result = new byte[fingerprintBytes];
        for (int offset = 0; offset < data.length; offset++) {
            int value = data[offset] & 0xFF;
            if (value == 0) {
                continue;
            }
            for (int slot = 0; slot < fingerprintBytes; slot++) {
                int coefficient = coefficient(offset, slot);
                result[slot] ^= (byte) GaloisField256.multiply(value, coefficient);
            }
        }
        return result;
    }

    public byte[] encodeFingerprint(byte[] codingRow, byte[][] sourceFingerprints) {
        if (codingRow.length != sourceFingerprints.length) {
            throw new IllegalArgumentException("coding row and fingerprint count mismatch");
        }
        byte[] result = new byte[fingerprintBytes];
        for (int source = 0; source < sourceFingerprints.length; source++) {
            int coefficient = codingRow[source] & 0xFF;
            if (coefficient == 0) {
                continue;
            }
            byte[] sourceFingerprint = sourceFingerprints[source];
            if (sourceFingerprint.length != fingerprintBytes) {
                throw new IllegalArgumentException("fingerprint size mismatch");
            }
            byte[] scaled = scalarMultiply(sourceFingerprint, coefficient);
            for (int i = 0; i < fingerprintBytes; i++) {
                result[i] ^= scaled[i];
            }
        }
        return result;
    }

    public byte[] add(byte[] left, byte[] right) {
        if (left.length != right.length || left.length != fingerprintBytes) {
            throw new IllegalArgumentException("fingerprint size mismatch");
        }
        byte[] result = new byte[fingerprintBytes];
        for (int i = 0; i < fingerprintBytes; i++) {
            result[i] = (byte) GaloisField256.add(left[i] & 0xFF, right[i] & 0xFF);
        }
        return result;
    }

    public byte[] scalarMultiply(byte[] fp, int scalar) {
        if (fp.length != fingerprintBytes) {
            throw new IllegalArgumentException("fingerprint size mismatch");
        }
        byte[] result = new byte[fingerprintBytes];
        for (int i = 0; i < fingerprintBytes; i++) {
            result[i] = (byte) GaloisField256.multiply(scalar, fp[i] & 0xFF);
        }
        return result;
    }

    public byte[] seed() {
        return Arrays.copyOf(seed, seed.length);
    }

    public int fingerprintBytes() {
        return fingerprintBytes;
    }

    public static byte[] deriveSeed(String[] crossChecksum) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String hash : crossChecksum) {
            byte[] bytes = hash.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        return HashUtil.sha256(out.toByteArray());
    }

    private int coefficient(int offset, int slot) {
        byte[] offsetBytes = ByteBuffer.allocate(Integer.BYTES * 2)
                .putInt(offset)
                .putInt(slot)
                .array();
        byte[] input = new byte[seed.length + offsetBytes.length];
        System.arraycopy(seed, 0, input, 0, seed.length);
        System.arraycopy(offsetBytes, 0, input, seed.length, offsetBytes.length);
        int c = HashUtil.sha256(input)[0] & 0xFF;
        return c == 0 ? 1 : c;
    }
}

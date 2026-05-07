package com.objectstore.common;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Evaluation fingerprinting over GF(2^8) — Theorem 2.4 from Hendricks et al. (PODC 2007).
 *
 * Each byte of the fingerprint is the evaluation of the data polynomial
 * d(x) = d[0] + d[1]*x + ... + d[L-1]*x^{L-1} at a distinct random point s_j in GF(2^8).
 *
 * The key property is homomorphism: if fragment_i = sum(c_ij * source_j),
 * then fp(fragment_i) = sum(c_ij * fp(source_j)) — i.e., fingerprints are
 * compatible with the linear structure of erasure coding.
 */
public final class HomomorphicFingerprint {

    public static final int DEFAULT_FINGERPRINT_BYTES = 16;

    private final byte[] seed;
    private final int fingerprintBytes;

    public HomomorphicFingerprint(byte[] seed) {
        this(seed, DEFAULT_FINGERPRINT_BYTES);
    }

    public HomomorphicFingerprint(byte[] seed, int fingerprintBytes) {
        if (seed == null || seed.length == 0) throw new IllegalArgumentException("seed must not be empty");
        if (fingerprintBytes < 1) throw new IllegalArgumentException("fingerprintBytes must be >= 1");
        this.seed = Arrays.copyOf(seed, seed.length);
        this.fingerprintBytes = fingerprintBytes;
    }

    /**
     * Computes the fingerprint of data using Horner's rule at each evaluation point.
     * fp[j] = d(s_j) where d(x) = d[0] + d[1]*x + ... + d[L-1]*x^{L-1}
     */
    public byte[] fingerprint(byte[] data) {
        byte[] result = new byte[fingerprintBytes];
        for (int slot = 0; slot < fingerprintBytes; slot++) {
            int s = evaluationPoint(slot);
            int fp = 0;
            for (int i = data.length - 1; i >= 0; i--)
                fp = GaloisField256.add(GaloisField256.multiply(fp, s), data[i] & 0xFF);
            result[slot] = (byte) fp;
        }
        return result;
    }

    /**
     * Computes the expected fingerprint for an encoded fragment given its coding
     * row coefficients and the fingerprints of the source fragments.
     * encFp = sum(codingRow[j] * sourceFingerprints[j]) in GF(2^8)
     */
    public byte[] encodeFingerprint(byte[] codingRow, byte[][] sourceFingerprints) {
        if (codingRow.length != sourceFingerprints.length)
            throw new IllegalArgumentException("coding row and fingerprint count mismatch");
        byte[] result = new byte[fingerprintBytes];
        for (int src = 0; src < sourceFingerprints.length; src++) {
            int coeff = codingRow[src] & 0xFF;
            if (coeff == 0) continue;
            byte[] scaled = scalarMultiply(sourceFingerprints[src], coeff);
            for (int i = 0; i < fingerprintBytes; i++) result[i] ^= scaled[i];
        }
        return result;
    }

    /** Element-wise GF(2^8) addition of two fingerprints. */
    public byte[] add(byte[] left, byte[] right) {
        byte[] result = new byte[fingerprintBytes];
        for (int i = 0; i < fingerprintBytes; i++)
            result[i] = (byte) GaloisField256.add(left[i] & 0xFF, right[i] & 0xFF);
        return result;
    }

    /** Scalar multiplication of a fingerprint by a GF(2^8) element. */
    public byte[] scalarMultiply(byte[] fp, int scalar) {
        byte[] result = new byte[fingerprintBytes];
        for (int i = 0; i < fingerprintBytes; i++)
            result[i] = (byte) GaloisField256.multiply(scalar, fp[i] & 0xFF);
        return result;
    }

    public byte[] seed() { return Arrays.copyOf(seed, seed.length); }
    public int fingerprintBytes() { return fingerprintBytes; }

    /** Derives a seed from the cross-checksum array (acts as a random oracle). */
    public static byte[] deriveSeed(String[] crossChecksum) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String hash : crossChecksum) {
            byte[] bytes = hash.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        return HashUtil.sha256(out.toByteArray());
    }

    /**
     * Derives the evaluation point s_j for slot j from SHA-256(seed || j).
     * We avoid s=0 since evaluating at 0 would collapse the polynomial.
     */
    private int evaluationPoint(int slot) {
        byte[] input = new byte[seed.length + Integer.BYTES];
        System.arraycopy(seed, 0, input, 0, seed.length);
        ByteBuffer.wrap(input, seed.length, Integer.BYTES).putInt(slot);
        int s = HashUtil.sha256(input)[0] & 0xFF;
        return s == 0 ? 1 : s;
    }
}

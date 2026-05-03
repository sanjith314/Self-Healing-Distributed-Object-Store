package com.objectstore.common;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Evaluation fingerprinting over GF(2^8) as defined in Hendricks, Ganger &amp; Reiter
 * (PODC 2007), Theorem 2.4.
 *
 * <p>Each output byte is the evaluation of the data polynomial
 * {@code d(x) = d[0] + d[1]·x + ... + d[L-1]·x^{L-1}} over GF(2^8) at a distinct
 * random evaluation point {@code sⱼ}, derived from the seed. Evaluation is performed
 * using Horner's rule, requiring only GF(2^8) multiply and add.
 *
 * <p>This gives the homomorphic property (Definition 2.5): if {@code dᵢ = Σⱼ bᵢⱼ·dⱼ}
 * (linear combination in GF(2^8)), then {@code fp(s, dᵢ) = Σⱼ bᵢⱼ·fp(s, dⱼ)} because
 * polynomial evaluation is a GF(2^8) ring homomorphism.
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

    /**
     * Computes the evaluation fingerprint of {@code data}.
     *
     * <p>For each output slot {@code j}, the data bytes are interpreted as coefficients of
     * a polynomial {@code d(x) = d[0] + d[1]·x + ... + d[L-1]·x^{L-1}} over GF(2^8),
     * and the result is {@code d(sⱼ)} computed by Horner's rule:
     * <pre>
     *   fp = d[L-1]
     *   for i = L-2 downto 0:  fp = GF256_multiply(fp, sⱼ) XOR d[i]
     * </pre>
     * This is Theorem 2.4 (evaluation fingerprinting) from Hendricks et al.
     */
    public byte[] fingerprint(byte[] data) {
        byte[] result = new byte[fingerprintBytes];
        for (int slot = 0; slot < fingerprintBytes; slot++) {
            int s = evaluationPoint(slot);
            int fp = 0;
            for (int i = data.length - 1; i >= 0; i--) {
                fp = GaloisField256.add(
                        GaloisField256.multiply(fp, s),
                        data[i] & 0xFF);
            }
            result[slot] = (byte) fp;
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

    /**
     * Derives the evaluation point {@code sⱼ ∈ GF(2^8)} for output slot {@code j}.
     *
     * <p>Computed as the first byte of {@code SHA-256(seed || j)}, mapping each slot to
     * a distinct, seed-dependent, non-zero field element. Non-zero is enforced because
     * evaluation at 0 collapses the polynomial to its constant term, losing all
     * higher-degree information and breaking the homomorphic property for parity fragments.
     */
    private int evaluationPoint(int slot) {
        byte[] input = new byte[seed.length + Integer.BYTES];
        System.arraycopy(seed, 0, input, 0, seed.length);
        ByteBuffer.wrap(input, seed.length, Integer.BYTES).putInt(slot);
        int s = HashUtil.sha256(input)[0] & 0xFF;
        return s == 0 ? 1 : s;
    }
}

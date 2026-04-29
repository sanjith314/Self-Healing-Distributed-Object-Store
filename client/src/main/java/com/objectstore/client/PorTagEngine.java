package com.objectstore.client;

import com.objectstore.common.HashUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Shacham-Waters PRF-based tag engine (private verifiability, CCS 2008).
 *
 * <h2>Tag formula</h2>
 * <p>For block {@code i} with sector values {@code m_{i,0} … m_{i,s-1}} ∈ Z_p:
 * <pre>
 *   σᵢ = f_k(i) + Σⱼ m_{i,j} · u_j   (mod p)
 * </pre>
 * where {@code f_k(i) = HMAC-SHA256(k, i) mod p} is the PRF.
 *
 * <h2>Z_p arithmetic</h2>
 * <p>The prime {@code p = 2^61 - 1} (a Mersenne prime) fits in a signed Java {@code long}.
 * {@link #mulMod(long, long)} uses {@link Math#multiplyHigh(long, long)} (Java 9+) to
 * avoid BigInteger and stay within 64 bits.
 *
 * <h2>Thread safety</h2>
 * <p>All methods are static and stateless — safe to call from any thread.
 */
public final class PorTagEngine {

    private PorTagEngine() {}

    // -----------------------------------------------------------------------
    // PRF
    // -----------------------------------------------------------------------

    /**
     * Computes the PRF value {@code f_k(i) = HMAC-SHA256(k, i) mod p}.
     *
     * <p>The first 8 bytes of the HMAC output are interpreted as an unsigned big-endian
     * long, then reduced modulo {@link PorSecretKey#P}. The resulting bias is < 2^-52
     * and is negligible for our security parameter.
     *
     * @param k HMAC key (typically {@link PorSecretKey#prf_k()})
     * @param i block index
     * @return PRF output in [0, p)
     */
    public static long prf(byte[] k, int i) {
        byte[] input = ByteBuffer.allocate(4).putInt(i).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            byte[] digest = mac.doFinal(input);
            // Take first 8 bytes as big-endian unsigned long, then reduce mod p
            long raw = ByteBuffer.wrap(digest).getLong();
            // Make positive before reducing
            return Math.floorMod(raw, PorSecretKey.P);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 unavailable", e);
        }
    }

    // -----------------------------------------------------------------------
    // Sector splitting
    // -----------------------------------------------------------------------

    /**
     * Splits a fragment byte array into {@code s} sector values in Z_p.
     *
     * <p>Each sector takes the first 8 bytes of its slice (zero-padded if shorter)
     * and reduces them modulo p. This is a deterministic, reversible bijection for
     * our purposes — the sectors are only used in the tag equation, not for decoding.
     *
     * @param fragment raw bytes of one encoded block fragment
     * @param s        number of sectors ({@link PorSecretKey#NUM_SECTORS})
     * @return array of {@code s} values, each in [0, p)
     */
    public static long[] toSectors(byte[] fragment, int s) {
        long[] sectors = new long[s];
        int sectorSize = (fragment.length + s - 1) / s; // ceiling division
        for (int j = 0; j < s; j++) {
            int start = j * sectorSize;
            if (start >= fragment.length) break; // trailing empty sectors stay 0
            // Take up to 8 bytes from this sector slice
            long val = 0;
            int limit = Math.min(start + 8, Math.min(start + sectorSize, fragment.length));
            for (int b = start; b < limit; b++) {
                val = (val << 8) | (fragment[b] & 0xFFL);
            }
            // Pad remaining bytes with 0 (already zero-initialized)
            sectors[j] = val % PorSecretKey.P;
        }
        return sectors;
    }

    // -----------------------------------------------------------------------
    // Tag computation
    // -----------------------------------------------------------------------

    /**
     * Computes the Shacham-Waters tag for one block fragment:
     * <pre>
     *   σᵢ = f_k(i) + Σⱼ m_{i,j} · u_j   (mod p)
     * </pre>
     *
     * @param sk            the client's secret key
     * @param blockIndex    0-based block index {@code i}
     * @param fragmentBytes raw bytes of the encoded fragment for block {@code i}
     * @return tag value σᵢ ∈ [0, p)
     */
    public static long computeTag(PorSecretKey sk, int blockIndex, byte[] fragmentBytes) {
        long[] sectors = toSectors(fragmentBytes, sk.u().length);
        long tag = prf(sk.prf_k(), blockIndex);          // f_k(i)
        for (int j = 0; j < sectors.length; j++) {
            tag = addMod(tag, mulMod(sectors[j], sk.u()[j]));
        }
        return tag;
    }

    // -----------------------------------------------------------------------
    // Z_p arithmetic helpers
    // -----------------------------------------------------------------------

    /**
     * Modular addition: {@code (a + b) mod p} where p = 2^61 - 1.
     *
     * <p>Both inputs must be in [0, p). Result is in [0, p).
     */
    public static long addMod(long a, long b) {
        long sum = a + b;
        // If the sum >= p (or overflowed into negative), subtract p
        if (sum >= PorSecretKey.P || sum < 0) {
            sum -= PorSecretKey.P;
        }
        return sum;
    }

    /**
     * Modular multiplication: {@code (a * b) mod p} where p = 2^61 - 1.
     *
     * <p>Uses {@link Math#multiplyHigh(long, long)} (Java 9+) to compute the high
     * 64 bits of the 128-bit product without BigInteger, then applies a fast
     * Mersenne reduction.
     *
     * <p>Both inputs must be in [0, p). Result is in [0, p).
     */
    public static long mulMod(long a, long b) {
        // Full 128-bit product: hi * 2^64 + lo = a * b
        long lo = a * b;
        long hi = Math.multiplyHigh(a, b);

        // Reduce mod p = 2^61 - 1.
        // Key identity: 2^61 ≡ 1 (mod p), so 2^64 = 2^61 * 2^3 ≡ 8 (mod p).
        // Therefore: hi * 2^64 + lo ≡ hi * 8 + lo (mod p).
        //
        // We split lo into its lower 61 bits and the top 3 bits:
        //   lo_low  = lo & P          (lower 61 bits, already < 2p since lo is 64-bit)
        //   lo_high = lo >>> 61       (upper 3 bits of lo, i.e. lo / 2^61)
        //
        // Result ≡ lo_low + lo_high + hi * 8  (mod p)
        long lo_low  = lo & PorSecretKey.P;   // lower 61 bits
        long lo_high = lo >>> 61;             // bits 61-63 of lo (0..7)
        long r = lo_low + lo_high + (hi << 3) + (hi >>> 58);

        // r may be slightly >= p; a single conditional subtraction suffices
        if (r >= PorSecretKey.P) r -= PorSecretKey.P;
        if (r < 0)               r += PorSecretKey.P;  // guard against edge cases
        return r;
    }
}

package com.objectstore.client;

import com.objectstore.common.HashUtil;
import com.objectstore.common.HomomorphicFingerprint;
import java.util.Arrays;
import java.util.Objects;

/**
 * Fingerprinted Cross-Checksum (FPCC) — the core integrity mechanism from
 * Hendricks, Ganger & Reiter (PODC 2007).
 *
 * An FPCC for n encoded fragments consists of:
 *   - cc[0..n-1]: SHA-256 hash of each encoded fragment
 *   - fp[0..k-1]: homomorphic fingerprint of each source/data fragment
 *
 * A fragment at index i is verified by checking:
 *   1. SHA-256(fragment) == cc[i]                                    (hash check)
 *   2. fp(fragment) == encode_i(fp[source_0], ..., fp[source_{k-1}]) (fingerprint check)
 *
 * If both pass, the fragment is (with high probability) the correct erasure-coded
 * fragment. If either fails, the fragment is treated as an erasure.
 */
public final class FingerprintedCrossChecksum {

    private final String[] crossChecksum;      // SHA-256 of each encoded fragment
    private final byte[][] sourceFingerprints; // homomorphic fingerprints of source fragments
    private final byte[] seed;                 // random oracle seed derived from cc[]
    private final int fingerprintBytes;

    public FingerprintedCrossChecksum(
            String[] crossChecksum, byte[][] sourceFingerprints, byte[] seed, int fingerprintBytes) {
        this.crossChecksum      = Arrays.copyOf(crossChecksum, crossChecksum.length);
        this.sourceFingerprints = deepCopy(sourceFingerprints);
        this.seed               = Arrays.copyOf(seed, seed.length);
        this.fingerprintBytes   = fingerprintBytes;
    }

    /** Creates an FPCC from the source and encoded fragments. */
    public static FingerprintedCrossChecksum create(
            byte[][] sourceFragments, byte[][] encodedFragments, LinearErasureCodec codec) {
        // Compute SHA-256 of each encoded fragment
        String[] cc = new String[encodedFragments.length];
        for (int i = 0; i < encodedFragments.length; i++)
            cc[i] = HashUtil.sha256Hex(encodedFragments[i]);

        // Derive seed from all hashes (random oracle)
        byte[] seed = HomomorphicFingerprint.deriveSeed(cc);
        HomomorphicFingerprint fp = new HomomorphicFingerprint(seed);

        // Compute homomorphic fingerprints of source fragments
        byte[][] sourceFp = new byte[sourceFragments.length][];
        for (int i = 0; i < sourceFragments.length; i++)
            sourceFp[i] = fp.fingerprint(sourceFragments[i]);

        return new FingerprintedCrossChecksum(cc, sourceFp, seed, fp.fingerprintBytes());
    }

    /**
     * Verifies a fragment at a given index against this FPCC.
     * Returns true if both the hash and fingerprint checks pass.
     */
    public boolean verifyFragment(int fragmentIndex, byte[] fragmentBytes, LinearErasureCodec codec) {
        Objects.requireNonNull(fragmentBytes);
        // Check 1: SHA-256 hash
        if (!crossChecksum[fragmentIndex].equals(HashUtil.sha256Hex(fragmentBytes)))
            return false;
        // Check 2: homomorphic fingerprint
        HomomorphicFingerprint fp = new HomomorphicFingerprint(seed, fingerprintBytes);
        byte[] actual   = fp.fingerprint(fragmentBytes);
        byte[] expected = fp.encodeFingerprint(codec.codingRow(fragmentIndex), sourceFingerprints);
        return Arrays.equals(expected, actual);
    }

    /** Returns the expected SHA-256 hash for fragment i. */
    public String expectedHash(int index) {
        return crossChecksum[index];
    }

    /** Computes the expected encoded fingerprint for fragment i. */
    public byte[] expectedEncodedFingerprint(int index, LinearErasureCodec codec) {
        return new HomomorphicFingerprint(seed, fingerprintBytes)
                .encodeFingerprint(codec.codingRow(index), sourceFingerprints);
    }

    public int totalFragments()        { return crossChecksum.length; }
    public int sourceFragments()       { return sourceFingerprints.length; }
    public String[] crossChecksum()    { return Arrays.copyOf(crossChecksum, crossChecksum.length); }
    public byte[][] sourceFingerprints() { return deepCopy(sourceFingerprints); }
    public byte[] seed()               { return Arrays.copyOf(seed, seed.length); }
    public int fingerprintBytes()      { return fingerprintBytes; }

    private static byte[][] deepCopy(byte[][] source) {
        byte[][] copy = new byte[source.length][];
        for (int i = 0; i < source.length; i++)
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        return copy;
    }
}

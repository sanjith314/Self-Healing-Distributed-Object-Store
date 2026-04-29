package com.objectstore.client;

import com.objectstore.common.HashUtil;

import java.util.Arrays;
import java.util.Objects;

/**
 * Paper-aligned integrity metadata for an encoded object.
 *
 * <p>{@code cc[]} records the cryptographic hash of every encoded fragment.
 * {@code sourceFingerprints[]} records homomorphic fingerprints of the source
 * fragments. A candidate encoded fragment is valid only when both its hash and
 * its encoded fingerprint match.
 */
public final class FingerprintedCrossChecksum {

    private final String[] crossChecksum;
    private final byte[][] sourceFingerprints;
    private final byte[] seed;
    private final int fingerprintBytes;

    public FingerprintedCrossChecksum(
            String[] crossChecksum, byte[][] sourceFingerprints, byte[] seed, int fingerprintBytes) {
        this.crossChecksum = Arrays.copyOf(Objects.requireNonNull(crossChecksum), crossChecksum.length);
        this.sourceFingerprints = deepCopy(Objects.requireNonNull(sourceFingerprints));
        this.seed = Arrays.copyOf(Objects.requireNonNull(seed), seed.length);
        this.fingerprintBytes = fingerprintBytes;
    }

    public static FingerprintedCrossChecksum create(
            byte[][] sourceFragments, byte[][] encodedFragments, LinearErasureCodec codec) {
        String[] cc = new String[encodedFragments.length];
        for (int i = 0; i < encodedFragments.length; i++) {
            cc[i] = HashUtil.sha256Hex(encodedFragments[i]);
        }

        byte[] seed = HomomorphicFingerprint.deriveSeed(cc);
        HomomorphicFingerprint fingerprint = new HomomorphicFingerprint(seed);
        byte[][] sourceFp = new byte[sourceFragments.length][];
        for (int i = 0; i < sourceFragments.length; i++) {
            sourceFp[i] = fingerprint.fingerprint(sourceFragments[i]);
        }
        return new FingerprintedCrossChecksum(cc, sourceFp, seed, fingerprint.fingerprintBytes());
    }

    public boolean verifyFragment(int fragmentIndex, byte[] fragmentBytes, LinearErasureCodec codec) {
        Objects.requireNonNull(fragmentBytes, "fragmentBytes must not be null");
        if (fragmentIndex < 0 || fragmentIndex >= crossChecksum.length) {
            throw new IllegalArgumentException("fragmentIndex out of range: " + fragmentIndex);
        }
        if (!crossChecksum[fragmentIndex].equals(HashUtil.sha256Hex(fragmentBytes))) {
            return false;
        }

        HomomorphicFingerprint fingerprint = new HomomorphicFingerprint(seed, fingerprintBytes);
        byte[] actual = fingerprint.fingerprint(fragmentBytes);
        byte[] expected = fingerprint.encodeFingerprint(codec.codingRow(fragmentIndex), sourceFingerprints);
        return Arrays.equals(expected, actual);
    }

    public String expectedHash(int index) {
        return crossChecksum[index];
    }

    public byte[] expectedEncodedFingerprint(int index, LinearErasureCodec codec) {
        return new HomomorphicFingerprint(seed, fingerprintBytes)
                .encodeFingerprint(codec.codingRow(index), sourceFingerprints);
    }

    public int totalFragments() {
        return crossChecksum.length;
    }

    public int sourceFragments() {
        return sourceFingerprints.length;
    }

    public String[] crossChecksum() {
        return Arrays.copyOf(crossChecksum, crossChecksum.length);
    }

    public byte[][] sourceFingerprints() {
        return deepCopy(sourceFingerprints);
    }

    public byte[] seed() {
        return Arrays.copyOf(seed, seed.length);
    }

    public int fingerprintBytes() {
        return fingerprintBytes;
    }

    private static byte[][] deepCopy(byte[][] source) {
        byte[][] copy = new byte[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return copy;
    }
}

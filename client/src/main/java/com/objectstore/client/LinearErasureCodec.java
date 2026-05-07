package com.objectstore.client;

import com.objectstore.common.GaloisField256;
import java.util.Arrays;

/**
 * (n, k) systematic Vandermonde erasure codec over GF(2^8).
 * 
 * The first k rows of the coding matrix are the identity (systematic code),
 * so the first k encoded fragments are just the original data chunks.
 * The remaining (n - k) rows are Vandermonde-style parity rows.
 * 
 * Decoding picks any k surviving fragments, extracts their rows from the
 * coding matrix, inverts via GF(2^8) Gaussian elimination, and multiplies.
 * 
 * Default: (6, 4) — 4 data + 2 parity. Tolerates any 2 fragment losses.
 */
public final class LinearErasureCodec {

    public static final int SOURCE_FRAGMENTS = 4;
    public static final int PARITY_FRAGMENTS = 2;
    public static final int TOTAL_FRAGMENTS  = SOURCE_FRAGMENTS + PARITY_FRAGMENTS;

    private final int sourceFragments;
    private final int parityFragments;
    private final int totalFragments;
    private final byte[][] codingMatrix;

    public LinearErasureCodec() {
        this(SOURCE_FRAGMENTS, PARITY_FRAGMENTS);
    }

    public LinearErasureCodec(int sourceFragments, int parityFragments) {
        this.sourceFragments = sourceFragments;
        this.parityFragments = parityFragments;
        this.totalFragments  = sourceFragments + parityFragments;
        this.codingMatrix    = buildCodingMatrix(sourceFragments, totalFragments);
    }

    /** Encodes data into n fragments (k data + parity). */
    public EncodedBlock encode(byte[] data) {
        if (data == null || data.length == 0)
            throw new IllegalArgumentException("data must not be null or empty");

        int fragSize = fragmentSize(data.length, sourceFragments);
        byte[][] source  = splitSourceFragments(data, fragSize);
        byte[][] encoded = encodeFragments(source);
        return new EncodedBlock(source, encoded, data.length, fragSize);
    }

    /** Decodes from any k present fragments back to the original data. */
    public byte[] decode(byte[][] fragments, boolean[] present, long originalLength) {
        int[] selected = selectPresentFragments(fragments, present);
        int fragSize = fragments[selected[0]].length;

        // Build sub-matrix and sub-fragment array for the selected k fragments
        byte[][] subMatrix = new byte[sourceFragments][sourceFragments];
        byte[][] subFrags  = new byte[sourceFragments][fragSize];
        for (int r = 0; r < sourceFragments; r++) {
            System.arraycopy(codingMatrix[selected[r]], 0, subMatrix[r], 0, sourceFragments);
            System.arraycopy(fragments[selected[r]], 0, subFrags[r], 0, fragSize);
        }

        // Invert and multiply to recover original source fragments
        byte[][] inverse = GaloisField256.invertMatrix(subMatrix);
        byte[][] source  = multiplyMatrixByFragments(inverse, subFrags, fragSize);

        // Join and trim to original length
        byte[] reconstructed = joinSourceFragments(source, fragSize);
        int trimLen = originalLength <= 0 ? reconstructed.length : Math.toIntExact(originalLength);
        return Arrays.copyOf(reconstructed, trimLen);
    }

    /** Multiplies the full coding matrix by source fragments to get all n encoded fragments. */
    public byte[][] encodeFragments(byte[][] source) {
        return multiplyMatrixByFragments(codingMatrix, source, source[0].length);
    }

    /** Returns the coding matrix row for fragment index i (used for fingerprint verification). */
    public byte[] codingRow(int index) {
        return Arrays.copyOf(codingMatrix[index], sourceFragments);
    }

    public byte[][] codingMatrix() {
        byte[][] copy = new byte[totalFragments][sourceFragments];
        for (int i = 0; i < totalFragments; i++) copy[i] = codingRow(i);
        return copy;
    }

    public int sourceFragments()  { return sourceFragments; }
    public int parityFragments()  { return parityFragments; }
    public int totalFragments()   { return totalFragments; }

    /** Computes how big each fragment needs to be given a file length and k. */
    public static int fragmentSize(long fileLength, int sourceFragments) {
        return (int) ((fileLength + sourceFragments - 1) / sourceFragments);
    }

    // -- Private helpers --

    private byte[][] splitSourceFragments(byte[] data, int fragSize) {
        byte[][] source = new byte[sourceFragments][fragSize];
        for (int i = 0; i < sourceFragments; i++) {
            int start = i * fragSize;
            int len = Math.min(fragSize, Math.max(0, data.length - start));
            if (len > 0) System.arraycopy(data, start, source[i], 0, len);
        }
        return source;
    }

    private byte[] joinSourceFragments(byte[][] source, int fragSize) {
        byte[] joined = new byte[sourceFragments * fragSize];
        for (int i = 0; i < sourceFragments; i++)
            System.arraycopy(source[i], 0, joined, i * fragSize, fragSize);
        return joined;
    }

    private int[] selectPresentFragments(byte[][] fragments, boolean[] present) {
        int[] selected = new int[sourceFragments];
        int count = 0;
        for (int i = 0; i < totalFragments && count < sourceFragments; i++) {
            if (present[i] && fragments[i] != null) selected[count++] = i;
        }
        if (count < sourceFragments)
            throw new InsufficientFragmentsException(
                    "Need " + sourceFragments + " fragments but only " + count + " are present");
        return selected;
    }

    /**
     * Builds the coding matrix: identity rows for data, Vandermonde rows for parity.
     * Parity row r uses evaluation point (r - k + 1), so the matrix is always invertible
     * for any k-subset.
     */
    private static byte[][] buildCodingMatrix(int k, int n) {
        byte[][] matrix = new byte[n][k];
        for (int i = 0; i < k; i++) matrix[i][i] = 1; // identity for data rows
        for (int row = k; row < n; row++) {
            int x = row - k + 1;
            for (int col = 0; col < k; col++)
                matrix[row][col] = (byte) GaloisField256.power(x, col);
        }
        return matrix;
    }

    private static byte[][] multiplyMatrixByFragments(byte[][] matrix, byte[][] fragments, int fragSize) {
        byte[][] output = new byte[matrix.length][fragSize];
        for (int row = 0; row < matrix.length; row++)
            for (int src = 0; src < fragments.length; src++) {
                int coeff = matrix[row][src] & 0xFF;
                if (coeff == 0) continue;
                for (int off = 0; off < fragSize; off++)
                    output[row][off] ^= (byte) GaloisField256.multiply(coeff, fragments[src][off] & 0xFF);
            }
        return output;
    }

    /** Holds the result of encode(): source fragments, all encoded fragments, and metadata. */
    public record EncodedBlock(byte[][] sourceFragments, byte[][] encodedFragments,
                               long originalLength, int fragmentSize) {}

    /** Thrown when too few fragments survive for reconstruction. */
    public static class InsufficientFragmentsException extends RuntimeException {
        public InsufficientFragmentsException(String message) { super(message); }
    }
}

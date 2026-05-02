package com.objectstore.client;

import com.objectstore.common.GaloisField256;

import java.util.Arrays;

/**
 * Manual m-of-n linear erasure codec over GF(2^8).
 *
 * <p>The first {@code m} rows of the coding matrix are systematic identity rows.
 * Additional rows are Vandermonde-style parity rows. Decoding uses any
 * {@code m} verified fragments by inverting the corresponding rows.
 */
public final class LinearErasureCodec {

    public static final int SOURCE_FRAGMENTS = 4;
    public static final int PARITY_FRAGMENTS = 2;
    public static final int TOTAL_FRAGMENTS = SOURCE_FRAGMENTS + PARITY_FRAGMENTS;

    private final int sourceFragments;
    private final int parityFragments;
    private final int totalFragments;
    private final byte[][] codingMatrix;

    public LinearErasureCodec() {
        this(SOURCE_FRAGMENTS, PARITY_FRAGMENTS);
    }

    public LinearErasureCodec(int sourceFragments, int parityFragments) {
        if (sourceFragments < 1) {
            throw new IllegalArgumentException("sourceFragments must be >= 1");
        }
        if (parityFragments < 1) {
            throw new IllegalArgumentException("parityFragments must be >= 1");
        }
        if (sourceFragments + parityFragments > 255) {
            throw new IllegalArgumentException("total fragments must be <= 255");
        }
        this.sourceFragments = sourceFragments;
        this.parityFragments = parityFragments;
        this.totalFragments = sourceFragments + parityFragments;
        this.codingMatrix = buildCodingMatrix(sourceFragments, totalFragments);
    }

    public EncodedBlock encode(byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }

        int fragmentSize = fragmentSize(data.length, sourceFragments);
        byte[][] source = splitSourceFragments(data, fragmentSize);
        byte[][] encoded = encodeFragments(source);
        return new EncodedBlock(source, encoded, data.length, fragmentSize);
    }

    public byte[] decode(byte[][] fragments, boolean[] present, long originalLength) {
        if (fragments.length != totalFragments || present.length != totalFragments) {
            throw new IllegalArgumentException("fragments and present arrays must have length == totalFragments");
        }

        int[] selected = selectPresentFragments(fragments, present);
        int fragmentSize = fragments[selected[0]].length;
        byte[][] selectedMatrix = new byte[sourceFragments][sourceFragments];
        byte[][] selectedFragments = new byte[sourceFragments][fragmentSize];
        for (int row = 0; row < sourceFragments; row++) {
            System.arraycopy(codingMatrix[selected[row]], 0, selectedMatrix[row], 0, sourceFragments);
            System.arraycopy(fragments[selected[row]], 0, selectedFragments[row], 0, fragmentSize);
        }

        byte[][] inverse = GaloisField256.invertMatrix(selectedMatrix);
        byte[][] source = multiplyMatrixByFragments(inverse, selectedFragments, fragmentSize);
        byte[] reconstructed = joinSourceFragments(source, fragmentSize);
        int trimLength = originalLength <= 0 ? reconstructed.length : Math.toIntExact(originalLength);
        if (trimLength > reconstructed.length) {
            throw new IllegalArgumentException("originalLength exceeds reconstructed data length");
        }
        return Arrays.copyOf(reconstructed, trimLength);
    }

    public byte[][] encodeFragments(byte[][] source) {
        if (source.length != sourceFragments) {
            throw new IllegalArgumentException("source fragment count mismatch");
        }
        int fragmentSize = source[0].length;
        for (byte[] fragment : source) {
            if (fragment.length != fragmentSize) {
                throw new IllegalArgumentException("all source fragments must have the same length");
            }
        }
        return multiplyMatrixByFragments(codingMatrix, source, fragmentSize);
    }

    public byte[] codingRow(int index) {
        return Arrays.copyOf(codingMatrix[index], sourceFragments);
    }

    public byte[][] codingMatrix() {
        byte[][] copy = new byte[totalFragments][sourceFragments];
        for (int i = 0; i < totalFragments; i++) {
            copy[i] = codingRow(i);
        }
        return copy;
    }

    public int sourceFragments() {
        return sourceFragments;
    }

    public int parityFragments() {
        return parityFragments;
    }

    public int totalFragments() {
        return totalFragments;
    }

    public static int fragmentSize(long fileLength, int sourceFragments) {
        return (int) ((fileLength + sourceFragments - 1) / sourceFragments);
    }

    private byte[][] splitSourceFragments(byte[] data, int fragmentSize) {
        byte[][] source = new byte[sourceFragments][fragmentSize];
        for (int i = 0; i < sourceFragments; i++) {
            int start = i * fragmentSize;
            int len = Math.min(fragmentSize, Math.max(0, data.length - start));
            if (len > 0) {
                System.arraycopy(data, start, source[i], 0, len);
            }
        }
        return source;
    }

    private byte[] joinSourceFragments(byte[][] source, int fragmentSize) {
        byte[] joined = new byte[sourceFragments * fragmentSize];
        for (int i = 0; i < sourceFragments; i++) {
            System.arraycopy(source[i], 0, joined, i * fragmentSize, fragmentSize);
        }
        return joined;
    }

    private int[] selectPresentFragments(byte[][] fragments, boolean[] present) {
        int[] selected = new int[sourceFragments];
        int count = 0;
        int fragmentSize = -1;
        for (int i = 0; i < totalFragments && count < sourceFragments; i++) {
            if (!present[i] || fragments[i] == null) {
                continue;
            }
            if (fragmentSize < 0) {
                fragmentSize = fragments[i].length;
            } else if (fragments[i].length != fragmentSize) {
                throw new IllegalArgumentException("present fragments must have the same length");
            }
            selected[count++] = i;
        }
        if (count < sourceFragments) {
            throw new InsufficientFragmentsException(
                    "Need at least " + sourceFragments + " verified fragments but only " + count + " are present");
        }
        return selected;
    }

    private static byte[][] buildCodingMatrix(int sourceFragments, int totalFragments) {
        byte[][] matrix = new byte[totalFragments][sourceFragments];
        for (int i = 0; i < sourceFragments; i++) {
            matrix[i][i] = 1;
        }
        for (int row = sourceFragments; row < totalFragments; row++) {
            int x = row - sourceFragments + 1;
            for (int col = 0; col < sourceFragments; col++) {
                matrix[row][col] = (byte) GaloisField256.power(x, col);
            }
        }
        return matrix;
    }

    private static byte[][] multiplyMatrixByFragments(byte[][] matrix, byte[][] fragments, int fragmentSize) {
        byte[][] output = new byte[matrix.length][fragmentSize];
        for (int row = 0; row < matrix.length; row++) {
            for (int source = 0; source < fragments.length; source++) {
                int coefficient = matrix[row][source] & 0xFF;
                if (coefficient == 0) {
                    continue;
                }
                for (int offset = 0; offset < fragmentSize; offset++) {
                    int product = GaloisField256.multiply(coefficient, fragments[source][offset] & 0xFF);
                    output[row][offset] ^= (byte) product;
                }
            }
        }
        return output;
    }

    public record EncodedBlock(byte[][] sourceFragments, byte[][] encodedFragments,
                               long originalLength, int fragmentSize) {}

    public static class InsufficientFragmentsException extends RuntimeException {
        public InsufficientFragmentsException(String message) {
            super(message);
        }
    }
}

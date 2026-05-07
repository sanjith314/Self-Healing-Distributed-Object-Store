package com.objectstore.common;

/**
 * Arithmetic over GF(2^8) with the primitive polynomial x^8 + x^4 + x^3 + x^2 + 1.
 * Values are unsigned bytes stored in ints. Addition = XOR, multiplication uses
 * the standard shift-and-reduce approach.
 */
public final class GaloisField256 {

    private static final int PRIMITIVE_POLYNOMIAL = 0x11D;

    public static int add(int a, int b) {
        return (a ^ b) & 0xFF;
    }

    public static int subtract(int a, int b) {
        return add(a, b); // same as add in GF(2^8)
    }

    public static int multiply(int a, int b) {
        a &= 0xFF;
        b &= 0xFF;
        int product = 0;
        while (b != 0) {
            if ((b & 1) != 0) product ^= a;
            b >>>= 1;
            a <<= 1;
            if ((a & 0x100) != 0) a ^= PRIMITIVE_POLYNOMIAL;
        }
        return product & 0xFF;
    }

    public static int divide(int a, int b) {
        if ((b & 0xFF) == 0) throw new ArithmeticException("division by zero in GF(256)");
        return multiply(a, inverse(b));
    }

    public static int power(int value, int exponent) {
        int result = 1;
        int base = value & 0xFF;
        while (exponent > 0) {
            if ((exponent & 1) != 0) result = multiply(result, base);
            base = multiply(base, base);
            exponent >>>= 1;
        }
        return result;
    }

    public static int inverse(int value) {
        value &= 0xFF;
        if (value == 0) throw new ArithmeticException("zero has no inverse in GF(256)");
        return power(value, 254); // Fermat's little theorem: a^(q-2) = a^(-1)
    }

    /**
     * Inverts an n×n matrix over GF(2^8) using Gauss-Jordan elimination.
     * Used by the erasure codec to decode from any k surviving fragments.
     */
    public static byte[][] invertMatrix(byte[][] matrix) {
        int n = matrix.length;
        byte[][] aug = new byte[n][n * 2];
        for (int r = 0; r < n; r++) {
            System.arraycopy(matrix[r], 0, aug[r], 0, n);
            aug[r][n + r] = 1; // identity on the right
        }

        for (int col = 0; col < n; col++) {
            // find pivot
            int pivot = col;
            while (pivot < n && (aug[pivot][col] & 0xFF) == 0) pivot++;
            if (pivot == n) throw new IllegalArgumentException("matrix is singular");
            if (pivot != col) { byte[] tmp = aug[pivot]; aug[pivot] = aug[col]; aug[col] = tmp; }

            // scale pivot row
            int inv = inverse(aug[col][col] & 0xFF);
            for (int j = 0; j < n * 2; j++)
                aug[col][j] = (byte) multiply(aug[col][j] & 0xFF, inv);

            // eliminate other rows
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                int factor = aug[r][col] & 0xFF;
                if (factor == 0) continue;
                for (int j = 0; j < n * 2; j++)
                    aug[r][j] = (byte) subtract(aug[r][j] & 0xFF, multiply(factor, aug[col][j] & 0xFF));
            }
        }

        byte[][] result = new byte[n][n];
        for (int r = 0; r < n; r++)
            System.arraycopy(aug[r], n, result[r], 0, n);
        return result;
    }

    private GaloisField256() {}
}

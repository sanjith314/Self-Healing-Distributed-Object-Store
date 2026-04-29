package com.objectstore.client;

/**
 * Arithmetic over GF(2^8) using the common primitive polynomial x^8+x^4+x^3+x^2+1.
 *
 * <p>Values are represented as unsigned bytes stored in {@code int}s. Addition is
 * XOR; multiplication and inversion are implemented directly so the erasure
 * codec does not depend on an external Reed-Solomon library.
 */
public final class GaloisField256 {

    private static final int PRIMITIVE_POLYNOMIAL = 0x11D;

    public static int add(int a, int b) {
        return (a ^ b) & 0xFF;
    }

    public static int subtract(int a, int b) {
        return add(a, b);
    }

    public static int multiply(int a, int b) {
        a &= 0xFF;
        b &= 0xFF;
        int product = 0;
        while (b != 0) {
            if ((b & 1) != 0) {
                product ^= a;
            }
            b >>>= 1;
            a <<= 1;
            if ((a & 0x100) != 0) {
                a ^= PRIMITIVE_POLYNOMIAL;
            }
        }
        return product & 0xFF;
    }

    public static int divide(int a, int b) {
        if ((b & 0xFF) == 0) {
            throw new ArithmeticException("division by zero in GF(256)");
        }
        return multiply(a, inverse(b));
    }

    public static int power(int value, int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException("exponent must be >= 0");
        }
        int result = 1;
        int base = value & 0xFF;
        int exp = exponent;
        while (exp > 0) {
            if ((exp & 1) != 0) {
                result = multiply(result, base);
            }
            base = multiply(base, base);
            exp >>>= 1;
        }
        return result;
    }

    public static int inverse(int value) {
        value &= 0xFF;
        if (value == 0) {
            throw new ArithmeticException("zero has no inverse in GF(256)");
        }
        return power(value, 254);
    }

    public static byte[][] invertMatrix(byte[][] matrix) {
        int n = matrix.length;
        if (n == 0 || matrix[0].length != n) {
            throw new IllegalArgumentException("matrix must be non-empty and square");
        }

        byte[][] augmented = new byte[n][n * 2];
        for (int row = 0; row < n; row++) {
            if (matrix[row].length != n) {
                throw new IllegalArgumentException("matrix must be square");
            }
            System.arraycopy(matrix[row], 0, augmented[row], 0, n);
            augmented[row][n + row] = 1;
        }

        for (int col = 0; col < n; col++) {
            int pivot = col;
            while (pivot < n && (augmented[pivot][col] & 0xFF) == 0) {
                pivot++;
            }
            if (pivot == n) {
                throw new IllegalArgumentException("matrix is singular");
            }
            if (pivot != col) {
                byte[] tmp = augmented[pivot];
                augmented[pivot] = augmented[col];
                augmented[col] = tmp;
            }

            int pivotValue = augmented[col][col] & 0xFF;
            int inverse = inverse(pivotValue);
            for (int j = 0; j < n * 2; j++) {
                augmented[col][j] = (byte) multiply(augmented[col][j] & 0xFF, inverse);
            }

            for (int row = 0; row < n; row++) {
                if (row == col) {
                    continue;
                }
                int factor = augmented[row][col] & 0xFF;
                if (factor == 0) {
                    continue;
                }
                for (int j = 0; j < n * 2; j++) {
                    int scaled = multiply(factor, augmented[col][j] & 0xFF);
                    augmented[row][j] = (byte) subtract(augmented[row][j] & 0xFF, scaled);
                }
            }
        }

        byte[][] inverse = new byte[n][n];
        for (int row = 0; row < n; row++) {
            System.arraycopy(augmented[row], n, inverse[row], 0, n);
        }
        return inverse;
    }

    private GaloisField256() {}
}

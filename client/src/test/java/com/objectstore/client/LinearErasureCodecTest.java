package com.objectstore.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LinearErasureCodecTest {

    private static final LinearErasureCodec CODEC = new LinearErasureCodec();
    private static final int M = LinearErasureCodec.SOURCE_FRAGMENTS;
    private static final int N = LinearErasureCodec.TOTAL_FRAGMENTS;

    @Test
    @DisplayName("Default constants are m=4, parity=2, n=6")
    void constants_areCorrect() {
        assertEquals(4, LinearErasureCodec.SOURCE_FRAGMENTS);
        assertEquals(2, LinearErasureCodec.PARITY_FRAGMENTS);
        assertEquals(6, LinearErasureCodec.TOTAL_FRAGMENTS);
    }

    @Test
    @DisplayName("GF(256) arithmetic has identity, inverse, and distributive behavior")
    void gf256_arithmetic() {
        assertEquals(0x57 ^ 0x83, GaloisField256.add(0x57, 0x83));
        assertEquals(0x57, GaloisField256.multiply(0x57, 1));
        assertEquals(0, GaloisField256.multiply(0x57, 0));
        int value = 0xC7;
        assertEquals(1, GaloisField256.multiply(value, GaloisField256.inverse(value)));
        int left = GaloisField256.multiply(0x11, GaloisField256.add(0x22, 0x33));
        int right = GaloisField256.add(
                GaloisField256.multiply(0x11, 0x22),
                GaloisField256.multiply(0x11, 0x33));
        assertEquals(left, right);
    }

    @Test
    @DisplayName("Encode and decode round-trip with no missing fragments")
    void roundTrip_noMissingFragments() {
        byte[] original = bytes("manual linear coding with homomorphic fingerprints");
        LinearErasureCodec.EncodedBlock block = CODEC.encode(original);
        boolean[] present = allPresent();

        byte[] recovered = CODEC.decode(block.encodedFragments(), present, original.length);

        assertArrayEquals(original, recovered);
    }

    @Test
    @DisplayName("Decode succeeds for every subset of m fragments")
    void decode_allValidSubsets() {
        byte[] original = new byte[1025];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 31 + 7);
        }
        LinearErasureCodec.EncodedBlock block = CODEC.encode(original);

        for (int mask = 0; mask < (1 << N); mask++) {
            if (Integer.bitCount(mask) != M) {
                continue;
            }
            byte[][] fragments = copyFragments(block.encodedFragments());
            boolean[] present = new boolean[N];
            for (int i = 0; i < N; i++) {
                present[i] = (mask & (1 << i)) != 0;
                if (!present[i]) {
                    fragments[i] = null;
                }
            }
            assertArrayEquals(original, CODEC.decode(fragments, present, original.length),
                    "subset mask " + mask + " must decode");
        }
    }

    @Test
    @DisplayName("Decode fails with fewer than m fragments")
    void decode_tooFewFragments_throws() {
        byte[] original = bytes("not enough fragments");
        LinearErasureCodec.EncodedBlock block = CODEC.encode(original);
        boolean[] present = allPresent();
        byte[][] fragments = copyFragments(block.encodedFragments());
        present[0] = false;
        present[1] = false;
        present[2] = false;
        fragments[0] = null;
        fragments[1] = null;
        fragments[2] = null;

        assertThrows(LinearErasureCodec.InsufficientFragmentsException.class,
                () -> CODEC.decode(fragments, present, original.length));
    }

    @Test
    @DisplayName("Fragment size rounds up for uneven source lengths")
    void fragmentSize_roundsUp() {
        assertEquals(3, LinearErasureCodec.fragmentSize(9, 4));
        assertEquals(2, LinearErasureCodec.fragmentSize(8, 4));
        assertEquals(1, LinearErasureCodec.fragmentSize(1, 4));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean[] allPresent() {
        boolean[] present = new boolean[N];
        Arrays.fill(present, true);
        return present;
    }

    private static byte[][] copyFragments(byte[][] fragments) {
        byte[][] copy = new byte[fragments.length][];
        for (int i = 0; i < fragments.length; i++) {
            copy[i] = Arrays.copyOf(fragments[i], fragments[i].length);
        }
        return copy;
    }
}

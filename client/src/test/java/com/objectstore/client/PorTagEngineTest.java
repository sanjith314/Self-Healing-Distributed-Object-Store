package com.objectstore.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PorTagEngine} and {@link PorSecretKey}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>PRF produces a stable, deterministic value for known inputs.</li>
 *   <li>Tag equation σᵢ = f_k(i) + Σⱼ mᵢ,ⱼ·uⱼ (mod p) holds.</li>
 *   <li>Z_p arithmetic helpers addMod / mulMod are correct at boundary values.</li>
 *   <li>PorSecretKey save() / load() round-trip is lossless.</li>
 *   <li>Tags change when block data changes (sanity).</li>
 * </ol>
 */
class PorTagEngineTest {

    private static final long P = PorSecretKey.P; // 2^61 - 1

    // -----------------------------------------------------------------------
    // PRF tests
    // -----------------------------------------------------------------------

    @Test
    void prf_isInRange() {
        byte[] k = new byte[32]; // all-zero key
        for (int i = 0; i < 20; i++) {
            long v = PorTagEngine.prf(k, i);
            assertTrue(v >= 0 && v < P,
                    "PRF output out of range [0, p) for i=" + i + ": " + v);
        }
    }

    @Test
    void prf_isDeterministic() {
        byte[] k = "test-key-32-bytes-0000000000000".getBytes();
        long v1 = PorTagEngine.prf(k, 42);
        long v2 = PorTagEngine.prf(k, 42);
        assertEquals(v1, v2, "PRF must be deterministic");
    }

    @Test
    void prf_variesByIndex() {
        byte[] k = new byte[32];
        long v0 = PorTagEngine.prf(k, 0);
        long v1 = PorTagEngine.prf(k, 1);
        assertNotEquals(v0, v1, "PRF should produce different values for different indices");
    }

    // -----------------------------------------------------------------------
    // Tag equation tests
    // -----------------------------------------------------------------------

    @Test
    void computeTag_verificationEquationHolds() {
        PorSecretKey sk = PorSecretKey.generate();
        int blockIndex = 3;
        byte[] fragment = "Hello, Shacham-Waters!".getBytes();

        long sigma = PorTagEngine.computeTag(sk, blockIndex, fragment);

        // Re-derive the expected value manually
        long expected = PorTagEngine.prf(sk.prf_k(), blockIndex);
        long[] sectors = PorTagEngine.toSectors(fragment, sk.u().length);
        for (int j = 0; j < sectors.length; j++) {
            expected = PorTagEngine.addMod(expected, PorTagEngine.mulMod(sectors[j], sk.u()[j]));
        }

        assertEquals(expected, sigma,
                "Tag equation σᵢ = f_k(i) + Σⱼ m_{i,j}·u_j (mod p) must hold");
    }

    @Test
    void computeTag_isDeterministic() {
        PorSecretKey sk = PorSecretKey.generate();
        byte[] fragment = new byte[512];
        for (int i = 0; i < fragment.length; i++) fragment[i] = (byte) i;

        long t1 = PorTagEngine.computeTag(sk, 0, fragment);
        long t2 = PorTagEngine.computeTag(sk, 0, fragment);
        assertEquals(t1, t2, "Tag computation must be deterministic");
    }

    @Test
    void computeTag_changesWithData() {
        PorSecretKey sk = PorSecretKey.generate();
        byte[] fragment1 = "block A".getBytes();
        byte[] fragment2 = "block B".getBytes();

        long t1 = PorTagEngine.computeTag(sk, 0, fragment1);
        long t2 = PorTagEngine.computeTag(sk, 0, fragment2);
        assertNotEquals(t1, t2, "Different data must (almost certainly) produce different tags");
    }

    @Test
    void computeTag_changesWithIndex() {
        PorSecretKey sk = PorSecretKey.generate();
        byte[] fragment = "same data".getBytes();

        long t0 = PorTagEngine.computeTag(sk, 0, fragment);
        long t1 = PorTagEngine.computeTag(sk, 1, fragment);
        assertNotEquals(t0, t1, "Different block index must produce different tag");
    }

    @Test
    void computeTag_isInRange() {
        PorSecretKey sk = PorSecretKey.generate();
        byte[] fragment = new byte[1024];
        long sigma = PorTagEngine.computeTag(sk, 0, fragment);
        assertTrue(sigma >= 0 && sigma < P,
                "Tag must be in [0, p): " + sigma);
    }

    // -----------------------------------------------------------------------
    // Z_p arithmetic tests
    // -----------------------------------------------------------------------

    @Test
    void addMod_basicValues() {
        assertEquals(5L, PorTagEngine.addMod(2L, 3L));
        assertEquals(0L, PorTagEngine.addMod(P - 1, 1L));   // wraps
        assertEquals(1L, PorTagEngine.addMod(P - 1, 2L));   // wraps with remainder
    }

    @Test
    void mulMod_basicValues() {
        assertEquals(0L, PorTagEngine.mulMod(0L, 12345L));
        assertEquals(0L, PorTagEngine.mulMod(12345L, 0L));
        assertEquals(1L, PorTagEngine.mulMod(1L, 1L));
        assertEquals(6L, PorTagEngine.mulMod(2L, 3L));
    }

    @Test
    void mulMod_reducesModP() {
        // (p-1) * (p-1) mod p = 1
        long pMinus1 = P - 1;
        long result = PorTagEngine.mulMod(pMinus1, pMinus1);
        assertEquals(1L, result,
                "(p-1)*(p-1) mod p must equal 1");
    }

    @Test
    void mulMod_largeValues_noOverflow() {
        // Use large values near p; result must stay in [0, p)
        long a = P - 2;
        long b = P - 3;
        long result = PorTagEngine.mulMod(a, b);
        assertTrue(result >= 0 && result < P,
                "mulMod result out of range: " + result);
        // Verify with BigInteger
        java.math.BigInteger ba = java.math.BigInteger.valueOf(a);
        java.math.BigInteger bb = java.math.BigInteger.valueOf(b);
        java.math.BigInteger bp = java.math.BigInteger.valueOf(P);
        long expected = ba.multiply(bb).mod(bp).longValueExact();
        assertEquals(expected, result);
    }

    // -----------------------------------------------------------------------
    // PorSecretKey persistence tests
    // -----------------------------------------------------------------------

    @Test
    void secretKey_generateIsInRange() {
        PorSecretKey sk = PorSecretKey.generate();
        assertTrue(sk.alpha() >= 0 && sk.alpha() < P, "alpha must be in [0, p)");
        assertEquals(PorSecretKey.NUM_SECTORS, sk.u().length);
        for (long uj : sk.u()) {
            assertTrue(uj >= 0 && uj < P, "u_j must be in [0, p): " + uj);
        }
        assertEquals(32, sk.prf_k().length);
    }

    @Test
    void secretKey_saveAndLoad_roundTrip(@TempDir Path tempDir) throws IOException {
        PorSecretKey original = PorSecretKey.generate();
        Path keyFile = tempDir.resolve("test.porkey");
        original.save(keyFile);

        PorSecretKey loaded = PorSecretKey.load(keyFile);

        assertArrayEquals(original.prf_k(), loaded.prf_k(), "prf_k must round-trip exactly");
        assertEquals(original.alpha(), loaded.alpha(), "alpha must round-trip exactly");
        assertArrayEquals(original.u(), loaded.u(), "u[] must round-trip exactly");
    }
}

package com.objectstore.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 unit tests for {@link ReedSolomonHelper}.
 *
 * <p>Every test runs entirely in-process — no network connections needed.
 * The test coverage mirrors the Phase 3 checklist in {@code AGENTS.md}:
 *
 * <ul>
 *   <li>Encode → decode round-trip (no erasures)</li>
 *   <li>Decode tolerating exactly {@code n - k} erasures (maximum tolerance)</li>
 *   <li>Decode with a hash-mismatch erasure (the "mismatch = erasure flag" path)</li>
 *   <li>Failure to decode when more than {@code n - k} shards are missing</li>
 *   <li>Correct zero-padding trim using {@code originalLength}</li>
 *   <li>Default parameter constants</li>
 * </ul>
 */
class ReedSolomonHelperTest {

    // Default parameters: (6, 4) — 4 data + 2 parity shards
    private static final int K = ReedSolomonHelper.DATA_SHARDS;     // 4
    private static final int P = ReedSolomonHelper.PARITY_SHARDS;   // 2
    private static final int N = ReedSolomonHelper.TOTAL_SHARDS;    // 6

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Default constants are K=4, P=2, N=6")
    void constants_areCorrect() {
        assertEquals(4, K, "DATA_SHARDS must be 4");
        assertEquals(2, P, "PARITY_SHARDS must be 2");
        assertEquals(6, N, "TOTAL_SHARDS must be 6");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Returns a full present[] mask (all true). */
    private static boolean[] allPresent(int n) {
        boolean[] p = new boolean[n];
        Arrays.fill(p, true);
        return p;
    }

    /**
     * Runs encode then decode with a custom present[] mask and returns the result.
     *
     * @param data     original file bytes
     * @param present  which shard slots are "present/healthy"
     * @return reconstructed bytes (trimmed to original length)
     */
    private static byte[] roundTrip(byte[] data, boolean[] present) {
        byte[][] shards = ReedSolomonHelper.encode(data, K, P);
        // Zero-out "missing" shards to simulate erasure
        for (int i = 0; i < N; i++) {
            if (!present[i]) shards[i] = null;
        }
        return ReedSolomonHelper.decode(shards, present, K, P, data.length);
    }

    // -----------------------------------------------------------------------
    // Encode → Decode round-trip (no erasures)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[PHASE 3 CORE] Encode → decode round-trip, no erasures, short string")
    void roundTrip_noErasures_shortString() {
        byte[] original = utf8("Hello, Reed-Solomon!");
        byte[] recovered = roundTrip(original, allPresent(N));
        assertArrayEquals(original, recovered, "Round-trip with no erasures must be lossless");
    }

    @Test
    @DisplayName("Round-trip with 4 KB of random-ish data")
    void roundTrip_noErasures_4KBdata() {
        // Deterministic pseudo-random data (avoids test flakiness)
        byte[] original = new byte[4096];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 37 + 13);
        }
        byte[] recovered = roundTrip(original, allPresent(N));
        assertArrayEquals(original, recovered, "4 KB round-trip must be exact");
    }

    @Test
    @DisplayName("Round-trip with data size not divisible by dataShards (triggers zero-padding)")
    void roundTrip_unevenDataSize() {
        // 4*shardSize-1 → needs 1 byte of zero-padding
        byte[] original = utf8("An odd-length string that is not a multiple of 4 shards!!");
        byte[] recovered = roundTrip(original, allPresent(N));
        assertArrayEquals(original, recovered,
                "Zero-padding must be stripped on decode (originalLength trim)");
    }

    @Test
    @DisplayName("Round-trip with a single-byte file")
    void roundTrip_singleByte() {
        byte[] original = new byte[]{(byte) 0xCA};
        byte[] recovered = roundTrip(original, allPresent(N));
        assertArrayEquals(original, recovered, "Single-byte file must survive encoding");
    }

    // -----------------------------------------------------------------------
    // Erasure tolerance — the core Phase 3 property
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[PHASE 3 CORE] Tolerate loss of shard 0 (a data shard)")
    void decode_shard0Missing() {
        byte[] original = utf8("Erasure coding is resilient to node failure.");
        boolean[] present = allPresent(N);
        present[0] = false;   // shard 0 (data shard) is lost
        assertArrayEquals(original, roundTrip(original, present),
                "Must reconstruct when shard 0 (data) is missing");
    }

    @Test
    @DisplayName("[PHASE 3 CORE] Tolerate loss of shard 4 (a parity shard)")
    void decode_parityShardMissing() {
        byte[] original = utf8("Parity shard loss is trivially handled.");
        boolean[] present = allPresent(N);
        present[4] = false;   // shard 4 (first parity shard)
        assertArrayEquals(original, roundTrip(original, present),
                "Must reconstruct when parity shard is missing");
    }

    @Test
    @DisplayName("[PHASE 3 CORE] Tolerate exactly n-k=2 simultaneous erasures (maximum tolerance)")
    void decode_maxErasures_twoParity() {
        byte[] original = utf8("Two simultaneous failures are within the tolerance envelope.");
        boolean[] present = allPresent(N);
        present[5] = false;   // parity shard 5
        present[3] = false;   // data shard 3
        assertArrayEquals(original, roundTrip(original, present),
                "Must reconstruct with exactly 2 erasures (the maximum allowed)");
    }

    @Test
    @DisplayName("Tolerate loss of the two parity shards simultaneously")
    void decode_bothParityShardsLost() {
        byte[] original = utf8("Both parity shards lost — still recoverable from 4 data shards.");
        boolean[] present = allPresent(N);
        present[4] = false;
        present[5] = false;
        assertArrayEquals(original, roundTrip(original, present),
                "Must recover when both parity shards are gone");
    }

    @Test
    @DisplayName("Tolerate loss of two data shards (non-consecutive)")
    void decode_twoDataShardsLost_nonConsecutive() {
        byte[] original = utf8("Non-consecutive data shard loss is still within tolerance.");
        boolean[] present = allPresent(N);
        present[0] = false;
        present[2] = false;
        assertArrayEquals(original, roundTrip(original, present),
                "Must recover when shards 0 and 2 (data) are erased");
    }

    // -----------------------------------------------------------------------
    // Hash-mismatch = erasure (the AGENTS.md design mandate)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[PHASE 3 CORE] Hash-mismatch treated as erasure — corrupt shard reconstructed")
    void decode_hashMismatch_treatedAsErasure() {
        byte[] original = utf8("A Byzantine node returns corrupted bytes.");

        byte[][] shards = ReedSolomonHelper.encode(original, K, P);

        // Simulate a Byzantine node flipping a byte in shard 1
        shards[1][0] ^= (byte) 0xFF;

        // Client detects hash mismatch → marks shard 1 as erasure
        boolean[] present = allPresent(N);
        present[1] = false;     // mismatch = erasure flag
        shards[1] = null;       // nulled out, as ErasureClient does

        byte[] recovered = ReedSolomonHelper.decode(shards, present, K, P, original.length);
        assertArrayEquals(original, recovered,
                "A hash-mismatched shard treated as erasure must still allow full reconstruction");
    }

    // -----------------------------------------------------------------------
    // Quorum failure — more than n-k erasures
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("InsufficientShardsException thrown when 3 shards missing (exceeds tolerance)")
    void decode_tooFewShards_throws() {
        byte[] original = utf8("This file will lose quorum.");
        boolean[] present = allPresent(N);
        present[0] = false;
        present[1] = false;
        present[2] = false;   // 3 erasures — one beyond the n-k=2 limit

        byte[][] shards = ReedSolomonHelper.encode(original, K, P);
        for (int i = 0; i < N; i++) if (!present[i]) shards[i] = null;

        assertThrows(ReedSolomonHelper.InsufficientShardsException.class,
                () -> ReedSolomonHelper.decode(shards, present, K, P, original.length),
                "Must throw InsufficientShardsException when fewer than k shards are present");
    }

    @Test
    @DisplayName("InsufficientShardsException thrown when ALL shards are missing")
    void decode_allShardsGone_throws() {
        byte[] original = utf8("Total loss.");
        boolean[] present = new boolean[N]; // all false
        byte[][] shards = new byte[N][];    // all null

        assertThrows(ReedSolomonHelper.InsufficientShardsException.class,
                () -> ReedSolomonHelper.decode(shards, present, K, P, original.length),
                "All shards gone must throw InsufficientShardsException");
    }

    // -----------------------------------------------------------------------
    // shardSize helper
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shardSize rounds up correctly for non-divisible lengths")
    void shardSize_roundsUp() {
        // fileLength=9, dataShards=4 → ceil(9/4) = 3
        assertEquals(3, ReedSolomonHelper.shardSize(9, 4));
        // fileLength=8, dataShards=4 → 8/4 = 2 (exact)
        assertEquals(2, ReedSolomonHelper.shardSize(8, 4));
        // fileLength=1, dataShards=4 → ceil(1/4) = 1
        assertEquals(1, ReedSolomonHelper.shardSize(1, 4));
    }

    // -----------------------------------------------------------------------
    // Parameter validation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("encode throws on null data")
    void encode_nullData_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ReedSolomonHelper.encode(null, K, P));
    }

    @Test
    @DisplayName("encode throws on empty data")
    void encode_emptyData_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ReedSolomonHelper.encode(new byte[0], K, P));
    }

    @Test
    @DisplayName("decode throws when shards array has wrong length")
    void decode_wrongShardsLength_throws() {
        byte[][] shards = new byte[3][];
        boolean[] present = {true, true, true};
        assertThrows(IllegalArgumentException.class,
                () -> ReedSolomonHelper.decode(shards, present, K, P, 10));
    }
}

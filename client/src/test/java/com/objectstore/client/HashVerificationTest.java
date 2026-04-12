package com.objectstore.client;

import com.objectstore.common.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 unit tests for SHA-256 hash verification and {@link ShardManifest}.
 *
 * <p>These tests run entirely in-process \u2014 no TCP connections to a storage node
 * are needed. The design mandate from {@code CLAUDE.md} is tested here:
 *
 * <blockquote>
 * A hash mismatch \u2192 shard is immediately discarded and treated as an
 * <em>erasure</em>. This is the critical distinction: <strong>mismatch = erasure
 * flag</strong>, not a separate error type.
 * </blockquote>
 *
 * <p>The key test \u2014 mandated by Phase 2 \u2014 is {@link #flipOneByte_isDetectedByManifest()}.
 */
class HashVerificationTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // ShardManifest \u2014 registration and basic lookup
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("registerShard returns the correct SHA-256 hex for the stored data")
    void registerShard_returnsCorrectHex() {
        byte[] data = bytes("hello shard");
        String expected = HashUtil.sha256Hex(data);

        ShardManifest manifest = new ShardManifest("file-01");
        String returned = manifest.registerShard(0, data);

        assertEquals(expected, returned,
                "registerShard must return the SHA-256 of the supplied bytes");
    }

    @Test
    @DisplayName("expectedHash retrieves the hash registered for a shard index")
    void expectedHash_returnsRegisteredHash() {
        byte[] data = bytes("shard content");
        ShardManifest manifest = new ShardManifest("file-02");
        String registered = manifest.registerShard(3, data);

        assertEquals(registered, manifest.expectedHash(3));
    }

    @Test
    @DisplayName("expectedHash throws for an unregistered shard index")
    void expectedHash_unregisteredIndex_throws() {
        ShardManifest manifest = new ShardManifest("file-03");
        manifest.registerShard(0, bytes("data"));

        assertThrows(IllegalArgumentException.class,
                () -> manifest.expectedHash(99),
                "Asking for an unregistered shard index must throw");
    }

    // -----------------------------------------------------------------------
    // ShardManifest.verify \u2014 happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("verify returns true when received bytes match the registered hash")
    void verify_identicalBytes_returnsTrue() {
        byte[] data = bytes("correct shard data");
        ShardManifest manifest = new ShardManifest("file-04");
        manifest.registerShard(0, data);

        assertTrue(manifest.verify(0, data),
                "verify must return true when the bytes are byte-for-byte identical to the upload");
    }

    @Test
    @DisplayName("verify returns true for a copy of the original bytes (not the same reference)")
    void verify_copyOfBytes_returnsTrue() {
        byte[] original = bytes("copy-safe data");
        byte[] copy     = Arrays.copyOf(original, original.length);

        ShardManifest manifest = new ShardManifest("file-05");
        manifest.registerShard(1, original);

        assertTrue(manifest.verify(1, copy),
                "verify must use value equality, not reference equality");
    }

    // -----------------------------------------------------------------------
    // ShardManifest.verify \u2014 corruption detection (the Phase 2 core requirement)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[PHASE 2 CORE] Flipping one byte in received data is detected by verify()")
    void flipOneByte_isDetectedByManifest() {
        byte[] original = bytes("This is a high-value shard that must not be tampered with.");
        ShardManifest manifest = new ShardManifest("file-phase2");
        manifest.registerShard(0, original);

        // Simulate a Byzantine storage node returning a single corrupted byte
        byte[] corrupted = Arrays.copyOf(original, original.length);
        corrupted[0] ^= (byte) 0xFF;   // flip all bits in the first byte

        assertFalse(manifest.verify(0, corrupted),
                "verify() MUST return false when even a single byte has been altered \u2014 "
                + "this is the Phase 2 core integrity guarantee");
    }

    @Test
    @DisplayName("Flipping the last byte is also detected")
    void flipLastByte_isDetectedByManifest() {
        byte[] original = bytes("last byte corruption test");
        ShardManifest manifest = new ShardManifest("file-last");
        manifest.registerShard(0, original);

        byte[] corrupted = Arrays.copyOf(original, original.length);
        corrupted[original.length - 1] ^= 0x01;  // flip only the LSB of the last byte

        assertFalse(manifest.verify(0, corrupted),
                "verify() must detect corruption in the last byte");
    }

    @Test
    @DisplayName("Truncating the data is detected")
    void truncatedData_isDetectedByManifest() {
        byte[] original = bytes("this data will be truncated");
        ShardManifest manifest = new ShardManifest("file-truncate");
        manifest.registerShard(0, original);

        byte[] truncated = Arrays.copyOf(original, original.length - 5);

        assertFalse(manifest.verify(0, truncated),
                "verify() must detect when the received data is shorter than expected");
    }

    @Test
    @DisplayName("Extra appended bytes are detected")
    void appendedBytes_areDetectedByManifest() {
        byte[] original = bytes("original");
        byte[] extended = Arrays.copyOf(original, original.length + 4);
        // tail bytes are 0x00 by default (Arrays.copyOf pads with zeroes)

        ShardManifest manifest = new ShardManifest("file-extended");
        manifest.registerShard(0, original);

        assertFalse(manifest.verify(0, extended),
                "verify() must detect when extra bytes have been appended");
    }

    @Test
    @DisplayName("Completely different bytes are detected")
    void differentBytes_areDetectedByManifest() {
        byte[] original   = bytes("original shard bytes");
        byte[] completely = bytes("COMPLETELY DIFFERENT DATA");

        ShardManifest manifest = new ShardManifest("file-diff");
        manifest.registerShard(0, original);

        assertFalse(manifest.verify(0, completely),
                "verify() must return false for completely different data");
    }

    // -----------------------------------------------------------------------
    // ShardManifest \u2014 multiple shards within one manifest
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Multiple shards can be registered under the same fileId")
    void multipleShards_allVerifyCorrectly() {
        String fileId = "multi-shard-file";
        ShardManifest manifest = new ShardManifest(fileId);

        byte[] shard0 = bytes("shard zero bytes");
        byte[] shard1 = bytes("shard one bytes");
        byte[] shard2 = bytes("shard two bytes");

        manifest.registerShard(0, shard0);
        manifest.registerShard(1, shard1);
        manifest.registerShard(2, shard2);

        assertAll("all three shard verifications must pass",
                () -> assertTrue(manifest.verify(0, shard0), "shard 0 must verify"),
                () -> assertTrue(manifest.verify(1, shard1), "shard 1 must verify"),
                () -> assertTrue(manifest.verify(2, shard2), "shard 2 must verify")
        );
    }

    @Test
    @DisplayName("A corrupt shard does not affect the verification of its neighbours")
    void corruptShard_doesNotAffectNeighbours() {
        ShardManifest manifest = new ShardManifest("multi-corrupt");

        byte[] shard0 = bytes("healthy shard zero");
        byte[] shard1 = bytes("this shard will be corrupted");
        byte[] shard2 = bytes("healthy shard two");

        manifest.registerShard(0, shard0);
        manifest.registerShard(1, shard1);
        manifest.registerShard(2, shard2);

        byte[] corruptedShard1 = Arrays.copyOf(shard1, shard1.length);
        corruptedShard1[5] ^= 0xFF;

        assertAll("only the corrupt shard must fail",
                () -> assertTrue (manifest.verify(0, shard0),        "shard 0 must still verify"),
                () -> assertFalse(manifest.verify(1, corruptedShard1), "shard 1 must not verify"),
                () -> assertTrue (manifest.verify(2, shard2),        "shard 2 must still verify")
        );
    }

    // -----------------------------------------------------------------------
    // ShardManifest \u2014 shardId wire format
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shardId returns the expected wire-format string")
    void shardId_format() {
        ShardManifest manifest = new ShardManifest("myfile");
        assertEquals("myfile/shard-0",  manifest.shardId(0));
        assertEquals("myfile/shard-7",  manifest.shardId(7));
        assertEquals("myfile/shard-42", manifest.shardId(42));
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Empty shard data can be registered and verified")
    void emptyShardData_canBeRegisteredAndVerified() {
        ShardManifest manifest = new ShardManifest("file-empty");
        manifest.registerShard(0, new byte[0]);

        assertTrue(manifest.verify(0, new byte[0]),
                "An empty shard must verify against its own hash");
        assertFalse(manifest.verify(0, new byte[]{0x01}),
                "A non-empty byte array must not verify against an empty shard hash");
    }
}

package com.objectstore.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FingerprintedCrossChecksumTest {

    private static final LinearErasureCodec CODEC = new LinearErasureCodec();

    @Test
    @DisplayName("All encoded fragments verify against the fingerprinted cross-checksum")
    void validFragments_verify() {
        LinearErasureCodec.EncodedBlock block = CODEC.encode(bytes("valid encoded block"));
        FingerprintedCrossChecksum fpcc =
                FingerprintedCrossChecksum.create(block.sourceFragments(), block.encodedFragments(), CODEC);

        for (int i = 0; i < CODEC.totalFragments(); i++) {
            assertTrue(fpcc.verifyFragment(i, block.encodedFragments()[i], CODEC));
        }
    }

    @Test
    @DisplayName("Bit-flipped fragment is rejected")
    void corruptedFragment_isRejected() {
        LinearErasureCodec.EncodedBlock block = CODEC.encode(bytes("corruption should be detected"));
        FingerprintedCrossChecksum fpcc =
                FingerprintedCrossChecksum.create(block.sourceFragments(), block.encodedFragments(), CODEC);

        byte[] corrupted = Arrays.copyOf(block.encodedFragments()[1], block.encodedFragments()[1].length);
        corrupted[0] ^= (byte) 0xFF;

        assertFalse(fpcc.verifyFragment(1, corrupted, CODEC));
    }

    @Test
    @DisplayName("Fragment from a different object is rejected")
    void mixedObjectFragment_isRejected() {
        // Use data where fragment 0 differs between the two objects.
        // With the default 4-shard codec each fragment is ceil(len/4) bytes.
        // "AAAA-BBBB-CCCC-DDDD" → fragment 0 = "AAAA-" (5 bytes)
        // "XXXX-YYYY-ZZZZ-WWWW" → fragment 0 = "XXXX-" (5 bytes) — clearly different SHA-256
        LinearErasureCodec.EncodedBlock blockA = CODEC.encode(bytes("AAAA-BBBB-CCCC-DDDD-end"));
        LinearErasureCodec.EncodedBlock blockB = CODEC.encode(bytes("XXXX-YYYY-ZZZZ-WWWW-end"));
        FingerprintedCrossChecksum fpccA =
                FingerprintedCrossChecksum.create(blockA.sourceFragments(), blockA.encodedFragments(), CODEC);

        assertFalse(fpccA.verifyFragment(0, blockB.encodedFragments()[0], CODEC));
    }

    @Test
    @DisplayName("Manifest persists and reloads fingerprinted cross-checksum metadata")
    void manifest_saveLoad_roundTrip() throws Exception {
        LinearErasureCodec.EncodedBlock block = CODEC.encode(bytes("manifest persistence"));
        FingerprintedCrossChecksum fpcc =
                FingerprintedCrossChecksum.create(block.sourceFragments(), block.encodedFragments(), CODEC);
        ShardManifest manifest = new ShardManifest("file-manifest");
        manifest.setCodingMetadata(CODEC.sourceFragments(), CODEC.totalFragments(), block.originalLength(), block.fragmentSize());
        manifest.setFingerprintCrossChecksum(fpcc);
        for (int i = 0; i < CODEC.totalFragments(); i++) {
            manifest.addNodeAddress("localhost:" + (7100 + i));
        }

        Path temp = Files.createTempFile("objectstore-manifest", ".txt");
        manifest.save(temp);
        ShardManifest loaded = ShardManifest.load(temp);

        assertEquals("file-manifest", loaded.getFileId());
        assertEquals(CODEC.sourceFragments(), loaded.getSourceFragments());
        assertEquals(CODEC.totalFragments(), loaded.getTotalFragments());
        assertTrue(loaded.verifyFragment(3, block.encodedFragments()[3], CODEC));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

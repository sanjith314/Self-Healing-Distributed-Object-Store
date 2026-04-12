package com.objectstore.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HashUtil}.
 *
 * <p>All tests run fully in-process with no network or filesystem I/O.
 */
class HashUtilTest {

    // -----------------------------------------------------------------------
    // sha256Hex
    // -----------------------------------------------------------------------

    @Test
    void sha256Hex_knownVector_returnsCorrectHex() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String hex = HashUtil.sha256Hex(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex,
                "SHA-256 of empty array must match the well-known RFC test vector");
    }

    @Test
    void sha256Hex_isDeterministic() {
        byte[] data    = "determinism check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hexRun1 = HashUtil.sha256Hex(data);
        String hexRun2 = HashUtil.sha256Hex(data);
        assertEquals(hexRun1, hexRun2, "SHA-256 must produce the same output on every call for the same input");
    }

    @Test
    void sha256Hex_returns64Characters() {
        String hex = HashUtil.sha256Hex("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(64, hex.length(), "SHA-256 hex output must always be 64 characters");
    }

    @Test
    void sha256Hex_isLowerCase() {
        String hex = HashUtil.sha256Hex("test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(hex.matches("[0-9a-f]+"), "SHA-256 hex output must be lowercase");
    }

    @Test
    void sha256_differentInputs_produceDifferentHashes() {
        String hex1 = HashUtil.sha256Hex("data1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hex2 = HashUtil.sha256Hex("data2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNotEquals(hex1, hex2, "Different inputs must produce different SHA-256 outputs");
    }

    @Test
    void sha256_sameInput_producesSameHash() {
        byte[] data = "idempotent".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hex1 = HashUtil.sha256Hex(data);
        String hex2 = HashUtil.sha256Hex(data);
        assertEquals(hex1, hex2, "Same input must always produce the same SHA-256 hash");
    }

    // -----------------------------------------------------------------------
    // bytesToHex
    // -----------------------------------------------------------------------

    @Test
    void bytesToHex_zeroBytes_returnsCorrectHex() {
        assertEquals("00ff7f80", HashUtil.bytesToHex(new byte[]{0x00, (byte)0xFF, 0x7F, (byte)0x80}));
    }

    @Test
    void bytesToHex_emptyArray_returnsEmptyString() {
        assertEquals("", HashUtil.bytesToHex(new byte[0]));
    }
}

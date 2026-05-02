package com.objectstore.client;

import com.objectstore.common.GaloisField256;
import com.objectstore.common.HomomorphicFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class HomomorphicFingerprintTest {

    @Test
    @DisplayName("Fingerprint addition matches byte-vector addition")
    void fingerprint_additionIsHomomorphic() {
        HomomorphicFingerprint fp = new HomomorphicFingerprint(seed());
        byte[] a = data(64, 3);
        byte[] b = data(64, 19);
        byte[] sum = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            sum[i] = (byte) GaloisField256.add(a[i] & 0xFF, b[i] & 0xFF);
        }

        assertArrayEquals(fp.fingerprint(sum), fp.add(fp.fingerprint(a), fp.fingerprint(b)));
    }

    @Test
    @DisplayName("Fingerprint scalar multiplication matches byte-vector scalar multiplication")
    void fingerprint_scalarMultiplicationIsHomomorphic() {
        HomomorphicFingerprint fp = new HomomorphicFingerprint(seed());
        byte[] data = data(64, 41);
        int scalar = 0x53;
        byte[] scaledData = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            scaledData[i] = (byte) GaloisField256.multiply(scalar, data[i] & 0xFF);
        }

        assertArrayEquals(fp.fingerprint(scaledData), fp.scalarMultiply(fp.fingerprint(data), scalar));
    }

    @Test
    @DisplayName("Encoded fragment fingerprint equals encoded source fingerprints")
    void encodedFragmentFingerprint_matchesEncodedSourceFingerprints() {
        LinearErasureCodec codec = new LinearErasureCodec();
        LinearErasureCodec.EncodedBlock block = codec.encode(data(257, 11));
        String[] cc = Arrays.stream(block.encodedFragments())
                .map(com.objectstore.common.HashUtil::sha256Hex)
                .toArray(String[]::new);

        HomomorphicFingerprint fp = new HomomorphicFingerprint(HomomorphicFingerprint.deriveSeed(cc));
        byte[][] sourceFp = new byte[codec.sourceFragments()][];
        for (int i = 0; i < sourceFp.length; i++) {
            sourceFp[i] = fp.fingerprint(block.sourceFragments()[i]);
        }

        for (int i = 0; i < codec.totalFragments(); i++) {
            assertArrayEquals(
                    fp.fingerprint(block.encodedFragments()[i]),
                    fp.encodeFingerprint(codec.codingRow(i), sourceFp),
                    "fragment " + i + " must satisfy the homomorphic relation");
        }
    }

    private static byte[] seed() {
        return new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    }

    private static byte[] data(int size, int salt) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 17 + salt);
        }
        return data;
    }
}

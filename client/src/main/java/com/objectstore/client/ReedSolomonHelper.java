package com.objectstore.client;

import com.backblaze.erasure.ReedSolomon;

import java.util.Arrays;

/**
 * Thin wrapper around the Backblaze Reed-Solomon library for the distributed
 * object store.
 *
 * <h2>Encoding (upload path)</h2>
 * <p>Given raw file bytes and the coding parameters {@code (dataShards, parityShards)},
 * {@link #encode} pads the data to the smallest multiple of {@code dataShards} bytes,
 * splits it into {@code dataShards} equal chunks, and then encodes
 * {@code parityShards} parity chunks.  The caller stores the <em>original</em>
 * (pre-padding) length in the manifest so that padding can be stripped on decode.
 *
 * <h2>Decoding (download path)</h2>
 * <p>Given the (possibly partial) shard array and a {@code boolean[] present} mask
 * (where {@code present[i] = false} means shard {@code i} is unavailable or
 * corrupted), {@link #decode} invokes the RS reconstruction algorithm and returns
 * the reassembled original bytes, stripped of any zero-padding.
 *
 * <h2>Erasure convention</h2>
 * <p>In the download path, a hash mismatch detected by {@link ShardManifest#verify}
 * <em>must</em> map to {@code present[i] = false} before calling {@link #decode}.
 * This is the "mismatch = erasure flag" design mandated by {@code AGENTS.md}.
 *
 * <h2>Default parameters</h2>
 * <pre>
 *   DATA_SHARDS   = 4   (k)
 *   PARITY_SHARDS = 2   (n - k)
 *   TOTAL_SHARDS  = 6   (n)
 * </pre>
 * Tolerates any 2 simultaneous failures at 1.5× storage overhead.
 */
public final class ReedSolomonHelper {

    /** Default number of data shards (k). */
    public static final int DATA_SHARDS   = 4;

    /** Default number of parity shards (n - k). */
    public static final int PARITY_SHARDS = 2;

    /** Default total shard count (n). */
    public static final int TOTAL_SHARDS  = DATA_SHARDS + PARITY_SHARDS;

    // -----------------------------------------------------------------------
    // Encode
    // -----------------------------------------------------------------------

    /**
     * Encodes raw file bytes into {@code dataShards + parityShards} equal-size chunks.
     *
     * <p>The returned array has length {@code dataShards + parityShards}. All shards
     * have the same size.  The caller must record {@code data.length} in the
     * manifest so that zero-padding added by this method can be removed during decode.
     *
     * @param data         original file bytes (must not be {@code null})
     * @param dataShards   number of data shards (k)
     * @param parityShards number of parity shards (n - k)
     * @return 2-D array of {@code dataShards + parityShards} equal-length shard arrays
     * @throws IllegalArgumentException if {@code data} is empty or parameters are invalid
     */
    public static byte[][] encode(byte[] data, int dataShards, int parityShards) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }
        validateParams(dataShards, parityShards);

        int totalShards = dataShards + parityShards;

        // Pad to the smallest multiple of dataShards
        int shardSize = shardSize(data.length, dataShards);

        // Build the 2-D shard array: [shardIndex][byteIndex]
        byte[][] shards = new byte[totalShards][shardSize];

        // Slice file bytes into the first dataShards rows (last shard zero-padded if needed)
        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(flatBuffer(data, i, shardSize, dataShards), 0, shards[i], 0, shardSize);
        }

        // Encode parity rows using Backblaze RS
        ReedSolomon rs = ReedSolomon.create(dataShards, parityShards);
        rs.encodeParity(shards, 0, shardSize);

        return shards;
    }

    // -----------------------------------------------------------------------
    // Decode
    // -----------------------------------------------------------------------

    /**
     * Reconstructs the original file bytes from a partial set of shards.
     *
     * @param shards         2-D shard array; {@code shards[i]} may be {@code null}
     *                       or zeroed-out if {@code present[i]} is {@code false}
     * @param present        {@code present[i] = true} iff shard {@code i} is healthy
     * @param dataShards     number of data shards (k)
     * @param parityShards   number of parity shards (n - k)
     * @param originalLength original file length in bytes (to strip padding)
     * @return the reconstructed original file bytes
     * @throws IllegalArgumentException  if not enough shards are present (fewer than k)
     * @throws IllegalStateException     if the RS reconstruction step fails unexpectedly
     */
    public static byte[] decode(
            byte[][] shards, boolean[] present,
            int dataShards, int parityShards, long originalLength) {

        validateParams(dataShards, parityShards);
        int totalShards = dataShards + parityShards;

        if (shards.length != totalShards || present.length != totalShards) {
            throw new IllegalArgumentException(
                    "shards and present arrays must have length == totalShards (" + totalShards + ")");
        }

        // Count available shards
        int available = 0;
        for (boolean p : present) if (p) available++;
        if (available < dataShards) {
            throw new InsufficientShardsException(
                    "Need at least " + dataShards + " shards but only " + available + " are present/healthy");
        }

        // Determine shard size from the first present shard
        int shardSize = -1;
        for (int i = 0; i < totalShards; i++) {
            if (present[i] && shards[i] != null) {
                shardSize = shards[i].length;
                break;
            }
        }
        if (shardSize < 0) {
            throw new IllegalStateException("No healthy shard found despite present[] check");
        }

        // Ensure absent shard slots have an allocated (zeroed) buffer — RS library requires it
        for (int i = 0; i < totalShards; i++) {
            if (!present[i] || shards[i] == null) {
                shards[i] = new byte[shardSize];
                present[i] = false;  // normalise
            }
        }

        // Reed-Solomon reconstruction
        ReedSolomon rs = ReedSolomon.create(dataShards, parityShards);
        rs.decodeMissing(shards, present, 0, shardSize);

        // Reassemble data shards and strip zero-padding
        byte[] reconstructed = new byte[dataShards * shardSize];
        for (int i = 0; i < dataShards; i++) {
            System.arraycopy(shards[i], 0, reconstructed, i * shardSize, shardSize);
        }

        // Trim to original length
        return Arrays.copyOf(reconstructed, (int) originalLength);
    }

    // -----------------------------------------------------------------------
    // Public helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the per-shard size (in bytes) for a file of the given length encoded
     * with {@code dataShards} data shards.
     *
     * <p>The shard size is the ceiling of {@code fileLength / dataShards}, rounded
     * up to ensure even partitioning.
     *
     * @param fileLength  original file length in bytes
     * @param dataShards  number of data shards (k)
     * @return shard size in bytes (≥ 1)
     */
    public static int shardSize(long fileLength, int dataShards) {
        return (int) ((fileLength + dataShards - 1) / dataShards);
    }

    // -----------------------------------------------------------------------
    // Encode helper — slice data into per-shard rows
    // -----------------------------------------------------------------------

    /**
     * Returns the bytes that belong to shard {@code shardIndex} when {@code data}
     * is split into equal {@code shardSize}-byte segments.
     *
     * <p>If the last shard is shorter than {@code shardSize}, the returned array
     * is zero-padded to {@code shardSize}.
     */
    private static byte[] flatBuffer(byte[] data, int shardIndex, int shardSize, int dataShards) {
        byte[] buf = new byte[shardSize];
        int start = shardIndex * shardSize;
        int end   = Math.min(start + shardSize, data.length);
        if (start < data.length) {
            System.arraycopy(data, start, buf, 0, end - start);
        }
        return buf;
    }


    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    private static void validateParams(int dataShards, int parityShards) {
        if (dataShards < 1)  throw new IllegalArgumentException("dataShards must be >= 1");
        if (parityShards < 1) throw new IllegalArgumentException("parityShards must be >= 1");
    }

    // -----------------------------------------------------------------------
    // Exception
    // -----------------------------------------------------------------------

    /**
     * Thrown when there are too few healthy shards to reconstruct the original file.
     * The caller (Auditor or Client) must surface this as an unrecoverable error.
     */
    public static class InsufficientShardsException extends RuntimeException {
        public InsufficientShardsException(String message) {
            super(message);
        }
    }

    // Prevent instantiation
    private ReedSolomonHelper() {}
}

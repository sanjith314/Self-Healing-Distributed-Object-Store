package com.objectstore.client;

/**
 * A single Shacham-Waters verification tag for one block fragment.
 *
 * <p>The tag {@code sigma} is computed as:
 * <pre>
 *   σᵢ = f_k(i) + Σⱼ mᵢ,ⱼ · uⱼ   (mod p)
 * </pre>
 * where {@code f_k(i)} is HMAC-SHA256 reduced mod p, and {@code mᵢ,ⱼ} are the sector
 * values of block {@code i}.
 *
 * <p>This record is stored in the {@link ShardManifest} — never on storage nodes.
 * Nodes hold the raw {@code sigma} bytes alongside each {@code .data} file but
 * cannot re-derive or forge it without the secret key.
 *
 * @param blockIndex 0-based index of the block fragment this tag authenticates
 * @param sigma      the tag value in Z_p (must be in range [0, p))
 */
public record PorTag(int blockIndex, long sigma) {}

#!/usr/bin/env python3
"""
corrupt-shard.py — Demo helper: flip one byte in a shard file stored on disk.

This simulates a Byzantine storage node that has corrupted a shard on its
local filesystem, so the next Auditor cycle detects the hash mismatch and
triggers self-repair.

Usage:
    python3 scripts/corrupt-shard.py <shardFilePath> [byteOffset]

Arguments:
    shardFilePath   Path to the shard file to corrupt (e.g.
                    cluster-data/node-7101/myfile/shard-1)
    byteOffset      (optional) Zero-based byte offset to flip.
                    Defaults to 0 (the first byte).

Examples:
    # Corrupt the first byte of shard-1 on node 7101:
    python3 scripts/corrupt-shard.py cluster-data/node-7101/myfile/shard-1

    # Corrupt byte 42:
    python3 scripts/corrupt-shard.py cluster-data/node-7101/myfile/shard-1 42

After running this script, the next Auditor audit cycle (or a manual
rs-download) will detect the hash mismatch and treat the shard as an erasure.
"""

import sys
import os


def corrupt_shard(path: str, offset: int = 0) -> None:
    if not os.path.isfile(path):
        print(f"ERROR: File not found: {path}", file=sys.stderr)
        sys.exit(1)

    file_size = os.path.getsize(path)
    if file_size == 0:
        print(f"ERROR: File is empty: {path}", file=sys.stderr)
        sys.exit(1)

    if offset >= file_size:
        print(
            f"ERROR: Byte offset {offset} is out of range "
            f"(file size = {file_size} bytes).",
            file=sys.stderr,
        )
        sys.exit(1)

    with open(path, "r+b") as f:
        f.seek(offset)
        original_byte = f.read(1)[0]
        corrupted_byte = original_byte ^ 0xFF  # flip all 8 bits

        f.seek(offset)
        f.write(bytes([corrupted_byte]))

    print(f"[corrupt-shard] File    : {path}")
    print(f"[corrupt-shard] Offset  : {offset}")
    print(f"[corrupt-shard] Before  : 0x{original_byte:02X} ({original_byte})")
    print(f"[corrupt-shard] After   : 0x{corrupted_byte:02X} ({corrupted_byte})")
    print(f"[corrupt-shard] ✅  Shard corrupted — the Auditor should detect this.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    shard_path = sys.argv[1]
    byte_offset = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    corrupt_shard(shard_path, byte_offset)

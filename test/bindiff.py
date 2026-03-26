#!/usr/bin/env python3
"""Compare two binary images and report byte-level differences."""

import argparse
import sys
from pathlib import Path


def parse_args():
    p = argparse.ArgumentParser(
        description=(
            "Compare a generated .bin image against a golden .bin and report "
            "byte-level differences."
        )
    )
    p.add_argument("golden", type=Path, help="path to the golden .bin file")
    p.add_argument("candidate", type=Path, help="path to the generated .bin file")
    p.add_argument(
        "--max-diffs",
        type=int,
        default=32,
        help="maximum number of differing bytes to print (default: 32)",
    )
    return p.parse_args()


def fmt_hex(value: int, nbytes: int) -> str:
    return f"0x{value:0{nbytes * 2}x}"


def offset_width(golden_len: int, candidate_len: int) -> int:
    end_address = max(golden_len, candidate_len, 1) - 1
    hex_digits = max(1, len(f"{end_address:x}"))
    return max(4, (hex_digits + 1) // 2)


def read_bin(path: Path, label: str) -> bytes:
    if not path.exists():
        raise FileNotFoundError(f"{label} file not found: {path}")
    return path.read_bytes()


def main():
    args = parse_args()

    if args.max_diffs < 0:
        print("error: --max-diffs must be non-negative", file=sys.stderr)
        return 2

    try:
        golden = read_bin(args.golden, "golden")
        candidate = read_bin(args.candidate, "candidate")
    except FileNotFoundError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    nbytes = offset_width(len(golden), len(candidate))
    diff_count = 0
    printed = 0
    common_len = min(len(golden), len(candidate))
    total_bytes = max(len(golden), len(candidate))

    print(f"# golden={args.golden} bytes={len(golden)}")
    print(f"# candidate={args.candidate} bytes={len(candidate)}")

    for offset in range(common_len):
        g = golden[offset]
        c = candidate[offset]
        if g == c:
            continue
        diff_count += 1
        if printed < args.max_diffs:
            print(
                f"{fmt_hex(offset, nbytes)}: "
                f"golden={fmt_hex(g, 1)} candidate={fmt_hex(c, 1)}"
            )
            printed += 1

    size_mismatch_line = None
    if len(golden) != len(candidate):
        diff_count += abs(len(golden) - len(candidate))
        longer_name = "golden" if len(golden) > len(candidate) else "candidate"
        longer = golden if len(golden) > len(candidate) else candidate
        extra_start = common_len
        extra_count = len(longer) - common_len
        size_mismatch_line = (
            f"# size mismatch: golden={len(golden)} bytes candidate={len(candidate)} bytes "
            f"extra_in={longer_name} starting_at={fmt_hex(extra_start, nbytes)} "
            f"count={extra_count}"
        )
        while printed < args.max_diffs and extra_start < len(longer):
            byte = longer[extra_start]
            if longer_name == "golden":
                print(
                    f"{fmt_hex(extra_start, nbytes)}: "
                    f"golden={fmt_hex(byte, 1)} candidate=<missing>"
                )
            else:
                print(
                    f"{fmt_hex(extra_start, nbytes)}: "
                    f"golden=<missing> candidate={fmt_hex(byte, 1)}"
                )
            printed += 1
            extra_start += 1

    if diff_count == 0:
        print("# SUCCESS: files match")
        return 0

    hidden = diff_count - printed
    if hidden > 0:
        print(f"# ... {hidden} more differences not shown")
    if size_mismatch_line is not None:
        print(size_mismatch_line)
    diff_pct = 0.0 if total_bytes == 0 else 100.0 * diff_count / total_bytes
    print(f"# FAIL: {diff_count}/{total_bytes} bytes differ ({diff_pct:.2f}%)")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

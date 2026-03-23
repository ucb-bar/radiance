#!/usr/bin/env python3
"""Run manifest-driven trace dump + bindiff for a single sqlite trace."""

import argparse
import sys
from pathlib import Path

from bindiff_manifest import run_bindiff_from_sqlite


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Look up a sqlite trace in bindiff_manifest.json, dump the RTL .bin, "
            "and compare it against the configured golden .bin."
        )
    )
    parser.add_argument("sqlite", type=Path, help="path to sqlite trace file")
    parser.add_argument(
        "--log-dir",
        type=Path,
        help="directory for generated .bindiff.bin/.bindiff.log (default: sqlite parent)",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    sqlite_path = args.sqlite.resolve()
    script_dir = Path(__file__).resolve().parent
    if not sqlite_path.exists():
        print(f"error: sqlite trace not found: {sqlite_path}", file=sys.stderr)
        return 2

    bindiff_status, bindiff_failure_reason, bindiff_log_path = run_bindiff_from_sqlite(
        sqlite_path=sqlite_path,
        log_dir=args.log_dir,
        script_dir=script_dir,
    )

    if bindiff_status is None:
        print(f"# no bindiff manifest entry for {sqlite_path.stem}")
        return 0

    if bindiff_log_path:
        bindiff_log = Path(bindiff_log_path)
        if bindiff_log.exists():
            sys.stdout.write(bindiff_log.read_text(encoding="utf-8"))

    if bindiff_status == "pass":
        return 0
    if bindiff_status == "fail" and not bindiff_log_path:
        print(f"error: {bindiff_failure_reason}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

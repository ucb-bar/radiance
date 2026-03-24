#!/usr/bin/env python3
"""Run manifest-driven bindiff checks for binary test results."""

import argparse
import json
import sys
from pathlib import Path

from bindiff_manifest import load_bindiff_manifest, run_bindiff_from_spec

myname = Path(sys.argv[0]).name


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Run manifest-driven bindiff checks for sqlite traces referenced by "
            "run_binary_tests.py results and update the JSON in place."
        )
    )
    parser.add_argument(
        "json_path",
        type=Path,
        help="path to JSON results emitted by run_binary_tests.py",
    )
    return parser.parse_args()


def read_results(json_path: Path) -> dict[str, object]:
    with json_path.open("r", encoding="utf-8") as f:
        return json.load(f)


def write_results(json_path: Path, run_result: dict[str, object]) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    with json_path.open("w", encoding="utf-8") as f:
        json.dump(run_result, f, indent=2)
        f.write("\n")


def recompute_summary(run_result: dict[str, object]) -> None:
    results = run_result.get("results", [])
    run_result["total"] = len(results)
    run_result["passed"] = sum(1 for result in results if result.get("status") == "pass")
    run_result["failed"] = sum(1 for result in results if result.get("status") == "fail")
    run_result["timed_out"] = sum(1 for result in results if result.get("status") == "timeout")


def main():
    args = parse_args()
    json_path = args.json_path.resolve()
    script_dir = Path(__file__).resolve().parent
    chipyard_dir = script_dir.parents[2].resolve()
    bindiff_manifest = load_bindiff_manifest(script_dir)
    run_result = read_results(json_path)
    log_dir = Path(run_result["log_dir"]).resolve()

    print(f"[{myname}] using results from {json_path}")
    print(f"[{myname}] scanning {len(run_result.get('results', []))} test results")

    for result in run_result.get("results", []):
        name = result["name"]
        result["bindiff_status"] = None
        result["bindiff_failure_reason"] = ""
        result["bindiff_log_path"] = None

        if result.get("status") != "pass":
            continue

        bindiff_spec = bindiff_manifest.get(name)
        if bindiff_spec is None:
            continue

        sqlite_path_str = result.get("sqlite_path")
        if sqlite_path_str:
            sqlite_path = Path(sqlite_path_str).resolve()
        else:
            sqlite_path = chipyard_dir / "sims/vcs" / f"{name}.sqlite"
        print(f"[{myname}] bindiff {name}")
        bindiff_status, bindiff_failure_reason, bindiff_log_path = run_bindiff_from_spec(
            sqlite_path=sqlite_path,
            elf_name=name,
            log_dir=log_dir,
            chipyard_dir=chipyard_dir,
            script_dir=script_dir,
            bindiff_spec=bindiff_spec,
        )

        result["bindiff_status"] = bindiff_status
        result["bindiff_failure_reason"] = bindiff_failure_reason
        result["bindiff_log_path"] = bindiff_log_path

        if bindiff_status == "fail":
            result["status"] = "fail"
            result["failure_reason"] = bindiff_failure_reason

    recompute_summary(run_result)
    write_results(json_path, run_result)
    print(f"[{myname}] wrote updated results to {json_path}")
    return 0 if run_result["failed"] == 0 and run_result["timed_out"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

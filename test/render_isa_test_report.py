#!/usr/bin/env python3
"""Render ISA test JSON results as markdown."""

import argparse
import json
import os
import sys
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Render JSON emitted by run_isa_tests.py into markdown.\n"
            "By default writes markdown to stdout."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("json_path", help="path to JSON results from run_isa_tests.py")
    parser.add_argument(
        "--output",
        help="write markdown to this file instead of stdout",
    )
    parser.add_argument(
        "--github-step-summary",
        action="store_true",
        help="append markdown to the file named by $GITHUB_STEP_SUMMARY",
    )
    return parser.parse_args()


def read_results(json_path):
    with Path(json_path).open("r", encoding="utf-8") as f:
        return json.load(f)


def escape_cell(text):
    return str(text).replace("\n", "<br>").replace("|", "\\|")


def format_duration(seconds):
    return f"{seconds:.1f}s"


def render_markdown(run_result):
    config = run_result["config"]
    total = run_result["total"]
    passed = run_result["passed"]
    failed = run_result["failed"]
    timed_out = run_result["timed_out"]
    sim_binary = run_result["sim_binary"]
    results = run_result["results"]

    lines = []
    lines.append(f"## ISA Test Report: `{config}`")
    lines.append("")
    lines.append(
        f"Summary: {passed} passed, {failed} failed, {timed_out} timed out, {total} total"
    )
    lines.append("")
    lines.append(f"Simulator: `{sim_binary}`")
    lines.append("")
    lines.append("| Test | Status | Exit | Duration |")
    lines.append("| --- | --- | ---: | ---: |")
    for result in results:
        lines.append(
            "| {name} | {status} | {exit_code} | {duration} |".format(
                name=escape_cell(result["name"]),
                status=escape_cell(result["status"]),
                exit_code=result["exit_code"],
                duration=format_duration(result["duration_sec"]),
            )
        )

    failures = [r for r in results if r["status"] != "pass"]
    if failures:
        lines.append("")
        lines.append("### Failures")
        lines.append("")
        for result in failures:
            lines.append(
                "- `{name}`: `{status}`".format(
                    name=result["name"],
                    status=result["status"],
                )
            )
            if result["failure_reason"]:
                lines.append(f"  reason: {escape_cell(result['failure_reason'])}")

    return "\n".join(lines) + "\n"


def write_output(markdown, output_path):
    with Path(output_path).open("w", encoding="utf-8") as f:
        f.write(markdown)


def append_github_step_summary(markdown):
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        print(
            "error: --github-step-summary requires $GITHUB_STEP_SUMMARY to be set",
            file=sys.stderr,
        )
        sys.exit(1)
    with Path(summary_path).open("a", encoding="utf-8") as f:
        f.write(markdown)


def main():
    args = parse_args()
    run_result = read_results(args.json_path)
    markdown = render_markdown(run_result)

    if args.output:
        write_output(markdown, args.output)
    else:
        sys.stdout.write(markdown)

    if args.github_step_summary:
        append_github_step_summary(markdown)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

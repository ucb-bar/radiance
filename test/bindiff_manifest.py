#!/usr/bin/env python3
"""Shared manifest-driven trace dump + bindiff helpers."""

import json
import subprocess
import sys
from pathlib import Path


def load_bindiff_manifest(script_dir: Path) -> dict[str, dict[str, object]]:
    manifest_path = script_dir / "bindiff_manifest.json"
    if not manifest_path.exists():
        return {}
    with manifest_path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"bindiff manifest must contain a JSON object: {manifest_path}")
    return data


def run_bindiff_from_spec(
    *,
    sqlite_path: Path,
    elf_name: str,
    log_dir: Path,
    chipyard_dir: Path,
    script_dir: Path,
    bindiff_spec: dict[str, object] | None,
) -> tuple[str | None, str, str | None]:
    if bindiff_spec is None:
        return (None, "", None)

    golden_rel = bindiff_spec.get("golden_bin")
    trace_table = bindiff_spec.get("trace_table", "dmem")
    trace_kind = bindiff_spec.get("trace_kind", "all")
    trace_address = bindiff_spec.get("trace_address")
    cluster_id = bindiff_spec.get("cluster_id")
    core_id = bindiff_spec.get("core_id")
    warp = bindiff_spec.get("warp")

    if not isinstance(golden_rel, str) or not golden_rel:
        return ("fail", "bindiff manifest missing golden_bin", None)
    if not isinstance(trace_table, str) or not trace_table:
        return ("fail", "bindiff manifest has invalid trace_table", None)
    if not isinstance(trace_kind, str) or not trace_kind:
        return ("fail", "bindiff manifest has invalid trace_kind", None)
    if trace_address is not None and not isinstance(trace_address, str):
        return ("fail", "bindiff manifest has invalid trace_address", None)
    if cluster_id is not None and not isinstance(cluster_id, int):
        return ("fail", "bindiff manifest has invalid cluster_id", None)
    if core_id is not None and not isinstance(core_id, int):
        return ("fail", "bindiff manifest has invalid core_id", None)
    if warp is not None and not isinstance(warp, int):
        return ("fail", "bindiff manifest has invalid warp", None)

    golden_bin = chipyard_dir / golden_rel
    candidate_bin = log_dir / f"{elf_name}.bindiff.bin"
    bindiff_log_path = log_dir / f"{elf_name}.bindiff.log"
    trace_script = script_dir / "trace.py"
    bindiff_script = script_dir / "bindiff.py"

    if not sqlite_path.exists():
        return ("fail", f"sqlite trace not found: {sqlite_path}", str(bindiff_log_path))
    if not golden_bin.exists():
        return ("fail", f"golden bin not found: {golden_bin}", str(bindiff_log_path))

    trace_cmd = [
        sys.executable,
        str(trace_script),
        str(sqlite_path),
        "--table",
        trace_table,
        "--dump-bin",
        str(candidate_bin),
        "--kind",
        trace_kind,
    ]
    if trace_address is not None:
        trace_cmd.extend(["--address", trace_address])
    if cluster_id is not None:
        trace_cmd.extend(["--cluster", str(cluster_id)])
    if core_id is not None:
        trace_cmd.extend(["--core", str(core_id)])
    if warp is not None:
        trace_cmd.extend(["--warp", str(warp)])

    bindiff_cmd = [
        sys.executable,
        str(bindiff_script),
        str(golden_bin),
        str(candidate_bin),
    ]

    bindiff_log_path.parent.mkdir(parents=True, exist_ok=True)
    with bindiff_log_path.open("w", encoding="utf-8") as bindiff_log:
        bindiff_log.write(f"$ {' '.join(trace_cmd)}\n")
        trace_result = subprocess.run(
            trace_cmd,
            cwd=chipyard_dir,
            stdout=bindiff_log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        if trace_result.returncode != 0:
            return (
                "fail",
                f"trace dump failed with status {trace_result.returncode}",
                str(bindiff_log_path),
            )

        bindiff_log.write(f"\n$ {' '.join(bindiff_cmd)}\n")
        diff_result = subprocess.run(
            bindiff_cmd,
            cwd=chipyard_dir,
            stdout=bindiff_log,
            stderr=subprocess.STDOUT,
            text=True,
        )

    if diff_result.returncode == 0:
        return ("pass", "", str(bindiff_log_path))
    return ("fail", f"bindiff failed with status {diff_result.returncode}", str(bindiff_log_path))


def run_bindiff_from_sqlite(
    sqlite_path: Path,
    log_dir: Path | None = None,
    script_dir: Path | None = None,
) -> tuple[str | None, str, str | None]:
    sqlite_path = sqlite_path.resolve()
    script_dir = script_dir.resolve() if script_dir is not None else Path(__file__).resolve().parent
    chipyard_dir = script_dir.parents[2].resolve()
    manifest = load_bindiff_manifest(script_dir)
    elf_name = sqlite_path.stem
    bindiff_spec = manifest.get(elf_name)
    if log_dir is None:
        log_dir = sqlite_path.parent
    return run_bindiff_from_spec(
        sqlite_path=sqlite_path,
        elf_name=elf_name,
        log_dir=log_dir.resolve(),
        chipyard_dir=chipyard_dir,
        script_dir=script_dir,
        bindiff_spec=bindiff_spec,
    )

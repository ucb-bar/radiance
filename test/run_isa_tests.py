#!/usr/bin/env python3
"""Run Cyclotron ISA tests via the VCS simulator."""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

myname = Path(sys.argv[0]).name

def log_has_error(log_path):
    error_regex = re.compile(r"(error|difftest.*fail)", re.IGNORECASE)
    with log_path.open("r", encoding="utf-8", errors="ignore") as log_file:
        for line in log_file:
            if error_regex.search(line):
                return True
    return False


def get_and_check_sim_binary(config, sim_dir):
    if config == "soc":
        sim_binary = sim_dir / "simv-chipyard.harness-RadianceTapeoutSimConfig-debug"
    elif config == "backend":
        sim_binary = sim_dir / "simv-chipyard.unittest-MuonBackendTestConfig-debug"
    else:
        assert False, "unknown config"

    p = Path(sim_binary)
    if not p.exists():
        print("error: VCS binary not found at {}.  Have you make'd in Chipyard?" \
              .format(p.resolve()))
        sys.exit(1)

    return sim_binary


def run_binary(config, binary, elf, script_dir, chipyard_dir, sim_dir):
    elf = Path(elf)
    elf_name = elf.name
    log_dir = script_dir / "isa-test-logs" / f"{config}"
    fsdb_path = log_dir / f"{elf_name}.fsdb"
    log_path  = log_dir / f"{elf_name}.log"
    out_path  = log_dir / f"{elf_name}.out"

    dramsim_ini = \
        chipyard_dir/"generators"/"testchipip"/"src"/"main"/"resources"/"dramsim2_ini"

    cmd = [
        binary,
        "+permissive",
        "+dramsim",
        f"+dramsim_ini_dir={dramsim_ini}",
        "+max-cycles=10000000",
        "+ntb_random_seed_automatic",
        "+verbose",
        f"+fsdbfile={fsdb_path}",
        f"+loadmem={elf}",
        "+permissive-off",
        str(elf),
    ]

    log_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"[{myname}] running {elf}")

    with log_path.open("w", encoding="utf-8") as log_file, out_path.open(
        "w", encoding="utf-8"
    ) as err_file:
        process = subprocess.Popen(
            cmd,
            cwd=sim_dir,
            stdin=subprocess.DEVNULL,
            stdout=log_file,
            stderr=err_file,
            text=True,
            bufsize=1,
        )
        try:
            timeout = 5 * 60 # 5min
            status = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            status = process.wait()
            print(f"[{myname}] FAIL: {elf}: timeout after {timeout}s")
            return 1

    if status == 0 and log_has_error(log_path):
        print(f"[{myname}] FAIL: {elf}")
        return 1
    return status


def iter_elfs(elf_dir):
    if not elf_dir.is_dir():
        return
    for root, _, files in os.walk(elf_dir):
        for filename in files:
            path = Path(root) / filename
            try:
                if path.is_file() and os.access(path, os.X_OK):
                    yield path
            except OSError:
                continue


def sweep(config, binary, script_dir, chipyard_dir, sim_dir):
    radiance_dir = script_dir / ".."
    cyclotron_dir = radiance_dir / "cyclotron"
    if config == "soc":
        elf_dir = cyclotron_dir / "test" / "fused"
    else:
        elf_dir = cyclotron_dir / "test" / "isa-tests"
    print(f"[{myname}] searching for ELFs in {elf_dir}")

    executables = sorted(iter_elfs(elf_dir))
    if not executables:
        print(f"error: failed to find any ELF binary in {elf_dir}")
        sys.exit(1)
    statusall = 0
    for elf in executables:
        status = run_binary(config, binary, elf, script_dir, chipyard_dir, sim_dir)
        if status != 0 and statusall == 0:
            statusall = status
    return statusall


def discover_chipyard(script_dir):
    root = script_dir.parents[2].resolve()
    if root.name != "chipyard":
        print(f"error: failed to find chipyard root directory from PWD={script_dir}",
              file=sys.stderr)
        sys.exit(1)
    return root


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Run integration tests on the RTL using muon-isa-test ELF binaries.\n"
            "ELF binaries can either be given explicitly, or searched in the filesystem to do a sweep.\n"
            "Requires VCS simulation binary to be built."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter)

    parser.add_argument('binary', nargs="?",
                        help="ELF to run; if omitted, sweeps found ELFs on its own")
    parser.add_argument('-c', '--config', default='soc',
                        help="testbench config to run; (soc|backend). default is 'soc'")
    return parser.parse_args()


def main():
    script_dir = Path(__file__).resolve().parent
    chipyard_dir = discover_chipyard(script_dir)
    sim_dir = chipyard_dir / "sims/vcs"

    args = parse_args()
    config = args.config
    if not (config == "soc" or config == "backend"):
        print(f"error: unknown config '{config}'. must be (soc|backend)")
        sys.exit(1)

    sim_binary = get_and_check_sim_binary(config, sim_dir)
    print(f"[{myname}] using config: '{config}', binary: {sim_binary}")

    if args.binary:
        return run_binary(config, sim_binary, Path(args.binary), script_dir, chipyard_dir, sim_dir)
    return sweep(config, sim_binary, script_dir, chipyard_dir, sim_dir)


if __name__ == "__main__":
    raise SystemExit(main())

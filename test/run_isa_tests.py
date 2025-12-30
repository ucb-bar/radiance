#!/usr/bin/env python3
"""Run Cyclotron ISA tests via the VCS simulator."""

import argparse
import os
import re
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

myname = Path(sys.argv[0]).name

def log_has_error(log_path) -> str:
    reason = ""
    failed = False
    context_lines = 2
    curr_lines = context_lines

    error_regex = re.compile(r"(error|difftest.*fail)", re.IGNORECASE)
    with log_path.open("r", encoding="utf-8", errors="ignore") as log_file:
        for line in log_file:
            if failed and curr_lines > 0:
                reason += line
                curr_lines -= 1
            if error_regex.search(line):
                failed = True
                reason += line
                curr_lines = context_lines - 1
    return reason


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


def run_binary(config, binary, elf, log_dir, chipyard_dir, sim_dir):
    elf = Path(elf)
    elf_name = elf.name
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

    errstring = log_has_error(log_path).rstrip('\n')
    if status == 0 and errstring:
        print(f"[{myname}] FAIL: {elf}")
        print(f"{errstring}")
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


def sweep(config, binary, log_dir, script_dir, chipyard_dir, sim_dir, jobs):
    print(f"[{myname}] sweeping all ISA tests using {jobs} parallel jobs")

    radiance_dir = script_dir.parent
    cyclotron_dir = radiance_dir / "cyclotron"
    if config == "soc":
        elf_dir = cyclotron_dir / "test" / "fused"
    else:
        elf_dir = cyclotron_dir / "test" / "isa-tests"
    print(f"[{myname}] ELF dir: {elf_dir}")

    executables = sorted(iter_elfs(elf_dir))
    if not executables:
        print(f"error: failed to find any ELF binary in {elf_dir}")
        sys.exit(1)

    statusall = 0
    with ProcessPoolExecutor(max_workers=jobs) as executor:
        future_to_elf = {
            executor.submit(
                run_binary, config, binary, elf, log_dir, chipyard_dir, sim_dir
            ): elf
            for elf in executables
        }
        for future in as_completed(future_to_elf):
            elf = future_to_elf[future]
            try:
                status = future.result()
            except Exception as exc:
                print(f"[{myname}] FAIL: {elf} raised {exc}", file=sys.stderr)
                status = 1
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
    parser.add_argument('--log-dir', default='isa-test-logs',
                        help="directory to be created to place the logs. default is 'isa-test-logs'")
    parser.add_argument('-j', '--jobs', type=int, default=1,
                        help="maximum number of parallel simulations (default: 1)")
    return parser.parse_args()


def main():
    args = parse_args()
    log_dir_name = args.log_dir
    config = args.config
    if not (config == "soc" or config == "backend"):
        print(f"error: unknown config '{config}'. must be (soc|backend)")
        sys.exit(1)

    script_dir = Path(__file__).resolve().parent
    chipyard_dir = discover_chipyard(script_dir)
    sim_dir = chipyard_dir / "sims/vcs"
    log_dir = script_dir / log_dir_name / config
    sim_binary = get_and_check_sim_binary(config, sim_dir)
    jobs = args.jobs if args.jobs and args.jobs > 0 else 1

    print(f"[{myname}] using config '{config}'")
    print(f"[{myname}] sim binary: {sim_binary}")
    print(f"[{myname}] log dir: {log_dir}")

    if args.binary:
        elf = Path(args.binary)
        return run_binary(config, sim_binary, elf, log_dir, chipyard_dir, sim_dir)
    return sweep(config, sim_binary, log_dir, script_dir, chipyard_dir, sim_dir, jobs)


if __name__ == "__main__":
    raise SystemExit(main())

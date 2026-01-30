#!/usr/bin/env python3
"""Run Cyclotron ISA tests via the VCS simulator."""

import argparse
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import TextIO

myname = Path(sys.argv[0]).name
SIM_TIMEOUT = 5 * 60  # seconds


@dataclass
class RunningTest:
    elf: Path
    process: subprocess.Popen
    log_path: Path
    log_file: TextIO
    err_file: TextIO
    start_time: float
    timed_out: bool = False

    def close_streams(self):
        self.log_file.close()
        self.err_file.close()


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
    elif config == "core":
        sim_binary = sim_dir / "simv-chipyard.unittest-MuonCoreTestConfig-debug"
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


def launch_test(config, binary, elf, log_dir, chipyard_dir, sim_dir):
    elf = Path(elf)
    elf_name = elf.name
    fsdb_path = log_dir / f"{elf_name}.fsdb"
    log_path = log_dir / f"{elf_name}.log"
    out_path = log_dir / f"{elf_name}.out"

    dramsim_ini = (
        chipyard_dir
        / "generators"
        / "testchipip"
        / "src"
        / "main"
        / "resources"
        / "dramsim2_ini"
    )

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

    log_file = log_path.open("w", encoding="utf-8")
    err_file = out_path.open("w", encoding="utf-8")
    process = subprocess.Popen(
        cmd,
        cwd=sim_dir,
        stdin=subprocess.DEVNULL,
        stdout=log_file,
        stderr=err_file,
        text=True,
        bufsize=1,
    )
    return RunningTest(
        elf=elf,
        process=process,
        log_path=log_path,
        log_file=log_file,
        err_file=err_file,
        start_time=time.monotonic(),
    )


def finalize_test(test: RunningTest, status: int) -> int:
    try:
        if test.timed_out:
            print(f"[{myname}] FAIL: {test.elf}: timeout after {SIM_TIMEOUT}s")
            return 1

        errstring = log_has_error(test.log_path).rstrip("\n")
        if status != 0 or errstring:
            print(f"[{myname}] FAIL: {test.elf} (status: {status})")
            if errstring:
                print(f"{errstring}")
            return 1
        return status
    finally:
        test.close_streams()


# single-threaded
def run_binary(config, binary, elf, log_dir, chipyard_dir, sim_dir):
    test = launch_test(config, binary, elf, log_dir, chipyard_dir, sim_dir)
    try:
        status = test.process.wait(timeout=SIM_TIMEOUT)
    except subprocess.TimeoutExpired:
        test.timed_out = True
        test.process.kill()
        status = test.process.wait()
    return finalize_test(test, status)


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
    running = []
    executables_iter = iter(executables)
    pending_exhausted = False

    try:
        while True:
            while len(running) < jobs and not pending_exhausted:
                try:
                    elf = next(executables_iter)
                except StopIteration:
                    pending_exhausted = True
                    break
                running.append(
                    launch_test(config, binary, elf, log_dir, chipyard_dir, sim_dir)
                )

            if not running:
                break

            still_running = []
            for test in running:
                status = test.process.poll()
                if status is None:
                    elapsed = time.monotonic() - test.start_time
                    if elapsed > SIM_TIMEOUT:
                        test.timed_out = True
                        test.process.kill()
                        status = test.process.wait()
                if status is None:
                    still_running.append(test)
                    continue
                result = finalize_test(test, status)
                if result != 0 and statusall == 0:
                    statusall = result

            running = still_running

            if running:
                time.sleep(0.1)

    except KeyboardInterrupt:
        print(f"[{myname}] Terminating...", file=sys.stderr)
        for test in running:
            test.process.kill()
        for test in running:
            try:
                test.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            test.close_streams()
        raise

    return statusall


def discover_chipyard(script_dir):
    root = script_dir.parents[2].resolve()
    if not root.name.startswith("chipyard"):
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
                        help="testbench config to run; (soc|core|backend). default is 'soc'")
    parser.add_argument('--log-dir', default='isa-test-logs',
                        help="directory to be created to place the logs. default is 'isa-test-logs'")
    parser.add_argument('-j', '--jobs', type=int, default=1,
                        help="maximum number of parallel simulations (default: 1)")
    return parser.parse_args()


def main():
    args = parse_args()
    log_dir_name = args.log_dir
    config = args.config
    if not (config == "soc" or config == "core" or config == "backend"):
        print(f"error: unknown config '{config}'. must be (soc|core|backend)")
        sys.exit(1)

    script_dir = Path(__file__).resolve().parent
    chipyard_dir = discover_chipyard(script_dir)
    sim_dir = chipyard_dir / "sims/vcs"
    log_dir = script_dir / log_dir_name / config
    sim_binary = get_and_check_sim_binary(config, sim_dir)
    jobs = args.jobs if args.jobs and args.jobs > 0 else 1

    print(f"[{myname}] using config '{config}'")
    print(f"[{myname}] sim binary: {sim_binary}")
    print(f"[{myname}] writing logs to {log_dir}")

    if args.binary:
        elf = Path(args.binary)
        return run_binary(config, sim_binary, elf, log_dir, chipyard_dir, sim_dir)
    return sweep(config, sim_binary, log_dir, script_dir, chipyard_dir, sim_dir, jobs)


if __name__ == "__main__":
    raise SystemExit(main())

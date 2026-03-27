#!/usr/bin/env python3
"""Run ELF binary tests via the VCS simulator."""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import TextIO

myname = Path(sys.argv[0]).name
DEFAULT_SIM_TIMEOUT = 45 * 60  # seconds

# list of (config, test_name) that are waived
waivers = [
    # these are confirmed working in end-to-end kernels, but not root-caused
    # for isa-tests
    ("soc", "mu32-p-flush"),
    ("soc", "rv32ui-p-fence_i"),
    ("soc", "rv32uzfh-p-fcvt_w"),
    ("soc", "rv32uzfh-p-fdiv"),
    ("soc", "rv32uzfh-p-recoding"),
    ("soc", "mu32-p-spin"),
    ("soc", "vx32-p-pred"), # this one needs a look
    # wspawn isa test is written using the old change-pc-of-initiator
    # assumption and not updated yet. TODO
    ("soc", "vx32-p-wspawn"),
    # neutrino tests are incomplete atm
    ("soc", "neutrino-p-sync"),
    # standalone core config ties off shared memory, so it just hangs
    # waiting for a response
    ("core", "mu32-p-lb_shared"),
    ("core", "mu32-p-lbu_shared"),
    ("core", "mu32-p-lh_shared"),
    ("core", "mu32-p-lhu_shared"),
    ("core", "mu32-p-lw_shared"),
    ("core", "mu32-p-ld_st_shared"),
    ("core", "mu32-p-sb_shared"),
    ("core", "mu32-p-sh_shared"),
    ("core", "mu32-p-sw_shared"),
    ("core", "mu32-p-st_ld_shared"),
    # core config is single-core and does not support barriers
    ("core", "vx32-p-bar"),
    ("core", "neutrino-p-sync"),
    ("cosim", "neutrino-p-sync"),
    ("backend", "neutrino-p-sync"),
]

@dataclass
class RunningTest:
    elf: Path
    process: subprocess.Popen
    log_path: Path
    sqlite_path: Path
    log_file: TextIO
    err_file: TextIO
    start_time: float
    timed_out: bool = False

    def close_streams(self):
        self.log_file.close()
        self.err_file.close()


@dataclass
class TestResult:
    name: str
    elf: str
    config: str
    status: str
    exit_code: int
    duration_sec: float
    log_path: str
    sqlite_path: str
    stderr_path: str
    failure_reason: str
    cycles: list[dict[str, object]]
    ipc: list[dict[str, object]]
    bindiff_status: str | None
    bindiff_failure_reason: str
    bindiff_log_path: str | None


@dataclass
class RunResult:
    config: str
    sim_binary: str
    log_dir: str
    total: int
    passed: int
    failed: int
    timed_out: int
    waived: int
    results: list[TestResult]

    @property
    def exit_code(self) -> int:
        return 0 if self.failed == 0 else 1


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


def extract_ipc_results(log_path: Path) -> list[dict[str, object]]:
    finished_regex = re.compile(r"Muon \[cluster (\d+) core (\d+)\] finished execution\.")
    cycles_regex = re.compile(r"Cycles:\s*(\d+)")
    ipc_regex = re.compile(r"IPC:\s*([0-9]+(?:\.[0-9]+)?)")

    pending_context: tuple[int, int] | None = None
    cycles_results = []
    ipc_results = []

    with log_path.open("r", encoding="utf-8", errors="ignore") as log_file:
        for line in log_file:
            finished_match = finished_regex.search(line)
            if finished_match:
                pending_context = (
                    int(finished_match.group(1)),
                    int(finished_match.group(2)),
                )
                continue

            cycles_match = cycles_regex.search(line)
            if cycles_match and pending_context is not None:
                cycles_results.append(
                    {
                        "cluster_id": pending_context[0],
                        "core_id": pending_context[1],
                        "cycles": int(cycles_match.group(1)),
                    }
                )
                continue

            ipc_match = ipc_regex.search(line)
            if ipc_match and pending_context is not None:
                ipc_results.append(
                    {
                        "cluster_id": pending_context[0],
                        "core_id": pending_context[1],
                        "ipc": float(ipc_match.group(1)),
                    }
                )
                pending_context = None

    return (cycles_results, ipc_results)

def get_and_check_sim_binary(config, sim_dir):
    if config == "soc":
        sim_binary = sim_dir / "simv-chipyard.harness-RadianceTapeoutSimTraceConfig-debug"
    elif config == "core":
        sim_binary = sim_dir / "simv-chipyard.unittest-MuonCoreTestConfig-debug"
    elif config == "cosim":
        sim_binary = sim_dir / "simv-chipyard.harness-RadianceCyclotronConfig-debug"
    elif config == "hostlaunch":
        sim_binary = sim_dir / "simv-chipyard.harness-TetheredRadianceTapeoutConfig-debug"
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
    elf = Path(elf).resolve()
    elf_name = elf.name
    fsdb_path = log_dir / f"{elf_name}.fsdb"
    log_path = log_dir / f"{elf_name}.log"
    out_path = log_dir / f"{elf_name}.out"
    sqlite_path = (log_dir / f"{elf_name}.sqlite").resolve()

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
        f"+trace-db={sqlite_path}",
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
        sqlite_path=sqlite_path,
        log_file=log_file,
        err_file=err_file,
        start_time=time.monotonic(),
    )


def finalize_test(
    config: str,
    test: RunningTest,
    status: int,
    timeout_sec: int,
) -> TestResult:
    duration_sec = time.monotonic() - test.start_time
    try:
        cycles, ipc = extract_ipc_results(test.log_path)
        if test.timed_out:
            print(f"[{myname}] FAIL: {test.elf}: timeout after {timeout_sec}s")
            return TestResult(
                name=test.elf.name,
                elf=str(test.elf),
                config=config,
                status="timeout",
                exit_code=status,
                duration_sec=duration_sec,
                log_path=str(test.log_path),
                sqlite_path=str(test.sqlite_path),
                stderr_path=str(test.err_file.name),
                failure_reason=f"timeout after {timeout_sec}s",
                cycles=cycles,
                ipc=ipc,
                bindiff_status=None,
                bindiff_failure_reason="",
                bindiff_log_path=None,
            )

        errstring = log_has_error(test.log_path).rstrip("\n")
        if status != 0 or errstring:
            print(f"[{myname}] FAIL: {test.elf} (status: {status})")
            if errstring:
                print(f"{errstring}")
            failure_reason = errstring if errstring else f"process exited with status {status}"
            return TestResult(
                name=test.elf.name,
                elf=str(test.elf),
                config=config,
                status="fail",
                exit_code=status,
                duration_sec=duration_sec,
                log_path=str(test.log_path),
                sqlite_path=str(test.sqlite_path),
                stderr_path=str(test.err_file.name),
                failure_reason=failure_reason,
                cycles=cycles,
                ipc=ipc,
                bindiff_status=None,
                bindiff_failure_reason="",
                bindiff_log_path=None,
            )
        return TestResult(
            name=test.elf.name,
            elf=str(test.elf),
            config=config,
            status="pass",
            exit_code=status,
            duration_sec=duration_sec,
            log_path=str(test.log_path),
            sqlite_path=str(test.sqlite_path),
            stderr_path=str(test.err_file.name),
            failure_reason="",
            cycles=cycles,
            ipc=ipc,
            bindiff_status=None,
            bindiff_failure_reason="",
            bindiff_log_path=None,
        )
    finally:
        test.close_streams()


def summarize_results(config, binary, log_dir, results):
    passed = sum(1 for result in results if result.status == "pass")
    failed = sum(1 for result in results if result.status == "fail")
    timed_out = sum(1 for result in results if result.status == "timeout")
    waived = sum(1 for result in results if result.status == "waived")
    return RunResult(
        config=config,
        sim_binary=str(binary),
        log_dir=str(log_dir),
        total=len(results),
        passed=passed,
        failed=failed,
        timed_out=timed_out,
        waived=waived,
        results=results,
    )


def write_json_result(json_path, run_result: RunResult):
    json_path.parent.mkdir(parents=True, exist_ok=True)
    with json_path.open("w", encoding="utf-8") as f:
        json.dump(asdict(run_result), f, indent=2)
        f.write("\n")


def make_waived_result(config: str, elf: Path, log_dir: Path) -> TestResult:
    elf = elf.resolve()
    elf_name = elf.name
    reason = f"waived for config '{config}'"
    return TestResult(
        name=elf_name,
        elf=str(elf),
        config=config,
        status="waived",
        exit_code=0,
        duration_sec=0.0,
        log_path=str(log_dir / f"{elf_name}.log"),
        sqlite_path=str((log_dir / f"{elf_name}.sqlite").resolve()),
        stderr_path=str(log_dir / f"{elf_name}.out"),
        failure_reason=reason,
        cycles=[],
        ipc=[],
        bindiff_status=None,
        bindiff_failure_reason="",
        bindiff_log_path=None,
    )


# single-threaded
def run_binary(config, binary, elf, log_dir, chipyard_dir, sim_dir, timeout_sec):
    test = launch_test(config, binary, elf, log_dir, chipyard_dir, sim_dir)
    try:
        status = test.process.wait(timeout=timeout_sec)
    except subprocess.TimeoutExpired:
        test.timed_out = True
        test.process.kill()
        status = test.process.wait()
    result = finalize_test(
        config,
        test,
        status,
        timeout_sec,
    )
    return summarize_results(config, binary, log_dir, [result])


def iter_elfs(elf_dir):
    if not elf_dir.is_dir():
        return
    for root, _, files in os.walk(elf_dir):
        for filename in files:
            path = Path(root) / filename
            try:
                if path.is_file() and os.access(path, os.X_OK):
                    yield path.resolve()
            except OSError:
                continue


def default_elf_dir(config, script_dir):
    radiance_dir = script_dir.parent
    cyclotron_dir = radiance_dir / "cyclotron"
    if config == "soc" or config == "cosim":
        return cyclotron_dir / "test" / "fused"
    if config == "hostlaunch":
        return cyclotron_dir / "test" / "host-launch"
    return cyclotron_dir / "test" / "isa-tests"


def sweep(config, binary, log_dir, script_dir, chipyard_dir, sim_dir, jobs, timeout_sec, elf_dir=None):
    print(f"[{myname}] sweeping all ELF tests using {jobs} parallel jobs")

    elf_dir = Path(elf_dir) if elf_dir is not None else default_elf_dir(config, script_dir)
    print(f"[{myname}] ELF dir: {elf_dir}")

    executables = sorted(iter_elfs(elf_dir))
    if not executables:
        print(f"error: failed to find any ELF binary in {elf_dir}")
        sys.exit(1)

    results = []
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

                if (config, elf.name) in waivers:
                    print(f"[{myname}] waived {elf} for config '{config}'")
                    results.append(make_waived_result(config, elf, log_dir))
                    continue

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
                    if elapsed > timeout_sec:
                        test.timed_out = True
                        test.process.kill()
                        status = test.process.wait()
                if status is None:
                    still_running.append(test)
                    continue
                result = finalize_test(
                    config,
                    test,
                    status,
                    timeout_sec,
                )
                results.append(result)

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

    return summarize_results(config, binary, log_dir, results)


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
            "Run integration tests on the RTL using ELF binaries.\n"
            "ELF binaries can either be given explicitly, or searched in the filesystem to do a sweep.\n"
            "Requires VCS simulation binary to be built."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter)

    parser.add_argument('binary', nargs="?",
                        help="ELF to run; if omitted, sweeps found ELFs on its own")
    parser.add_argument('-c', '--config', default='soc',
                        help="testbench config to run; (soc|core|cosim|backend). default is 'soc'")
    parser.add_argument('--log-dir', default='binary-test-logs',
                        help="directory to be created to place the logs. default is 'binary-test-logs'")
    parser.add_argument('--json-out',
                        help="write machine-readable test results to this JSON path. "
                             "default is <log-dir>/<config>/results.json")
    parser.add_argument('--elf-dir', type=Path,
                        help="directory to recursively search for ELF binaries when sweeping")
    parser.add_argument('-j', '--jobs', type=int, default=1,
                        help="maximum number of parallel simulations (default: 1)")
    parser.add_argument('--timeout', type=int, default=DEFAULT_SIM_TIMEOUT,
                        help=f"per-test simulator timeout in seconds (default: {DEFAULT_SIM_TIMEOUT})")
    return parser.parse_args()


def main():
    args = parse_args()
    config = args.config
    if not (config == "soc" or config == "core" or config == "cosim" or config == "hostlaunch" or config == "backend"):
        print(f"error: unknown config '{config}'. must be (soc|core|cosim|hostlaunch|backend)")
        sys.exit(1)

    script_dir = Path(__file__).resolve().parent
    chipyard_dir = discover_chipyard(script_dir)
    sim_dir = chipyard_dir / "sims/vcs"
    log_dir_base = Path(args.log_dir)
    if not log_dir_base.is_absolute():
        log_dir_base = script_dir / log_dir_base
    log_dir = log_dir_base / config
    sim_binary = get_and_check_sim_binary(config, sim_dir)
    jobs = args.jobs if args.jobs and args.jobs > 0 else 1
    timeout_sec = args.timeout if args.timeout and args.timeout > 0 else DEFAULT_SIM_TIMEOUT
    json_out = Path(args.json_out).resolve() if args.json_out else (log_dir / "results.json")
    print(f"[{myname}] using config '{config}'")
    print(f"[{myname}] sim binary: {sim_binary}")
    print(f"[{myname}] writing logs to {log_dir}")
    print(f"[{myname}] writing json to {json_out}")
    print(f"[{myname}] per-test timeout: {timeout_sec}s")

    if args.binary:
        elf = Path(args.binary).resolve()
        run_result = run_binary(
            config,
            sim_binary,
            elf,
            log_dir,
            chipyard_dir,
            sim_dir,
            timeout_sec,
        )
    else:
        run_result = sweep(
            config,
            sim_binary,
            log_dir,
            script_dir,
            chipyard_dir,
            sim_dir,
            jobs,
            timeout_sec,
            args.elf_dir,
        )
    write_json_result(json_out, run_result)
    print(f"[{myname}] wrote json results to {json_out.resolve()}")
    return run_result.exit_code


if __name__ == "__main__":
    raise SystemExit(main())

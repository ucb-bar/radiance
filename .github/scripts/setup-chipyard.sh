#!/usr/bin/env bash

set -euo pipefail

CACHE_HIT="${1:-false}"

cd "${CHIPYARD_DIR}"

if [[ "${CACHE_HIT}" == "true" ]]; then
    echo "[CI] conda cache hit! Skipping conda and toolchain steps"
    ./build-setup.sh riscv-tools --skip-conda --skip-toolchain --skip-firesim --skip-marshal
else
    ./build-setup.sh riscv-tools --skip-firesim --skip-marshal
fi

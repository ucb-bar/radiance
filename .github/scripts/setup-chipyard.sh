#!/usr/bin/env bash

set -exo pipefail

CACHE_HIT="${1:-false}"

# github runner knocks out with -jN>=4
export MAKEFLAGS="-j"

cd "${CHIPYARD_DIR}"

if [[ "${CACHE_HIT}" == "true" ]]; then
    echo "[CI] Cache hit! Skipping conda and toolchain steps"
    export RISCV="${CHIPYARD_DIR}/.conda-env/riscv-tools"
    [ -d "${RISCV}" ] || ( echo "error: ${RISCV} does not exist!" && exit 1 )
    source $(conda info --base)/etc/profile.d/conda.sh
    conda activate "${CHIPYARD_DIR}/.conda-env"
    ./build-setup.sh riscv-tools --skip-conda --skip-toolchain --skip-precompile --skip-firesim --skip-marshal
else
    ./build-setup.sh riscv-tools --skip-precompile --skip-firesim --skip-marshal
fi

#!/usr/bin/env bash

set -exo pipefail

CACHE_HIT="${1:-false}"

# github runner knocks out with -jN>=4
export MAKEFLAGS="-j"

cd "${CHIPYARD_DIR}"

if [[ "${CACHE_HIT}" == "true" ]]; then
    echo "[CI] Cache hit! Skipping conda and toolchain steps"
    export RISCV="${CHIPYARD_CONDA_ENV_PATH}/riscv-tools"
    [ -d "${RISCV}" ] || ( echo "error: ${RISCV} does not exist!" && exit 1 )
    source $(conda info --base)/etc/profile.d/conda.sh
    conda activate "${CHIPYARD_CONDA_ENV_PATH}"
    ./build-setup.sh riscv-tools --conda-env-name "${CHIPYARD_CONDA_ENV_NAME}" \
        --skip-conda --skip-toolchain --skip-ctags --skip-precompile --skip-firesim --skip-marshal --skip-circt
else
    ./build-setup.sh riscv-tools --conda-env-name "${CHIPYARD_CONDA_ENV_NAME}" \
        --skip-ctags --skip-precompile --skip-firesim --skip-marshal
fi

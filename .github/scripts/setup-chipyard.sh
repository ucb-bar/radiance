#!/usr/bin/env bash

set -euo pipefail

WORKDIR="${GITHUB_WORKSPACE:-$(pwd)}"
CHIPYARD_DIR="${WORKDIR}/chipyard"

echo "[CI] WORKDIR=${WORKDIR}"
echo "[CI] CHIPYARD_DIR=${CHIPYARD_DIR}"

rm -rf "${CHIPYARD_DIR}"
git clone "https://github.com/ucb-bar/chipyard.git"
cd "${CHIPYARD_DIR}"
git checkout main

echo "MAKEFLAGS is ${MAKEFLAGS}"

./build-setup.sh riscv-tools -s 6 -s 7 -s 8 -s 9

# export CHIPYARD_DIR to later workflow steps
if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "CHIPYARD_DIR=${CHIPYARD_DIR}"
  } >> "${GITHUB_ENV}"
fi

echo "[CI] Chipyard setup complete in ${CHIPYARD_DIR}"

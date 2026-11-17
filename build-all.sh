#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

PATCHES=(
  patch-map-player-leak
  patch-fakeplayer-network-leak
  patch-tis-projectile-threadlocal
  patch-confluxmap-chunkstate-churn
  patch-ledger-queue-guard
)

rm -rf dist
mkdir -p dist

for p in "${PATCHES[@]}"; do
  echo "=== Building $p ==="
  bash "$p/build.sh"
  cp "$p/build/libs/"*.jar dist/
done

echo "=== Built jars in $(pwd)/dist ==="
ls -lh dist/

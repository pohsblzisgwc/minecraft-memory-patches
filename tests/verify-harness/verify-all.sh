#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT_DIR="$(cd ../.. && pwd)"
MC_DIR="${MC_DIR:?Set MC_DIR to your local Fabric server directory}"
SERVER_JAR="${SERVER_JAR:-$MC_DIR/versions/26.3/server-26.3.jar}"
MODS_ZIP="${MODS_ZIP:?Set MODS_ZIP to a local archive with the required mods/ entries}"
SCRATCH_SRV="$(mktemp -d /tmp/memory-patch-verify.XXXXXX)"
echo "Verification workspace: $SCRATCH_SRV"

echo "=== 1. Building all 5 patch mods ==="
bash "$ROOT_DIR/build-all.sh"

echo "=== 2. Preparing isolated test server ==="
mkdir -p "$SCRATCH_SRV/mods" "$SCRATCH_SRV/config"
ln -s "$MC_DIR/libraries" "$SCRATCH_SRV/libraries"
ln -s "$MC_DIR/versions" "$SCRATCH_SRV/versions"
ln -s "$MC_DIR/server.jar" "$SCRATCH_SRV/server.jar"
cp "$MC_DIR/fabric-server-launch.jar" "$SCRATCH_SRV/"
cp "$MC_DIR/fabric-server-launcher.properties" "$SCRATCH_SRV/"

python3 - <<'PY' "$MODS_ZIP" "$SCRATCH_SRV/mods"
import sys, zipfile, os
mods_zip, out_dir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(mods_zip) as z:
    for name in z.namelist():
        if name.startswith("mods/") and name.endswith(".jar"):
            base = os.path.basename(name)
            if base.startswith("whitelist_of_ledger") or base.startswith("gugle-carpet-addition"):
                continue
            with open(os.path.join(out_dir, base), "wb") as f:
                f.write(z.read(name))
PY

cp "$ROOT_DIR/dist/"*.jar "$SCRATCH_SRV/mods/"

echo "=== 3. Compiling verification harness mod ==="
HDIR="$ROOT_DIR/tests/verify-harness"
rm -rf "$HDIR/build"
mkdir -p "$HDIR/build/deps" "$HDIR/build/classes" "$HDIR/build/libs"
python3 - <<'PY' "$SCRATCH_SRV/mods" "$HDIR/build/deps"
import sys, zipfile, glob, os
mods_dir, dep_dir = sys.argv[1], sys.argv[2]
for j in glob.glob(os.path.join(mods_dir, "*.jar")):
    with zipfile.ZipFile(j) as z:
        for n in z.namelist():
            if n.startswith("META-INF/jars/") and n.endswith(".jar"):
                with open(os.path.join(dep_dir, os.path.basename(n)), "wb") as f:
                    f.write(z.read(n))
PY
CP="$SERVER_JAR:$(find "$MC_DIR/libraries" "$SCRATCH_SRV/mods" "$HDIR/build/deps" -name '*.jar' -printf '%p:' | tr -d '\n')"
javac -proc:none --release 25 -encoding UTF-8 -cp "$CP" -d "$HDIR/build/classes" $(find "$HDIR/src/main/java" -name '*.java')
cp -r "$HDIR/src/main/resources/." "$HDIR/build/classes/"
jar --create --file "$SCRATCH_SRV/mods/patch-verify-harness-1.0.0.jar" -C "$HDIR/build/classes" .

cat > "$SCRATCH_SRV/eula.txt" <<'EOF'
eula=true
EOF
cat > "$SCRATCH_SRV/server.properties" <<'EOF'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-type=minecraft\:flat
spawn-protection=0
max-tick-time=-1
view-distance=4
simulation-distance=4
EOF

python3 - "$SCRATCH_SRV" "${RECOVERY_FIXTURE:-none}" <<'PYFIXTURE'
from pathlib import Path
import sqlite3, sys
root, mode = Path(sys.argv[1]), sys.argv[2]
if mode != "none":
    path = root / "world/ledger.sqlite"
    path.parent.mkdir(parents=True, exist_ok=True)
    if mode == "corrupt": path.write_bytes(b"ledger recovery fixture: not a database")
    elif mode == "missing":
        with sqlite3.connect(path) as c: c.execute("CREATE TABLE players(id INTEGER)")
    else: raise ValueError(mode)
PYFIXTURE

echo "=== 4. Running headless Fabric 26.3 verification server ==="
(cd "$SCRATCH_SRV" && timeout --kill-after=10 90 java -Xmx2G -Dledger.verify.recoveryFixture="${RECOVERY_FIXTURE:-none}" -Dledger.queueGuard.maxSize=25 -Dledger.queueGuard.dropOldest="${DROP_OLDEST:-false}" -Dfabric.log.level=info -jar fabric-server-launch.jar nogui </dev/null) 2>&1 | tee "$SCRATCH_SRV/verify.log"

grep -q "ALL_REGRESSION_CHECKS_PASSED" "$SCRATCH_SRV/verify.log"
echo "=== 5. Checking for Mixin errors/warnings ==="
if grep -iE "MixinApplyError|InvalidMixinException|Critical injection failure" "$SCRATCH_SRV/verify.log"; then
  echo "FAIL: Mixin error found in verify.log!"
  exit 1
fi
echo "SUCCESS: All 5 patches loaded & passed all live Fabric 26.3 runtime checks!"

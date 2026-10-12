#!/usr/bin/env bash
# Headless A/B for the Huge Josh Land pack.
# NeoForge 21.1.233, Create 6.0.10, Sable 2.0.3, Aeronautics 1.3.0, no Sodium.
# Usage: bench/run-headless.sh /path/to/neoforge-server /path/to/mod-jars
set -euo pipefail

SERVER=${1:-/tmp/nf-server}
JARS=${2:-/tmp/bench-libs}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/bench/results"
mkdir -p "$OUT"

create_jar=$(ls "$JARS"/create*.jar | head -1)
sable_jar=$(ls "$JARS"/sable.jar | head -1)
aero_jar=$(ls "$JARS"/aeronautics.jar | head -1)
probe_jar=$(ls "$ROOT"/build/libs/shiphull-bench-probe-*.jar | head -1)
hull_jar=$(ls "$ROOT"/build/libs/aeronautics_ship_hull-*.jar | grep -v sources | grep -v javadoc | head -1)

cat > "$SERVER/eula.txt" <<'EOF'
eula=true
EOF

cat > "$SERVER/server.properties" <<'EOF'
online-mode=false
spawn-protection=0
level-type=minecraft:flat
level-seed=121121
view-distance=4
simulation-distance=4
max-tick-time=-1
motd=shiphull-bench
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
sync-chunk-writes=false
network-compression-threshold=-1
max-players=1
enable-rcon=false
white-list=false
EOF

run_one() {
  local name=$1
  local with_hull=$2
  rm -rf "$SERVER/world" "$SERVER/config" "$SERVER/mods"
  mkdir -p "$SERVER/mods"
  cp "$create_jar" "$sable_jar" "$aero_jar" "$probe_jar" "$SERVER/mods/"
  if [[ "$with_hull" == "yes" ]]; then
    cp "$hull_jar" "$SERVER/mods/"
  fi
  if ls "$SERVER/mods"/*odium* >/dev/null 2>&1; then
    echo "refusing to run: Sodium is in the mods folder" >&2
    exit 1
  fi
  cat > "$SERVER/user_jvm_args.txt" <<EOF
-Xms2G
-Xmx3G
-Dshiphull.bench=true
-Dshiphull.bench.out=$SERVER/bench-result.json
${BENCH_EXTRA_JVM:-}
EOF
  echo "[bench] starting $name"
  set +e
  # --foreground keeps the server in this terminal's process group. A new
  # process group stops on SIGTTIN the moment the dedicated server reads stdin.
  (cd "$SERVER" && timeout --foreground 12m ./run.sh --nogui) > "$OUT/$name.log" 2>&1
  local code=$?
  set -e
  if [[ ! -f "$SERVER/bench-result.json" ]]; then
    echo "[bench] $name produced no result (exit $code). Tail:" >&2
    tail -80 "$OUT/$name.log" >&2
    exit 1
  fi
  cp "$SERVER/bench-result.json" "$OUT/$name.json"
  if ! grep -q "SHIPHULL_BENCH_DONE" "$OUT/$name.log"; then
    echo "[bench] $name log has no completion line" >&2
    exit 1
  fi
  echo "[bench] $name done"
}

run_one baseline no
run_one with-mod yes
python3 "$ROOT/bench/compare.py" "$OUT/baseline.json" "$OUT/with-mod.json" > "$OUT/comparison.md"
echo "[bench] wrote $OUT/comparison.md"

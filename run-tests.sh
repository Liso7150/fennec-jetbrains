#!/usr/bin/env bash
# Runs the Fennec plugin test suites:
#   - Rust sidecar: unit tests + stdio JSON-RPC integration tests (built binary)
#   - Kotlin headless: SharedConfig contract + AirAgentRegistrar file behavior
# No IDE, no network, no model: loopback fakes and temporary homes only.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${FENNEC_IDE_PLATFORM:-C:/Program Files/JetBrains/RustRover 2026.3}"
KOTLINC="${KOTLINC:-D:/Fennec-Suite/engine-bench/kotlinc/kotlinc/bin/kotlinc.bat}"
SIDECAR_TARGET="${FENNEC_SIDECAR_TARGET:-x86_64-pc-windows-gnu}"
OUT="$HERE/build/test"

if [ ! -d "$PLATFORM/lib" ]; then
  echo "IntelliJ Platform not found at: $PLATFORM" >&2
  exit 1
fi

echo "=== Rust sidecar tests ==="
( cd "$HERE/sidecar" && cargo test --offline -j 6 --target "$SIDECAR_TARGET" )

echo "=== Kotlin headless tests ==="
mkdir -p "$OUT/classes"
export JAVA_HOME="$PLATFORM/jbr"
JAVA="$PLATFORM/jbr/bin/java.exe"
[ -x "$JAVA" ] || JAVA="$(command -v java)"

CP=$(ls "$PLATFORM/lib/"*.jar | cygpath -m -f - | paste -sd ';' -)
WIN_CLASSES=$(cygpath -m "$OUT/classes")
ARGS="$OUT/kotlinc.args"
{
  printf '%s\n' "-no-stdlib" "-no-reflect" "-jvm-target" "21" "-classpath" "\"$CP\"" "-d" "$WIN_CLASSES"
  find "$HERE/src/main/kotlin" "$HERE/src/test/kotlin" -name '*.kt' | cygpath -m -f -
} > "$ARGS"
"$KOTLINC" "@$(cygpath -m "$ARGS")"

RUNARGS="$OUT/java.args"
printf '%s\n' "-classpath" "\"$CP;$WIN_CLASSES\"" "fennec.plugin.TestMain" > "$RUNARGS"
"$JAVA" "@$(cygpath -m "$RUNARGS")"
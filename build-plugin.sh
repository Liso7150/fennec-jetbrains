#!/usr/bin/env bash
# Builds the Fennec JetBrains plugin (Kotlin + a bundled Rust sidecar) without Gradle.
#
# Compiles Kotlin against an installed IntelliJ Platform (263.x) with the local
# kotlinc, builds the Rust sidecar offline, and packages a standard plugin zip that
# "Install Plugin from Disk" accepts. The sidecar binary is bundled under
# sidecar/<os-dir>/ so the plugin can install it per OS at runtime.
#
# Overrides (mirroring FENNEC_IDE_PLATFORM):
#   KOTLINC              path to kotlinc(.bat)
#   FENNEC_IDE_PLATFORM  IntelliJ-based IDE install dir (provides lib jars + JBR)
#   FENNEC_SIDECAR_TARGET  Rust target triple to build (default: this machine's)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${FENNEC_IDE_PLATFORM:-C:/Program Files/JetBrains/RustRover 2026.3}"
KOTLINC="${KOTLINC:-D:/Fennec-Suite/engine-bench/kotlinc/kotlinc/bin/kotlinc.bat}"
SIDECAR_TARGET="${FENNEC_SIDECAR_TARGET:-x86_64-pc-windows-gnu}"
OUT="$HERE/build"
CLASSES="$OUT/classes"
DIST="$OUT/fennec-desktop"

if [ ! -d "$PLATFORM/lib" ]; then
  echo "IntelliJ Platform not found at: $PLATFORM" >&2
  echo "Set FENNEC_IDE_PLATFORM to a JetBrains IDE installation directory." >&2
  exit 1
fi
if [ ! -f "$KOTLINC" ]; then
  echo "kotlinc not found at: $KOTLINC" >&2
  echo "Set KOTLINC to a Kotlin compiler (kotlinc.bat on Windows)." >&2
  exit 1
fi

echo "platform:       $PLATFORM"
echo "kotlinc:        $KOTLINC"
echo "sidecar target: $SIDECAR_TARGET"

# --- Rust sidecar (offline, dependency-light) ---
echo "=== building Rust sidecar ==="
( cd "$HERE/sidecar" && cargo build --release --offline -j 6 --target "$SIDECAR_TARGET" )

rm -rf "$OUT"
mkdir -p "$CLASSES" "$DIST/lib"

# --- Kotlin: compile against the platform, no bundled stdlib (the IDE provides it) ---
JBR="$PLATFORM/jbr"
export JAVA_HOME="$JBR"
JAR="$JBR/bin/jar.exe"
[ -x "$JAR" ] || JAR="$(command -v jar)"
if [ -z "$JAR" ]; then
  echo "No jar tool found (need a JDK on PATH)." >&2
  exit 1
fi
echo "jar:            $JAR"

CP=$(ls "$PLATFORM/lib/"*.jar | cygpath -m -f - | paste -sd ';' -)
WIN_CLASSES=$(cygpath -m "$CLASSES")
ARGS="$OUT/kotlinc.args"
{
  printf '%s\n' "-no-stdlib" "-no-reflect" "-jvm-target" "21" "-classpath" "\"$CP\"" "-d" "$WIN_CLASSES"
  find "$HERE/src/main/kotlin" -name '*.kt' | cygpath -m -f -
} > "$ARGS"
echo "=== compiling Kotlin ==="
"$KOTLINC" "@$(cygpath -m "$ARGS")"

# --- resources (plugin.xml, icon) ---
cp -r "$HERE/src/main/resources/." "$CLASSES/"

# --- bundle every built sidecar binary under its OS dir ---
osdir_for() {
  case "$1" in
    x86_64-pc-windows-*)   echo "windows-x64" ;;
    aarch64-pc-windows-*)  echo "windows-arm64" ;;
    x86_64-apple-darwin)   echo "macos-x64" ;;
    aarch64-apple-darwin)  echo "macos-arm64" ;;
    x86_64-unknown-linux-*)  echo "linux-x64" ;;
    aarch64-unknown-linux-*) echo "linux-arm64" ;;
    *) echo "" ;;
  esac
}
packaged=0
for bindir in "$HERE/sidecar/target/"*/release; do
  [ -d "$bindir" ] || continue
  triple="$(basename "$(dirname "$bindir")")"
  osdir="$(osdir_for "$triple")"
  [ -n "$osdir" ] || continue
  case "$osdir" in
    windows-*) name="fennec-acp.exe" ;;
    *) name="fennec-acp" ;;
  esac
  bin="$bindir/$name"
  if [ -f "$bin" ]; then
    mkdir -p "$CLASSES/sidecar/$osdir"
    cp "$bin" "$CLASSES/sidecar/$osdir/$name"
    echo "packaged sidecar: $osdir/$name ($(wc -c < "$bin") bytes)"
    packaged=$((packaged + 1))
  fi
done
if [ "$packaged" -eq 0 ]; then
  echo "No sidecar binary was packaged; build one first." >&2
  exit 1
fi

# --- jar + zip ---
"$JAR" --create --file "$DIST/lib/fennec-desktop.jar" -C "$CLASSES" .
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HERE/src/main/resources/META-INF/plugin.xml" | head -n1)"
ZIP="$OUT/Fennec-plugin-$VERSION.zip"
( cd "$OUT" && "$JAR" --create --file "$ZIP" -C "$OUT" fennec-desktop )

echo "jar: $DIST/lib/fennec-desktop.jar ($(wc -c < "$DIST/lib/fennec-desktop.jar") bytes)"
echo "zip: $ZIP ($(wc -c < "$ZIP") bytes)"
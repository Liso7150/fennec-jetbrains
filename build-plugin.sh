#!/usr/bin/env bash
# Builds the Fennec JetBrains plugin (Kotlin + a bundled Rust sidecar) without Gradle.
#
# Compiles Kotlin against an installed IntelliJ Platform (263.x) with the local
# kotlinc, builds the Rust sidecar offline, and packages a standard plugin zip that
# "Install Plugin from Disk" accepts. The sidecar binary is bundled under
# sidecar/<os-dir>/ so the plugin can install it per OS at runtime.
#
# Overrides (mirroring FENNEC_IDE_PLATFORM):
#   FENNEC_KOTLINC      path to kotlinc(.bat) (default: kotlinc on PATH)
#   FENNEC_IDE_PLATFORM  IntelliJ-based IDE install dir (provides lib jars + JBR)
#   FENNEC_SIDECAR_TARGET  Rust target triple to build (default: this machine's)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${FENNEC_IDE_PLATFORM:-${RUSTROVER_HOME:-${IDEA_HOME:-}}}"
if [ -z "$PLATFORM" ]; then
  for candidate in \
    "/c/Program Files/JetBrains/RustRover 2026.3" \
    "$HOME/.local/share/JetBrains/RustRover2026.3" \
    "/Applications/RustRover.app/Contents"; do
    if [ -d "$candidate/lib" ]; then
      PLATFORM="$candidate"
      break
    fi
  done
fi
SIDECAR_TARGET="${FENNEC_SIDECAR_TARGET:-$(rustc -vV 2>/dev/null | sed -n 's/^host: //p')}"
OUT="$HERE/build"
CLASSES="$OUT/classes"
DIST="$OUT/fennec-jetbrains"

if [ -n "${FENNEC_KOTLINC:-}" ]; then
  KOTLINC="$FENNEC_KOTLINC"
else
  KOTLINC="$(command -v kotlinc || command -v kotlinc.bat || true)"
fi
if [ -z "$KOTLINC" ] || [ ! -f "$KOTLINC" ]; then
  echo "kotlinc not found. Add it to PATH or set FENNEC_KOTLINC to the compiler path." >&2
  exit 1
fi

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
if [[ ! "$SIDECAR_TARGET" =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]]; then
  echo "FENNEC_SIDECAR_TARGET must be one Rust target triple, not a path: $SIDECAR_TARGET" >&2
  exit 1
fi
OSDIR="$(osdir_for "$SIDECAR_TARGET")"
if [ -z "$OSDIR" ]; then
  echo "Unsupported FENNEC_SIDECAR_TARGET: $SIDECAR_TARGET" >&2
  exit 1
fi

if [ ! -d "$PLATFORM/lib" ]; then
  echo "IntelliJ Platform not found at: $PLATFORM" >&2
  echo "Set FENNEC_IDE_PLATFORM to a JetBrains IDE installation directory." >&2
  exit 1
fi

echo "platform:       $PLATFORM"
echo "kotlinc:        $KOTLINC"
echo "sidecar target: $SIDECAR_TARGET"

# --- Rust sidecar (offline, dependency-light) ---
echo "=== building Rust sidecar ==="
( cd "$HERE/sidecar" && cargo build --release --offline -j 6 --target-dir "$HERE/sidecar/target" --target "$SIDECAR_TARGET" )

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

# --- bundle only the configured sidecar target under its OS dir ---
TARGET_ROOT="$(cd "$HERE/sidecar/target" && pwd -P)"
BINDIR="$TARGET_ROOT/$SIDECAR_TARGET/release"
if [ ! -d "$BINDIR" ]; then
  echo "No sidecar build found for configured target: $SIDECAR_TARGET" >&2
  exit 1
fi
RESOLVED_BINDIR="$(cd "$BINDIR" && pwd -P)"
if [ "$RESOLVED_BINDIR" != "$BINDIR" ]; then
  echo "Refusing to package a sidecar path outside $TARGET_ROOT/$SIDECAR_TARGET/release." >&2
  exit 1
fi
case "$OSDIR" in
  windows-*) name="fennec-acp.exe" ;;
  *) name="fennec-acp" ;;
esac
bin="$RESOLVED_BINDIR/$name"
if [ ! -f "$bin" ]; then
  echo "Required sidecar binary not found for $SIDECAR_TARGET: $bin" >&2
  exit 1
fi
mkdir -p "$CLASSES/sidecar/$OSDIR"
cp "$bin" "$CLASSES/sidecar/$OSDIR/$name"
echo "packaged sidecar: $OSDIR/$name ($(wc -c < "$bin") bytes)"

# --- jar + zip ---
"$JAR" --create --file "$DIST/lib/fennec-jetbrains.jar" -C "$CLASSES" .
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HERE/src/main/resources/META-INF/plugin.xml" | head -n1)"
ZIP="$OUT/Fennec-plugin-$VERSION.zip"
( cd "$OUT" && "$JAR" --create --no-manifest --file "$ZIP" -C "$OUT" fennec-jetbrains )

echo "jar: $DIST/lib/fennec-jetbrains.jar ($(wc -c < "$DIST/lib/fennec-jetbrains.jar") bytes)"
echo "zip: $ZIP ($(wc -c < "$ZIP") bytes)"
#!/usr/bin/env bash
# Builds the Colibri Code IDE plugin without Gradle.
#
# Compiles against an installed IntelliJ Platform (263.x) so the artefact always
# matches a real IDE on this machine, then packages a standard plugin zip that
# Install Plugin from Disk accepts.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${COLIBRI_IDE_PLATFORM:-C:/Program Files/JetBrains/RustRover 2026.3}"
OUT="$HERE/build"
CLASSES="$OUT/classes"
DIST="$OUT/colibri-code"

if [ ! -d "$PLATFORM/lib" ]; then
  echo "IntelliJ Platform not found at: $PLATFORM" >&2
  echo "Set COLIBRI_IDE_PLATFORM to a JetBrains IDE installation directory." >&2
  exit 1
fi

echo "platform: $PLATFORM"
rm -rf "$OUT"
mkdir -p "$CLASSES" "$DIST/lib"

# The platform is built for Java 25, so its class files refuse an older javac.
# Prefer the JetBrains Runtime shipped with the IDE; fall back to a JDK 25 on PATH.
JAVAC="$PLATFORM/jbr/bin/javac.exe"
JAR="$PLATFORM/jbr/bin/jar.exe"
[ -x "$JAVAC" ] || JAVAC="$(command -v javac)"
[ -x "$JAR" ] || JAR="$(command -v jar)"
echo "javac: $JAVAC"

WIN_CLASSES="$(cygpath -w "$CLASSES")"
cd "$HERE/src/main/java"
find . -name '*.java' > "$OUT/sources.txt"
echo "compiling $(wc -l < "$OUT/sources.txt") files"
"$JAVAC" -encoding UTF-8 -nowarn -Xlint:none \
  -cp "$PLATFORM/lib/*" \
  -d "$WIN_CLASSES" \
  @"$(cygpath -w "$OUT/sources.txt")"
cd "$HERE"

cp -r "$HERE/src/main/resources/." "$CLASSES/"

# The Air bridge is a resource the plugin installs into ~/.colibri/air.
mkdir -p "$CLASSES/colibri/air"
cp "$HERE/air/colibri-acp.py" "$CLASSES/colibri/air/colibri-acp.py"

"$JAR" --create --file "$DIST/lib/colibri-code.jar" -C "$CLASSES" .

mkdir -p "$DIST/META-INF"
cat > "$OUT/plugin-zip-note.txt" <<'NOTE'
Colibri Code plugin for IntelliJ-based IDEs (build 263.x).
Install with: Settings -> Plugins -> gear -> Install Plugin from Disk.
NOTE
cp "$OUT/plugin-zip-note.txt" "$DIST/README.txt"

VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HERE/src/main/resources/META-INF/plugin.xml" | head -n1)"
ZIP="$OUT/ColibriCode-plugin-$VERSION.zip"
( cd "$OUT" && "$JAR" --create --file "$ZIP" -C "$OUT" colibri-code )

echo "jar:  $DIST/lib/colibri-code.jar"
echo "zip:  $ZIP"
ls -la "$ZIP"

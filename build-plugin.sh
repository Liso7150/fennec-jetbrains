#!/usr/bin/env bash
# Builds the Fennec Desktop IDE plugin without Gradle.
#
# Compiles Kotlin sources against an installed IntelliJ Platform (263.x) and
# the Kotlin compiler shipped with IntelliJ IDEA, then packages a standard
# plugin zip that Install Plugin from Disk accepts.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${FENNEC_IDE_PLATFORM:-C:/Program Files/JetBrains/RustRover 2026.3}"
IDEA_HOME="${FENNEC_IDEA_HOME:-C:/Program Files/JetBrains/IntelliJ IDEA 2026.3}"
OUT="$HERE/build"
CLASSES="$OUT/classes"
DIST="$OUT/fennec-desktop"

if [ ! -d "$PLATFORM/lib" ]; then
  echo "IntelliJ Platform not found at: $PLATFORM" >&2
  echo "Set FENNEC_IDE_PLATFORM to a JetBrains IDE installation directory." >&2
  exit 1
fi

JBR_JAVA="$PLATFORM/jbr/bin/java.exe"
if [ ! -x "$JBR_JAVA" ]; then
  echo "JBR java not found at: $JBR_JAVA" >&2
  exit 1
fi

KOTLIN_COMPILER="$IDEA_HOME/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar"
KOTLIN_STDLIB="$IDEA_HOME/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar"
if [ ! -f "$KOTLIN_COMPILER" ]; then
  echo "Kotlin compiler not found at: $KOTLIN_COMPILER" >&2
  echo "Set FENNEC_IDEA_HOME to an IntelliJ IDEA installation with the Kotlin plugin." >&2
  exit 1
fi

JAR="$PLATFORM/jbr/bin/jar.exe"
[ -x "$JAR" ] || JAR="$(command -v jar)"

echo "platform: $PLATFORM"
echo "kotlinc: $KOTLIN_COMPILER (via JBR)"
rm -rf "$OUT"
mkdir -p "$CLASSES" "$DIST/lib"

WIN_CLASSES="$(cygpath -w "$CLASSES" | tr '\\' '/')"
WIN_KOTLIN_COMPILER="$(cygpath -w "$KOTLIN_COMPILER" | tr '\\' '/')"
WIN_KOTLIN_STDLIB="$(cygpath -w "$KOTLIN_STDLIB" | tr '\\' '/')"

WIN_PLATFORM_CP=""
for jar in "$PLATFORM"/lib/*.jar; do
  win_jar="$(cygpath -w "$jar" | tr '\\' '/')"
  if [ -z "$WIN_PLATFORM_CP" ]; then
    WIN_PLATFORM_CP="$win_jar"
  else
    WIN_PLATFORM_CP="$WIN_PLATFORM_CP;$win_jar"
  fi
done

SRC_COUNT=$(find "$HERE/src/main/kotlin" -name '*.kt' | wc -l)
echo "compiling $SRC_COUNT Kotlin files"

KOTLINC_ARGSFILE="$OUT/kotlinc-args.txt"
{
  echo "-nowarn"
  echo "-no-reflect"
  echo "-cp"
  echo "\"$WIN_PLATFORM_CP;$WIN_KOTLIN_STDLIB\""
  echo "-d"
  echo "\"$WIN_CLASSES\""
  cd "$HERE/src/main/kotlin"
  find . -name '*.kt' | while read -r f; do
    echo "\"$(cygpath -w "$(pwd)/$f" | tr '\\' '/')\""
  done
} > "$KOTLINC_ARGSFILE"
cd "$HERE"

WIN_KOTLINC_ARGSFILE="$(cygpath -w "$KOTLINC_ARGSFILE" | tr '\\' '/')"
export CLASSPATH="$WIN_KOTLIN_COMPILER;$WIN_KOTLIN_STDLIB"
"$JBR_JAVA" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$WIN_KOTLINC_ARGSFILE"

cp -r "$HERE/src/main/resources/." "$CLASSES/"

mkdir -p "$CLASSES/fennec/air"
cp "$HERE/air/fennec-acp.py" "$CLASSES/fennec/air/fennec-acp.py"

"$JAR" --create --file "$DIST/lib/fennec-desktop.jar" -C "$CLASSES" .

mkdir -p "$DIST/META-INF"
cat > "$OUT/plugin-zip-note.txt" <<'NOTE'
Fennec Desktop plugin for IntelliJ-based IDEs (build 263.x).
Install with: Settings -> Plugins -> gear -> Install Plugin from Disk.
NOTE
cp "$OUT/plugin-zip-note.txt" "$DIST/README.txt"

VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$HERE/src/main/resources/META-INF/plugin.xml" | head -n1)"
ZIP="$OUT/Fennec-plugin-$VERSION.zip"
( cd "$OUT" && "$JAR" --create --file "$ZIP" -C "$OUT" fennec-desktop )

echo "jar:  $DIST/lib/fennec-desktop.jar"
echo "zip:  $ZIP"
ls -la "$ZIP"

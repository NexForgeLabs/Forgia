#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME=${FORGIA_COMPAT_JAVA_HOME:-/home/yc/.jdks/jdk-21.0.12.1+1}
INSTALL=${1:-/home/yc/Minecraft/SkyExplorer-forgia}
GUI_JAR=${2:-"/home/yc/Minecraft/SkyExplorer-forgia/mods/[机械动力：红石信号终端GUI] createredstonelinkgui-1.21.1-1.9.2.jar"}
FORGIA_UNIVERSAL=${3:-"../../projects/neoforge/build/libs/neoforge-21.1.250.314-beta-universal.jar"}
OUT=${4:-build/libs/forgia-redstonelink-compat-1.0.0.jar}

pick() {
    local pattern="$1"
    local found
    found=$(ls $pattern 2>/dev/null | sort -V | tail -1 || true)
    printf '%s' "$found"
}

SERVER=$(pick "$INSTALL/libraries/net/minecraft/server/*/server-*-srg.jar")
MIXIN=$(pick "$INSTALL/libraries/net/fabricmc/sponge-mixin/*/sponge-mixin-*.jar")
BUS=$(pick "$INSTALL/libraries/net/neoforged/bus/*/bus-*.jar")
FML=$(pick "$INSTALL/libraries/net/neoforged/fancymodloader/loader/*/loader-*.jar")
SLF4J=$(pick "$INSTALL/libraries/org/slf4j/slf4j-api/*/slf4j-api-*.jar")

for jar in "$FORGIA_UNIVERSAL" "$SERVER" "$MIXIN" "$BUS" "$FML" "$GUI_JAR"; do
    [ -f "$jar" ] || { echo "missing compile dependency: '$jar'" >&2; exit 1; }
done

rm -rf build/classes
mkdir -p build/classes
"$JAVA_HOME/bin/javac" -proc:none -encoding UTF-8 --release 21 \
    -cp "$FORGIA_UNIVERSAL:$SERVER:$MIXIN:$BUS:$FML:$SLF4J:$GUI_JAR" \
    -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
mkdir -p "$(dirname "$OUT")"
"$JAVA_HOME/bin/jar" --create --file "$OUT" -C build/classes .
echo "built $OUT"
"$JAVA_HOME/bin/jar" --list --file "$OUT"

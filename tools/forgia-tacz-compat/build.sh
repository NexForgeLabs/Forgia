#!/bin/bash
# Builds forgia-tacz-compat against an installed Forgia/NeoForge server and TaCZ jar.
#   usage: ./build.sh [install-dir] [tacz-jar] [out-jar]
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME=${FORGIA_COMPAT_JAVA_HOME:-/home/yc/.jdks/jdk-21.0.12.1+1}
INSTALL=${1:-/home/yc/Minecraft/forgia-vanilla}
TACZ=${2:-"/home/yc/Minecraft/SkyExplorer-forgia/mods/tacz-neoforge-1.21.1-1.1.8-hotfix-r6.jar"}
OUT=${3:-build/libs/forgia-tacz-compat-1.0.0.jar}

pick() {
    local pattern="$1"
    local found
    found=$(ls $pattern 2>/dev/null | sort -V | tail -1 || true)
    printf '%s' "$found"
}

UNIVERSAL=$(pick "../../projects/neoforge/build/libs/neoforge-*-universal.jar")
if [ -z "$UNIVERSAL" ]; then
    UNIVERSAL=$(pick "$INSTALL/libraries/net/neoforged/neoforge/*/neoforge-*-universal.jar")
fi
SERVER=$(pick "$INSTALL/libraries/net/minecraft/server/*/server-*-srg.jar")
MIXIN=$(pick "$INSTALL/libraries/net/fabricmc/sponge-mixin/*/sponge-mixin-*.jar")
BUS=$(pick "$INSTALL/libraries/net/neoforged/bus/*/bus-*.jar")
FML=$(pick "$INSTALL/libraries/net/neoforged/fancymodloader/loader/*/loader-*.jar")
SLF4J=$(pick "$INSTALL/libraries/org/slf4j/slf4j-api/*/slf4j-api-*.jar")

for jar in "$UNIVERSAL" "$SERVER" "$MIXIN" "$BUS" "$FML" "$SLF4J" "$TACZ"; do
    [ -f "$jar" ] || { echo "missing compile dependency: '$jar'" >&2; exit 1; }
done

rm -rf build/classes
mkdir -p build/classes
"$JAVA_HOME/bin/javac" -proc:none -encoding UTF-8 --release 21 \
    -cp "$UNIVERSAL:$SERVER:$MIXIN:$BUS:$FML:$SLF4J:$TACZ" \
    -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
mkdir -p "$(dirname "$OUT")"
"$JAVA_HOME/bin/jar" --create --file "$OUT" -C build/classes .
echo "built $OUT"
"$JAVA_HOME/bin/jar" --list --file "$OUT"

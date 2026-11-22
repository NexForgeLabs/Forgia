#!/bin/bash
# Builds forgia-cataclysm-compat against an installed Forgia/NeoForge server and Cataclysm jar.
#   usage: ./build.sh [install-dir] [cataclysm-jar] [out-jar]
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME=${FORGIA_COMPAT_JAVA_HOME:-/home/yc/.local/opt/jdk-21.0.13+9}
INSTALL=${1:-/home/yc/Minecraft/forgia-vanilla}
CATACLYSM=${2:-"/home/yc/Minecraft/SkyExplorer-forgia/mods/[灾变] L_Ender's Cataclysm 1.21.1-3.33.jar"}
OUT=${3:-build/libs/forgia-cataclysm-compat-1.0.0.jar}

pick() {
    local pattern="$1"
    local found
    found=$(ls $pattern 2>/dev/null | sort -V | tail -1 || true)
    printf '%s' "$found"
}

UNIVERSAL=$(pick "$INSTALL/libraries/net/neoforged/neoforge/*/neoforge-*-universal.jar")
SERVER=$(pick "$INSTALL/libraries/net/minecraft/server/*/server-*-srg.jar")
MIXIN=$(pick "$INSTALL/libraries/net/fabricmc/sponge-mixin/*/sponge-mixin-*.jar")
BUS=$(pick "$INSTALL/libraries/net/neoforged/bus/*/bus-*.jar")
FML=$(pick "$INSTALL/libraries/net/neoforged/fancymodloader/loader/*/loader-*.jar")

for jar in "$UNIVERSAL" "$SERVER" "$MIXIN" "$BUS" "$FML" "$CATACLYSM"; do
    [ -f "$jar" ] || { echo "missing compile dependency: '$jar'" >&2; exit 1; }
done

rm -rf build/classes
mkdir -p build/classes
"$JAVA_HOME/bin/javac" -proc:none -encoding UTF-8 --release 21 \
    -cp "$UNIVERSAL:$SERVER:$MIXIN:$BUS:$FML:$CATACLYSM" \
    -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
mkdir -p "$(dirname "$OUT")"
"$JAVA_HOME/bin/jar" --create --file "$OUT" -C build/classes .
echo "built $OUT"
"$JAVA_HOME/bin/jar" --list --file "$OUT"

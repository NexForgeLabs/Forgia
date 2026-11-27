#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME=${FORGIA_COMPAT_JAVA_HOME:-/home/yc/.local/opt/jdk-21.0.13+9}
INSTALL=${1:-/home/yc/Minecraft/forgia-vanilla}
OUT=${2:-build/libs/forgia-villager-ai-opt-1.0.0.jar}

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
BRIGADIER=$(pick "$INSTALL/libraries/com/mojang/brigadier/*/brigadier-*.jar")

BRAINLAYERS="/tmp/sky_mods/brainlayers-neoforge-1.21.1-1.4.16.jar"
TEALLIB="/tmp/sky_mods/teallib-1.3.teal.jar"
BYEPREGEN="/tmp/sky_mods/byepregen-1.21.1-1.1.2.1.jar"
HAMSTER="/tmp/sky_mods/adorablehamsterpets-3.7.0-1.21.1+neoforge.jar"
GECKOLIB="/tmp/sky_mods/geckolib-neoforge-1.21.1-4.9.3.jar"
FZZY="/tmp/sky_mods/fzzy_config-0.7.6+1.21+neoforge.jar"

for jar in "$UNIVERSAL" "$SERVER" "$MIXIN" "$BUS" "$FML" "$SLF4J" "$BRIGADIER" "$BRAINLAYERS" "$TEALLIB" "$BYEPREGEN" "$HAMSTER" "$GECKOLIB" "$FZZY"; do
    [ -f "$jar" ] || { echo "missing compile dependency: '$jar'" >&2; exit 1; }
done

rm -rf build/classes
mkdir -p build/classes
"$JAVA_HOME/bin/javac" -proc:none -encoding UTF-8 --release 21 \
    -cp "$UNIVERSAL:$SERVER:$MIXIN:$BUS:$FML:$SLF4J:$BRIGADIER:$BRAINLAYERS:$TEALLIB:$BYEPREGEN:$HAMSTER:$GECKOLIB:$FZZY" \
    -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
mkdir -p "$(dirname "$OUT")"
"$JAVA_HOME/bin/jar" --create --file "$OUT" -C build/classes .
echo "built $OUT"
"$JAVA_HOME/bin/jar" --list --file "$OUT"

#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME=${FORGIA_COMPAT_JAVA_HOME:-/home/yc/.local/opt/jdk-21.0.13+9}
INSTALL=${1:-/home/yc/Minecraft/forgia-vanilla}
LOOTR_JAR=${2:-"/home/yc/Minecraft/SkyExplorer-forgia/mods/lootr-neoforge-1.21.1-1.11.38.126.jar"}
OUT=${3:-build/libs/forgia-lootr-compat-1.0.0.jar}

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
MIXINEXTRAS=$(pick "/home/yc/.gradle/caches/modules-2/files-2.1/io.github.llamalad7/mixinextras-neoforge/*/*/mixinextras-neoforge-*.jar")
if [ -z "$MIXINEXTRAS" ]; then
    MIXINEXTRAS=$(pick "$INSTALL/libraries/io/github/llamalad7/mixinextras-neoforge/*/mixinextras-neoforge-*.jar")
fi
FML=$(pick "$INSTALL/libraries/net/neoforged/fancymodloader/loader/*/loader-*.jar")
SLF4J=$(pick "$INSTALL/libraries/org/slf4j/slf4j-api/*/slf4j-api-*.jar")

for jar in "$UNIVERSAL" "$SERVER" "$MIXIN" "$MIXINEXTRAS" "$FML" "$SLF4J" "$LOOTR_JAR"; do
    [ -f "$jar" ] || { echo "missing compile dependency: '$jar'" >&2; exit 1; }
done

rm -rf build/classes
mkdir -p build/classes
"$JAVA_HOME/bin/javac" -proc:none -encoding UTF-8 --release 21 \
    -cp "$UNIVERSAL:$SERVER:$MIXIN:$MIXINEXTRAS:$FML:$SLF4J:$LOOTR_JAR" \
    -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
mkdir -p "$(dirname "$OUT")"
"$JAVA_HOME/bin/jar" --create --file "$OUT" -C build/classes .
echo "built $OUT"
"$JAVA_HOME/bin/jar" --list --file "$OUT"

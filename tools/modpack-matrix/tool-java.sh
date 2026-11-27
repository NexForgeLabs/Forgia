#!/bin/bash
# Starts the server inside a run directory with the installed Forgia loader.
set -uo pipefail
RUN=$1
cd "$RUN"
# Use the newest Forgia build installed in the pack, so a rebuilt loader is picked up without editing this file.
ARGS=$(ls -d libraries/net/neoforged/neoforge/*-beta/unix_args.txt 2>/dev/null | sort -V | tail -1)
if [ -z "$ARGS" ]; then echo "no installed Forgia build in $PWD/libraries/net/neoforged/neoforge" >&2; exit 90; fi
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/java}
JAVA_BIN=${JAVA_BIN:-$(command -v java || true)}
if [ -z "$JAVA_BIN" ]; then
    echo "java not found; set JAVA_HOME or add java to PATH" >&2
    exit 1
fi
exec "$JAVA_BIN" -Xms1G -Xmx3G -Dfile.encoding=UTF-8 "@$ARGS" nogui

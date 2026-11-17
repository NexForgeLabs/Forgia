"""Read committed NDJSON events while an independent producer is still writing.

A newline commits a record. Final verifiers still parse the complete closed file
strictly; malformed committed records are never ignored here.
"""
import json


def read_complete_events(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines(keepends=True) if line.endswith('\n')]

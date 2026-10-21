"""Public TF-C denial record, NOT an approval verifier or cloud executor.

Exit 1: valid but BLOCKED. Exit 2: invalid/unsafe input. No success/READY path.
Real approvals belong in a separately reviewed private process, never this file.
"""
import argparse
import json
from pathlib import Path

import quality_gates as gates

GATES = {f"B{i:02d}" for i in range(1, 15)}
TESTS = {f"T{i:02d}" for i in range(1, 18)}


def strict_json(text):
    def invalid_constant(_):
        raise ValueError("Non-JSON constant")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise gates.Violation("Duplicate JSON field rejected")
            result[key] = value
        return result
    try:
        return json.loads(text, object_pairs_hook=unique, parse_constant=invalid_constant)
    except (ValueError, TypeError, RecursionError) as error:
        raise gates.Violation("Invalid public JSON document") from error


def blocked_record(document):
    if not isinstance(document, dict) or set(document) != {"schema_version", "mode", "gates", "tests", "unresolved"}:
        raise gates.Violation("Unexpected public preflight fields")
    if type(document["schema_version"]) is not int or document["schema_version"] != 1 or document["mode"] != "offline-only":
        raise gates.Violation("Only the offline public schema is accepted")
    for field, identifiers, status in (("gates", GATES, "BLOCKED"), ("tests", TESTS, "NOT_RUN"),
                                        ("unresolved", {"C01", "C02", "C05"}, "BLOCKED")):
        entries = document[field]
        if not isinstance(entries, dict) or set(entries) != identifiers or any(value != status for value in entries.values()):
            raise gates.Violation("Missing, forged or prematurely successful preflight entry")
    return {"decision": "BLOCKED", "approved": 0, "blocked": 14, "executed": 0, "not_run": 17,
            "unresolved": 3, "cloud_execution_available": False}


def main():
    class PublicParser(argparse.ArgumentParser):
        def error(self, message):
            # argparse's default echoes unknown arguments, which may be private.
            self.exit(2, "preflight: unsupported arguments; cloud execution denied\n")

    parser = PublicParser(description=__doc__)
    # Deliberately no private approval file, command, operation or override option.
    parser.parse_args()
    path = Path(__file__).resolve().parents[2] / "terraform-sandbox-tests/preflight.public.json"
    try:
        if path.is_symlink() or path.stat().st_size > 8192:
            raise gates.Violation("Unsafe public denial record")
        result = blocked_record(strict_json(path.read_text(encoding="utf-8")))
        print(json.dumps(result, sort_keys=True))
        return 1
    except (gates.Violation, OSError, UnicodeError):
        print("preflight: INVALID; cloud execution denied (input values omitted)")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

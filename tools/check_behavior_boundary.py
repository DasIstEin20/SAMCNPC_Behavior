#!/usr/bin/env python3
"""Standalone version of the workspace's source-level Behavior dependency guard.

This complements compilation and integration tests; it is not a runtime sandbox.
"""

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "src"


def main() -> int:
    if not (SOURCES / "main" / "kotlin").is_dir():
        print("Behavior Kotlin source directory is missing.")
        return 1

    violations = []
    for path in sorted((SOURCES / "main").rglob("*.java")):
        violations.append(f"{path.relative_to(ROOT)}: production source must be Kotlin")

    for path in sorted(SOURCES.rglob("*.kt")):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            declaration = line.strip()
            if not declaration.startswith(("import ", "package ")):
                continue
            location = f"{path.relative_to(ROOT)}:{line_number}"
            if "samcnpc.llm" in declaration or "samcnpc_llm" in declaration:
                violations.append(f"{location}: Behavior must not depend on LLM: {declaration}")
            if declaration.startswith("import io.samcnpc.core.") and not declaration.startswith(
                "import io.samcnpc.core.api."
            ):
                violations.append(f"{location}: use the public Core API only: {declaration}")

    if violations:
        print("Behavior boundary violations:")
        print("\n".join(f" - {violation}" for violation in violations))
        return 1

    print("Behavior boundary check passed: Kotlin production sources, Core API only, no LLM imports.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

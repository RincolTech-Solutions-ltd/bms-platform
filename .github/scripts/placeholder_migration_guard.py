#!/usr/bin/env python3
"""
placeholder_migration_guard: a migration whose version is the placeholder 99 must never reach main.

Flyway runs with outOfOrder off, so a version 99 that was applied on a server makes every migration from V61 to V98 a number below
an applied one, and none of them can ever run. A branch that cannot know the next free number (it is taken at merge, above the
highest one on any open branch) uses V99 as a placeholder and is renumbered by the maintainer before it merges. A renumbered file
is no longer version 99, so there is no escape word and no override: any such file fails, on a pull request, on a push to main and
in the first job of the deploy workflow (which refuses to build or to move the staging pointer).

Version 99 is every spelling Flyway reads as that version: V99__x.sql, V099__x.sql, V0099__x.sql and V99.0__x.sql (the same
pattern is written in the deploy workflow's shell step, and a test keeps the two equal).

MIGRATIONS may point at another directory, for the tests of this script (test_placeholder_migration_guard.py).
"""
import os
import pathlib
import re
import sys

PATTERN = r"^V0*99(\.0+)?__"
DEFAULT_DIR = "backend/src/main/resources/db/migration"


def main() -> int:
    directory = pathlib.Path(os.environ.get("MIGRATIONS", DEFAULT_DIR))
    found = sorted(p.name for p in directory.iterdir() if re.match(PATTERN, p.name))
    if not found:
        print("No placeholder (version 99) migration. Guard passes.")
        return 0
    for name in found:
        print(f"::error file={directory}/{name}::placeholder migration version 99. Renumber it to the next free number "
              "before it merges (Flyway runs with outOfOrder off: a version 99 on main blocks every later migration).")
    return 1


if __name__ == "__main__":
    sys.exit(main())

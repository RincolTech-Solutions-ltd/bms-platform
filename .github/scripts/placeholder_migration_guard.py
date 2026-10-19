#!/usr/bin/env python3
"""
placeholder_migration_guard: a migration whose file name starts with the placeholder number V99 must never reach main.

Flyway runs with outOfOrder off, so a V99 that was applied on a server makes every migration from V61 to V98 a number below
an applied one, and none of them can ever run. A branch that cannot know the next free number (it is taken at merge, above the
highest one on any open branch) uses V99 as a placeholder and is renumbered by the maintainer before it merges. A renumbered file
is no longer V99, so there is no escape word and no override: any V99__*.sql file fails, on a pull request, on a push to main and
in the first job of the deploy workflow (which refuses to build or to move the staging pointer).

MIGRATIONS may point at another directory, for the tests of this script (test_placeholder_migration_guard.py).
"""
import os
import pathlib
import sys

PLACEHOLDER = "V99__"
DEFAULT_DIR = "backend/src/main/resources/db/migration"


def main() -> int:
    directory = pathlib.Path(os.environ.get("MIGRATIONS", DEFAULT_DIR))
    found = sorted(p.name for p in directory.glob(PLACEHOLDER + "*.sql"))
    if not found:
        print("No placeholder (V99) migration. Guard passes.")
        return 0
    for name in found:
        print(f"::error file={directory}/{name}::placeholder migration number V99. Renumber it to the next free number "
              "before it merges (Flyway runs with outOfOrder off: a V99 on main blocks every later migration).")
    return 1


if __name__ == "__main__":
    sys.exit(main())

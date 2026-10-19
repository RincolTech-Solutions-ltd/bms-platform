#!/usr/bin/env python3
"""
placeholder_migration_guard: a migration whose file name starts with the placeholder number V99 must never reach main.

Flyway runs with outOfOrder off, so a V99 that was applied on a server makes every migration from V61 to V98 a number below
an applied one, and none of them can ever run. A branch that cannot know the next free number (it is taken at merge, above the
highest one on any open branch) uses V99 as a placeholder and is renumbered by the maintainer before it merges.

The guard fails when a file backend/src/main/resources/db/migration/V99__*.sql exists, unless the run is for a pull request whose
description contains the word RENUMBERED (the maintainer's explicit statement that this number is deliberate). On a push to main
it always fails: nothing may land with the placeholder, whatever the description said.

Environment: PR_BODY (the pull request description, passed through the environment, never interpolated into the shell) and
GITHUB_EVENT_NAME. MIGRATIONS may point at another directory, for the tests of this script.
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
    event = os.environ.get("GITHUB_EVENT_NAME", "")
    if event == "pull_request" and "RENUMBERED" in os.environ.get("PR_BODY", ""):
        print("Placeholder migration present but the description says RENUMBERED: " + ", ".join(found))
        return 0
    for name in found:
        print(f"::error file={directory}/{name}::placeholder migration number V99. Renumber it to the next free number "
              "before it merges (Flyway runs with outOfOrder off: a V99 on main blocks every later migration).")
    return 1


if __name__ == "__main__":
    sys.exit(main())

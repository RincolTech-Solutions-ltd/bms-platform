#!/usr/bin/env python3
"""Tests of placeholder_migration_guard.py: it has no escape, whatever the event or the pull request text says."""
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("placeholder_migration_guard.py")


def run(directory: pathlib.Path, **env: str) -> subprocess.CompletedProcess:
    # Only what the test sets: nothing of the caller's environment (a PR_BODY, an event name) can change the answer.
    clean = {"PATH": os.environ.get("PATH", ""), "MIGRATIONS": str(directory), **env}
    return subprocess.run([sys.executable, str(SCRIPT)], env=clean, capture_output=True, text=True, check=False)


class PlaceholderMigrationGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self._tmp.name)
        for name in ("V1__foundation.sql", "V60__savings_deposit_by_gateway.sql", "V64__next_free_number.sql"):
            (self.dir / name).write_text("-- fabricated\n")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_no_placeholder_passes(self) -> None:
        result = run(self.dir, GITHUB_EVENT_NAME="pull_request")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_a_placeholder_on_a_pull_request_fails(self) -> None:
        (self.dir / "V99__staff_password_reset.sql").write_text("-- placeholder\n")
        result = run(self.dir, GITHUB_EVENT_NAME="pull_request", PR_BODY="A normal description.")
        self.assertEqual(result.returncode, 1)
        self.assertIn("V99__staff_password_reset.sql", result.stdout)

    def test_a_placeholder_on_a_push_to_main_fails(self) -> None:
        (self.dir / "V99__staff_password_reset.sql").write_text("-- placeholder\n")
        result = run(self.dir, GITHUB_EVENT_NAME="push")
        self.assertEqual(result.returncode, 1)

    def test_the_old_escape_word_changes_nothing(self) -> None:
        (self.dir / "V99__staff_password_reset.sql").write_text("-- placeholder\n")
        for body in ("RENUMBERED", "not yet RENUMBERED", "renumbered", "This is RENUMBERED."):
            for event in ("pull_request", "push", ""):
                result = run(self.dir, GITHUB_EVENT_NAME=event, PR_BODY=body)
                self.assertEqual(result.returncode, 1, (body, event))

    def test_only_the_placeholder_number_is_refused(self) -> None:
        (self.dir / "V990__not_the_placeholder.sql").write_text("-- a number far above, not V99\n")
        (self.dir / "V9__lending.sql").write_text("-- a real V9\n")
        self.assertEqual(run(self.dir).returncode, 0)


if __name__ == "__main__":
    unittest.main()

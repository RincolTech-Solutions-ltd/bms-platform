#!/usr/bin/env python3
"""Signs the images a deploy run built, with the cosign key that lives only in the maintainer's Keychain.

ADR-040. This runs on the maintainer's Mac under launchd. It is NOT a GitHub runner and no runner ever
holds the key. Every pass it asks GitHub for the latest deploy runs and signs the three image digests of a
run only when ALL of these hold:

  * the run is this repository's `.github/workflows/deploy.yml`, event `push`, branch `main`;
  * its head commit is on main (an ancestor of, or equal to, main);
  * the three `Build and push <image>` jobs finished with success, ran on the dedicated runner
    (`bms-deploy-pi`) and at that commit;
  * each build job recorded a `bms-image-digest` annotation (image, digest, revision) and the revision is the
    run's head commit. A job's annotations cannot be written from another run, so this is the one place the
    digest comes from; the tag in the registry is never trusted;
  * the image behind that digest carries the label `org.opencontainers.image.revision` equal to the commit.

It signs `image@digest`, never a tag. The Pi's puller verifies the signature against deploy/cosign.pub
before it trusts anything (deploy/pull-staging.sh), and the pointer job waits for the signature too.

Secrets are read at signing time and passed to cosign through the environment only: the key and password
come from the Keychain (items bms-cosign-key, base64 of the PEM, and bms-cosign-password); the registry
login is the `gh` token, written to a private temporary Docker config that is removed afterwards. Nothing
secret is printed or logged.

Usage:
  bms_signer.py --once            one pass, then exit
  bms_signer.py --loop 30         pass every 30 seconds (launchd KeepAlive)
  bms_signer.py --once --dry-run  say what would be signed, sign nothing
"""
from __future__ import annotations

import argparse
import base64
import calendar
import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = "RincolTech-Solutions-ltd/bms-platform"
WORKFLOW_PATH = ".github/workflows/deploy.yml"
RUNNER_NAME = "bms-deploy-pi"
IMAGE_PREFIX = "ghcr.io/rincoltech-solutions-ltd/bms-platform"
IMAGES = ("api", "web", "proxy")
ANNOTATION_TITLE = "bms-image-digest"
MAX_RUN_AGE_SECONDS = 48 * 3600
STATE_DIR = Path(os.environ.get("BMS_SIGNER_STATE", str(Path.home() / "bms-ops" / "signer")))
KEY_ITEM = "bms-cosign-key"
PASSWORD_ITEM = "bms-cosign-password"
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def log(message: str) -> None:
    print(time.strftime("%Y-%m-%dT%H:%M:%S%z"), message, flush=True)


def run(cmd: list[str], env: dict[str, str] | None = None, timeout: int = 120) -> tuple[int, str, str]:
    merged = dict(os.environ)
    if env:
        merged.update(env)
    try:
        proc = subprocess.run(cmd, env=merged, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 124, "", "timeout"
    return proc.returncode, proc.stdout, proc.stderr


def gh_api(path: str) -> object | None:
    code, out, err = run(["gh", "api", path])
    if code != 0:
        log(f"gh api {path.split('?')[0]} failed: {err.strip()[:160]}")
        return None
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return None


def keychain(item: str) -> str | None:
    code, out, _ = run(["security", "find-generic-password", "-s", item, "-w"])
    return out.strip() if code == 0 and out.strip() else None


def load_key() -> tuple[str, str] | None:
    raw, password = keychain(KEY_ITEM), keychain(PASSWORD_ITEM)
    if not raw or not password:
        log("Keychain items for the cosign key are missing or locked")
        return None
    try:
        pem = base64.b64decode(raw).decode()
    except Exception:
        log("the Keychain key is not base64 of a PEM")
        return None
    if "BEGIN ENCRYPTED SIGSTORE PRIVATE KEY" not in pem:
        log("the Keychain key is not a cosign private key")
        return None
    return pem, password


def is_on_main(sha: str) -> bool:
    data = gh_api(f"repos/{REPO}/compare/{sha}...main")
    return isinstance(data, dict) and data.get("status") in ("ahead", "identical")


def parse_annotation(message: str) -> dict[str, str]:
    return dict(pair.split("=", 1) for pair in message.split() if "=" in pair)


def build_digests(run_info: dict) -> dict[str, str] | None:
    """The three digests the build jobs of this run recorded, or None while anything is missing or wrong."""
    sha = run_info["head_sha"]
    jobs = gh_api(f"repos/{REPO}/actions/runs/{run_info['id']}/jobs?per_page=100&filter=latest")
    if not isinstance(jobs, dict):
        return None
    by_name = {job["name"]: job for job in jobs.get("jobs", [])}
    digests: dict[str, str] = {}
    for image in IMAGES:
        job = by_name.get(f"Build and push {image}")
        if not job or job.get("status") != "completed" or job.get("conclusion") != "success":
            return None
        if job.get("runner_name") != RUNNER_NAME or job.get("head_sha") != sha:
            log(f"run {run_info['id']}: {image} build ran on {job.get('runner_name')!r} at {job.get('head_sha')}: refused")
            return None
        notes = gh_api(f"repos/{REPO}/check-runs/{job['id']}/annotations")
        found = [n for n in (notes if isinstance(notes, list) else []) if n.get("title") == ANNOTATION_TITLE]
        if len(found) != 1:
            return None
        fields = parse_annotation(found[0].get("message", ""))
        if fields.get("image") != image or fields.get("revision") != sha or not DIGEST_RE.match(fields.get("digest", "")):
            log(f"run {run_info['id']}: the digest note of {image} does not match the run: refused")
            return None
        digests[image] = fields["digest"]
    return digests


def label_revision(ref: str, docker_config: str) -> str | None:
    code, out, _ = run(["crane", "config", "--platform", "linux/arm64", ref], env={"DOCKER_CONFIG": docker_config})
    if code != 0:
        return None
    try:
        return (json.loads(out).get("config") or {}).get("Labels", {}).get("org.opencontainers.image.revision")
    except json.JSONDecodeError:
        return None


def signed_already(ref: str, pub_file: str, docker_config: str) -> bool:
    code, _, _ = run(
        ["cosign", "verify", "--key", pub_file, "--insecure-ignore-tlog=true", ref],
        env={"DOCKER_CONFIG": docker_config},
    )
    return code == 0


def process(run_info: dict, key: tuple[str, str], state: dict, dry: bool) -> None:
    run_id, sha = run_info["id"], run_info["head_sha"]
    if str(run_id) in state["done"]:
        return
    if run_info.get("path") != WORKFLOW_PATH or run_info.get("event") != "push" or run_info.get("head_branch") != "main":
        return
    if (run_info.get("repository") or {}).get("full_name") != REPO or not SHA_RE.match(sha):
        return
    digests = build_digests(run_info)
    if digests is None:
        return
    if not is_on_main(sha):
        log(f"run {run_id}: commit {sha[:7]} is not on main: refused")
        return
    pem, password = key
    user_code, user_out, _ = run(["gh", "api", "user", "--jq", ".login"])
    tok_code, token, _ = run(["gh", "auth", "token"])
    if user_code != 0 or tok_code != 0:
        log("no gh login for the registry")
        return
    workdir = tempfile.mkdtemp(prefix="bms-signer-")
    os.chmod(workdir, 0o700)
    try:
        docker_config = os.path.join(workdir, "docker")
        os.makedirs(docker_config, mode=0o700)
        auth = base64.b64encode(f"{user_out.strip()}:{token.strip()}".encode()).decode()
        Path(docker_config, "config.json").write_text(json.dumps({"auths": {"ghcr.io": {"auth": auth}}}))
        os.chmod(Path(docker_config, "config.json"), 0o600)
        env = {"COSIGN_KEY": pem, "COSIGN_PASSWORD": password, "DOCKER_CONFIG": docker_config}
        code, pub, _ = run(["cosign", "public-key", "--key", "env://COSIGN_KEY"], env=env)
        if code != 0:
            log("cosign could not read the key")
            return
        pub_file = os.path.join(workdir, "cosign.pub")
        Path(pub_file).write_text(pub)
        refs = {image: f"{IMAGE_PREFIX}-{image}@{digest}" for image, digest in digests.items()}
        for image, ref in refs.items():
            if label_revision(ref, docker_config) != sha:
                log(f"run {run_id}: {image} image labels do not name {sha[:7]}: refused")
                return
        for image, ref in refs.items():
            if signed_already(ref, pub_file, docker_config):
                continue
            if dry:
                log(f"DRY RUN: would sign {ref}")
                continue
            code, _, err = run(["cosign", "sign", "--key", "env://COSIGN_KEY", "--tlog-upload=false", "--yes", ref], env=env, timeout=300)
            if code != 0:
                log(f"run {run_id}: signing {image} failed: {err.strip()[-200:]}")
                return
            log(f"run {run_id}: signed {image} {digests[image][:19]} for {sha[:7]}")
        if not dry:
            state["done"][str(run_id)] = {"sha": sha, "at": int(time.time())}
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def one_pass(dry: bool) -> None:
    state_file = STATE_DIR / "state.json"
    state = {"done": {}}
    if state_file.exists():
        try:
            state = json.loads(state_file.read_text())
        except json.JSONDecodeError:
            pass
    listing = gh_api(f"repos/{REPO}/actions/workflows/deploy.yml/runs?event=push&branch=main&per_page=10")
    if not isinstance(listing, dict):
        return
    key = load_key()
    if key is None:
        return
    now = time.time()
    for run_info in listing.get("workflow_runs", []):
        if run_info.get("conclusion") in ("failure", "cancelled", "skipped"):
            continue
        created = run_info.get("created_at", "")
        try:
            age = now - calendar.timegm(time.strptime(created, "%Y-%m-%dT%H:%M:%SZ"))
        except ValueError:
            continue
        if age > MAX_RUN_AGE_SECONDS:
            continue
        process(run_info, key, state, dry)
    if not dry:
        state["done"] = dict(list(state["done"].items())[-50:])
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        tmp = state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(state))
        tmp.replace(state_file)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--loop", type=int, metavar="SECONDS")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not args.once and not args.loop:
        parser.error("give --once or --loop SECONDS")
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    lock = open(STATE_DIR / "signer.lock", "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        log("another signer is running")
        return 1
    while True:
        try:
            one_pass(args.dry_run)
        except Exception as exc:  # a bad pass must not stop the loop
            log(f"pass failed: {type(exc).__name__}: {str(exc)[:160]}")
        if args.once:
            return 0
        time.sleep(args.loop)


if __name__ == "__main__":
    sys.exit(main())

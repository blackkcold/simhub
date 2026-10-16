#!/usr/bin/env python3
"""Fail closed until current-head CI, security workflow and native CodeQL are green.

This gate is intentionally separate from build/sign jobs. A release workflow can
finish its own tests before the independent security analysis finishes.
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
import urllib.request

REQUIRED_WORKFLOWS = ("CI", "Security scan")
REQUIRED_CHECK = "CodeQL"
FAILURES = {"failure", "timed_out", "cancelled", "action_required", "startup_failure"}


def verdict(runs: list[dict], checks: list[dict], sha: str, branch: str) -> tuple[str, str]:
    if branch not in {"main", "develop"} or len(sha) != 40:
        return "failed", "Invalid immutable release branch or commit"
    current = {}
    for run in runs:
        if (run.get("head_sha") == sha and run.get("head_branch") == branch
                and run.get("event") == "push" and run.get("name") in REQUIRED_WORKFLOWS):
            name = run["name"]
            if name not in current or run.get("id", 0) > current[name].get("id", 0):
                current[name] = run
    pending = []
    for name in REQUIRED_WORKFLOWS:
        run = current.get(name)
        if run is None or run.get("status") != "completed":
            pending.append(f"workflow {name}")
        elif run.get("conclusion") != "success":
            return "failed", f"workflow {name}: {run.get('conclusion')}"
    aggregate = [check for check in checks if check.get("name") == REQUIRED_CHECK
                 and check.get("head_sha") == sha]
    if not aggregate:
        pending.append("native CodeQL aggregate")
    else:
        latest = max(aggregate, key=lambda check: check.get("id", 0))
        if latest.get("status") != "completed":
            pending.append("native CodeQL aggregate")
        elif latest.get("conclusion") in (None, "neutral", "skipped"):
            # GitHub may emit a temporary neutral aggregate while SARIF is still processing.
            pending.append("native CodeQL aggregate (not yet green)")
        elif latest.get("conclusion") != "success":
            return "failed", f"native CodeQL aggregate: {latest.get('conclusion')}"
    return ("waiting", ", ".join(pending)) if pending else ("ready", "CI, Security scan and native CodeQL all passed on identical SHA")


def fetch(endpoint: str, token: str) -> dict:
    request = urllib.request.Request(
        "https://api.github.com" + endpoint,
        headers={"Accept": "application/vnd.github+json",
                 "Authorization": "Bearer " + token,
                 "X-GitHub-Api-Version": "2022-11-28",
                 "User-Agent": "simhub-release-green-gate"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def verify(repo: str, sha: str, branch: str, token: str, *,
           attempts: int = 60, delay: int = 20) -> None:
    if not (repo == "blackkcold/simhub" and branch in {"main", "develop"}
            and len(sha) == 40 and all(c in "0123456789abcdef" for c in sha)
            and token):
        raise ValueError("Invalid release environment or missing GitHub token")
    for attempt in range(1, attempts + 1):
        params = urllib.parse.urlencode({"head_sha": sha, "branch": branch, "per_page": 100})
        workflows = fetch(f"/repos/{repo}/actions/runs?{params}", token)["workflow_runs"]
        checks = fetch(f"/repos/{repo}/commits/{sha}/check-runs?per_page=100", token)["check_runs"]
        status, description = verdict(workflows, checks, sha, branch)
        print(f"Release gates [{attempt}/{attempts}] {status}: {description}", flush=True)
        if status == "ready":
            return
        if status == "failed":
            raise RuntimeError("Release blocked: " + description)
        if attempt < attempts:
            time.sleep(delay)
    raise TimeoutError("Release blocked: independent CI / CodeQL gates never all passed")


if __name__ == "__main__":
    try:
        verify(os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"],
               os.environ["GITHUB_REF_NAME"], os.environ["GH_TOKEN"])
    except (KeyError, ValueError, RuntimeError, TimeoutError, OSError) as error:
        print("::error::" + str(error), file=sys.stderr)
        sys.exit(1)

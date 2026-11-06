#!/usr/bin/env python3
"""Fail on high/critical CodeQL findings or incomplete SARIF analysis."""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def findings(document: dict, threshold: float = 7.0) -> list[dict]:
    if document.get("version") != "2.1.0" or not isinstance(document.get("runs"), list) or not document["runs"]:
        raise ValueError("Missing valid SARIF analysis runs")
    blocked = []
    for run in document["runs"]:
        if any(invocation.get("executionSuccessful") is False for invocation in run.get("invocations", [])):
            raise ValueError("CodeQL analysis did not complete successfully")
        driver = run.get("tool", {}).get("driver", {})
        rules = {rule.get("id"): rule for component in [driver, *run.get("tool", {}).get("extensions", [])]
                 for rule in component.get("rules", [])}
        for result in run.get("results", []):
            rule = rules.get(result.get("ruleId"), {})
            if not rule and isinstance(result.get("ruleIndex"), int):
                index = result["ruleIndex"]
                candidates = driver.get("rules", [])
                if 0 <= index < len(candidates):
                    rule = candidates[index]
            properties = rule.get("properties", {})
            raw = properties.get("security-severity")
            score = None
            if raw is not None:
                score = float(raw)
                if not math.isfinite(score) or not 0 <= score <= 10:
                    raise ValueError("Invalid CodeQL security severity")
            level = result.get("level", rule.get("defaultConfiguration", {}).get("level", "warning"))
            # No unchanged-result or suppression exemption: all current high
            # security findings must be fixed before this commit can release.
            if (score is not None and score >= threshold) or level == "error":
                locations = result.get("locations", [])
                physical = locations[0].get("physicalLocation", {}) if locations else {}
                blocked.append({"rule": result.get("ruleId", rule.get("id", "unknown")),
                                "score": score, "level": level,
                                "file": physical.get("artifactLocation", {}).get("uri", "unknown"),
                                "line": physical.get("region", {}).get("startLine")})
    return blocked


def check(directory: Path) -> list[dict]:
    files = sorted(directory.rglob("*.sarif"))
    if not files:
        raise ValueError("No CodeQL SARIF produced; refusing an empty security gate")
    return [finding for path in files for finding in findings(json.loads(path.read_text("utf-8")))]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    arguments = parser.parse_args()
    try:
        blocked = check(arguments.directory)
    except (OSError, ValueError, TypeError, KeyError) as error:
        raise SystemExit("CodeQL gate failed: " + str(error))
    for finding in blocked:
        # Rule/location only; never log a potentially sensitive code snippet.
        print("CODEQL_BLOCKED " + json.dumps(finding, ensure_ascii=True))
    print(f"CodeQL gate: {len(blocked)} high/critical or error-level findings")
    raise SystemExit(1 if blocked else 0)

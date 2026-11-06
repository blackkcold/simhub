#!/usr/bin/env python3
"""Discover regression entry points; isolate Python fixtures in fresh processes.

Browser UI requires Playwright and is run separately unless --include-ui is set.
Unittest modules are discovered even if they omit a __main__ block. Legacy
standalone assertions and documentation/icon checks retain their script entry.
"""
from __future__ import annotations

import argparse
import ast
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def python_command(path: Path) -> list[str]:
    tree = ast.parse(path.read_text("utf-8"))
    unittest_module = any(
        (isinstance(node, ast.Import) and any(alias.name == "unittest" for alias in node.names))
        or (isinstance(node, ast.ImportFrom) and node.module == "unittest")
        for node in ast.walk(tree))
    if unittest_module:
        return [sys.executable, "-m", "unittest", "discover", "-v", "-s", str(path.parent), "-p", path.name]
    return [sys.executable, str(path)]


def commands(python=True, web=True, include_ui=False):
    if python:
        for directory in ("server", "modem_agent", "scripts"):
            for path in sorted(ROOT.joinpath(directory).glob("test_*.py")):
                yield path.relative_to(ROOT).as_posix(), python_command(path)
    if web:
        for path in sorted(ROOT.joinpath("web").glob("*.js")):
            yield path.relative_to(ROOT).as_posix(), ["node", "--check", str(path)]
        for path in sorted(ROOT.joinpath("web").glob("test_*.mjs")):
            if path.name == "test_ui.mjs" and not include_ui:
                continue
            yield path.relative_to(ROOT).as_posix(), ["node", str(path)]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--python-only", action="store_true")
    group.add_argument("--web-only", action="store_true")
    parser.add_argument("--include-ui", action="store_true")
    parser.add_argument("--list", action="store_true", help="List discovered entry points without running them")
    arguments = parser.parse_args()
    environment = {**os.environ, "PYTHONPATH": os.pathsep.join(
        [str(ROOT / directory) for directory in ("server", "modem_agent", "scripts")]
        + ([os.environ["PYTHONPATH"]] if os.environ.get("PYTHONPATH") else []))}
    count = 0
    for label, command in commands(not arguments.web_only, not arguments.python_only, arguments.include_ui):
        count += 1
        print(f"REGRESSION {label}", flush=True)
        if not arguments.list:
            subprocess.run(command, cwd=ROOT, env=environment, check=True)
    if not count:
        raise SystemExit("No regression entry points found")
    print(f"{'Discovered' if arguments.list else 'Passed'} {count} regression entry points", flush=True)

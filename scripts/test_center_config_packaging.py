#!/usr/bin/env python3
"""Controlled-packaging negative tests for the injected trusted-center description.

Proves, with a unique synthetic canary and the real generator:

* a complete value set produces a description only inside the generated directory;
* the canary never appears in sources, tracked resources, build logs or other outputs;
* a missing or duplicate value fails the build with a fixed code, and the failure
  output does not echo the offending input.

No production value is used or needed. Run where `gradle` is available.
"""
import os
import pathlib
import random
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
GENERATED = ROOT / "android/app/build/generated/centerConfig/center-config.json"
PIN_A = "sha256/" + "A" * 43 + "="
PIN_B = "sha256/" + "B" * 43 + "="
PIN_C = "sha256/" + "C" * 43 + "="
PIN_D = "sha256/" + "D" * 43 + "="


def gradle_command():
    override = os.environ.get("GRADLE_CMD")
    if override:
        return override.split()
    found = shutil.which("gradle")
    if not found:
        print("gradle not available", file=sys.stderr)
        return None
    return [found]


def run_generator(environment, extra=None):
    command = [*gradle_command(), "-p", "android", "--console=plain", "--quiet"]
    if extra:
        command += extra
    command += ["generateCenterConfig"]
    # A negative test must actually remove omitted inputs even when the host
    # runner has packaging variables in its inherited environment.
    isolated_env = dict(os.environ)
    for key in ("P2P_NODE_ORIGIN", "P2P_CONTROLLER_ORIGIN",
                "P2P_NODE_PIN_CURRENT", "P2P_NODE_PIN_BACKUP",
                "P2P_CONTROLLER_PIN_CURRENT", "P2P_CONTROLLER_PIN_BACKUP"):
        isolated_env.pop(key, None)
    isolated_env.update(environment)
    return subprocess.run(command, cwd=ROOT, env=isolated_env,
                          capture_output=True, text=True)


def base_environment(canary):
    return {
        "P2P_NODE_ORIGIN": f"https://node-canary-{canary}.relay.invalid",
        "P2P_CONTROLLER_ORIGIN": f"https://admin-canary-{canary}.relay.invalid",
        "P2P_NODE_PIN_CURRENT": PIN_A,
        "P2P_NODE_PIN_BACKUP": PIN_B,
        "P2P_CONTROLLER_PIN_CURRENT": PIN_C,
        "P2P_CONTROLLER_PIN_BACKUP": PIN_D,
    }


def scan(root, needle, skip_generated=True):
    hits = []
    for path in pathlib.Path(root).rglob("*"):
        if not path.is_file():
            continue
        if skip_generated and "generated/centerConfig" in str(path):
            continue
        try:
            if needle.encode() in path.read_bytes():
                hits.append(path)
        except OSError:
            continue
    return hits


def main():
    if gradle_command() is None:
        return 2
    canary = f"{random.randint(10**8, 10**9 - 1)}"
    environment = base_environment(canary)
    packaging = ["-PcenterConfigPackaging=true"]
    failures = []

    # 1. A complete value set generates exactly one description.
    if GENERATED.exists():
        GENERATED.unlink()
    complete = run_generator(environment, packaging)
    if complete.returncode != 0:
        failures.append("complete packaging configuration failed to generate")
        if canary in complete.stdout or canary in complete.stderr:
            failures.append("generator output echoed the injected value")
    if not GENERATED.exists():
        failures.append("generated description missing after a successful run")
    else:
        text = GENERATED.read_text(encoding="utf-8")
        if canary not in text:
            failures.append("generated description does not carry the injected origin")
        if "fakepublicci" in text:
            failures.append("packaging mode leaked the public fake configuration id")
    leaked = scan(ROOT / "android/app/src", canary)
    leaked += scan(ROOT / ".github", canary)
    if leaked:
        failures.append("canary reached sources or workflows: " + ", ".join(str(p.relative_to(ROOT)) for p in leaked))
    if canary in complete.stdout or canary in complete.stderr:
        failures.append("build log echoed the injected value")

    # 2. A missing value fails closed without echoing the other values.
    incomplete = dict(environment)
    incomplete.pop("P2P_NODE_PIN_BACKUP")
    missing = run_generator(incomplete, packaging)
    if missing.returncode == 0:
        failures.append("missing pin did not fail the build")
    if "CENTER_CONFIG_PIN_MISSING" not in (missing.stdout + missing.stderr):
        failures.append("missing pin failure did not report the fixed code")
    if canary in (missing.stdout + missing.stderr):
        failures.append("missing pin failure echoed the injected origin")

    # 3. A placeholder or invalid pin fails closed.
    invalid = dict(environment)
    invalid["P2P_NODE_PIN_CURRENT"] = "not-a-pin"
    bad = run_generator(invalid, packaging)
    if bad.returncode == 0:
        failures.append("invalid pin did not fail the build")
    if "CENTER_CONFIG_PIN_INVALID" not in (bad.stdout + bad.stderr):
        failures.append("invalid pin failure did not report the fixed code")

    # 4. Duplicate pins for one role fail closed.
    duplicate = dict(environment)
    duplicate["P2P_NODE_PIN_BACKUP"] = PIN_A
    twins = run_generator(duplicate, packaging)
    if twins.returncode == 0:
        failures.append("duplicate pins did not fail the build")
    if "CENTER_CONFIG_PIN_DUPLICATE" not in (twins.stdout + twins.stderr):
        failures.append("duplicate pin failure did not report the fixed code")

    # 5. Without a packaging flag the description is removed again.
    final = run_generator({}, [])
    if final.returncode != 0:
        failures.append("plain generation failed")
    if GENERATED.exists():
        failures.append("a plain build still carries a generated description")

    if failures:
        for failure in failures:
            print(failure, file=sys.stderr)
        return 1
    print("packaging configuration negative tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

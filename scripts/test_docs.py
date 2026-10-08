#!/usr/bin/env python3
"""Check evergreen product docs, local references and current UI previews.

No external network calls: run from the repository checkout with Python 3.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
DOCS = (
    "README.md",
    "README.zh-CN.md",
    "docs/INSTALLATION.md",
    "docs/DEPLOYMENT.md",
    "docs/QUICKSTART.zh-CN.md",
    "docs/UPGRADE_0.4_TO_0.5.md",
    "docs/ANDROID_SETUP.md",
    "docs/SERVER.md",
    "docs/PROTOCOL.md",
    "docs/DESIGN_SYSTEM.md",
    "docs/DEVELOPER_DIAGNOSTICS.md",
)
LINK = re.compile(r"!?(?:\[[^\]]*\])\(([^)]+)\)|<img\b[^>]*?\bsrc=['\"]([^'\"]+)['\"]", re.I)


def check() -> list[str]:
    problems: list[str] = []
    for rel in DOCS:
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"{rel}: missing document")
            continue
        content = path.read_text(encoding="utf-8")
        for match in LINK.finditer(content):
            href = (match.group(1) or match.group(2) or "").split("#", 1)[0].split("?", 1)[0].strip()
            if not href or href.startswith(("http://", "https://", "mailto:", "data:", "/")):
                continue
            target = (path.parent / unquote(href)).resolve()
            if not target.is_relative_to(ROOT.resolve()):
                problems.append(f"{rel}: link escapes repository: {href}")
            elif not target.exists():
                problems.append(f"{rel}: broken local link: {href}")
    en = (ROOT / "README.md").read_text(encoding="utf-8")
    zh = (ROOT / "README.zh-CN.md").read_text(encoding="utf-8")
    for name, body in (("README.md", en), ("README.zh-CN.md", zh)):
        for expected in ("admin.example.com", "node.example.com", "scripts/setup.py", "docs/assets/ui-devices.svg", "docs/assets/ui-desktop.svg", "docs/assets/ui-mobile.svg", "docs/assets/ui-android.svg"):
            if expected not in body:
                problems.append(f"{name}: missing installation or UI preview: {expected}")
        if any(tag in body for tag in ("## What's new in v", "## 最新两轮升级", "## v0.4.", "## v0.5.")):
            problems.append(f"{name}: avoid version-by-version feature sections on README")
        if len(body) > 9000:
            problems.append(f"{name}: README too long for a concise product overview ({len(body)} chars)")
        quick = body.split("## Quick start", 1)[-1].split("## Installation and usage flow", 1)[0] if name == "README.md" else body.split("## 快速部署", 1)[-1].split("## 安装与使用流程", 1)[0]
        if "cp .env.example .env" in quick:
            problems.append(f"{name}: obsolete manual bootstrap shown as recommended quickstart")
    previews = {
        "docs/assets/ui-desktop.svg": ("短信", "会话", "SIM 1", "新建短信", "发送短信", "DEMO"),
        "docs/assets/ui-devices.svg": ("设备", "充电中", "Wi-Fi", "同步最近 100 条", "同步更早 100 条", "诊断", "DEMO"),
        "docs/assets/ui-mobile.svg": ("示例联系人", "输入短信内容", "SIM 1", "DEMO"),
        "docs/assets/ui-android.svg": ("SIM 状态", "系统配置", "同步最近 100 条", "DEMO"),
    }
    for rel, expected_labels in previews.items():
        try:
            root = ElementTree.parse(ROOT / rel).getroot()
            if root.tag != "{http://www.w3.org/2000/svg}svg":
                problems.append(f"{rel}: missing SVG namespace")
            labels = " ".join(el.text or "" for el in root.iter())
            for label in expected_labels:
                if label not in labels:
                    problems.append(f"{rel}: obsolete/missing UI label: {label}")
        except (OSError, ElementTree.ParseError) as exc:
            problems.append(f"{rel}: invalid SVG: {exc}")
    zhquick = (ROOT / "docs/QUICKSTART.zh-CN.md").read_text(encoding="utf-8")
    if "\\`" in zhquick:
        problems.append("docs/QUICKSTART.zh-CN.md: escaped Markdown backtick found")
    server = (ROOT / "docs/SERVER.md").read_text(encoding="utf-8")
    for expected in ("SIMHUB_SESSION_TTL=86400", "SIMHUB_SESSION_IDLE_TTL=28800", "SIMHUB_MANAGEMENT_ORIGIN="):
        if expected not in server:
            problems.append(f"docs/SERVER.md: missing current server setting: {expected}")
    return problems


if __name__ == "__main__":
    errors = check()
    for error in errors:
        print("DOC ERROR:", error, file=sys.stderr)
    if errors:
        raise SystemExit(1)
    print(f"PASS: {len(DOCS)} docs, links, compact README and {4} parseable UI previews")

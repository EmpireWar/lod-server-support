#!/usr/bin/env python3
"""Export metadata from the actual immutable YAML schema; never scrape Java fields."""
from __future__ import annotations

import argparse
import json
import pathlib
try:
    from .settings_file import ROOT, invoke
except ImportError:
    from settings_file import ROOT, invoke

TARGET = ROOT / "docs/reference/settings-schema.json"


def inventory() -> dict:
    return invoke("schema")


def metadata(side: str, data: dict | None = None) -> list[dict]:
    return (data or inventory())[side.lower()]["descriptors"]


def render() -> str:
    return json.dumps(inventory(), ensure_ascii=False, indent=2) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    arguments = parser.parse_args()
    output = render()
    if arguments.check:
        if not TARGET.exists() or TARGET.read_text(encoding="utf-8") != output:
            parser.exit(1, "Settings schema inventory is stale: run tools/settings/inventory.py\n")
    else:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(output, encoding="utf-8")

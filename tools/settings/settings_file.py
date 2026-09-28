#!/usr/bin/env python3
"""The shared settings-file helper. All schema, YAML and migration work runs in Java.

Examples:
  settings_file.py create --path stage/lss-server-config.yaml
  settings_file.py edit --path stage/lss-server-config.yaml --set generation.enabled=false
  settings_file.py edit --side client --path stage/lss-client-config.yaml --set lod.receive=false
  settings_file.py migrate --platform paper --path plugins/LSS

Saving does not activate anything. Harnesses must invoke the relevant reload command
and await its receipt. JSON on --set is argument transport, not a second config format.
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
MAIN_CLASS = "dev.vox.lss.common.config.SettingsCli"
CLASSPATH_FILE = ROOT / "common/build/settings-cli/classpath.txt"


def _classpath() -> str:
    override = os.environ.get("LSS_SETTINGS_CLASSPATH")
    if override:
        return override
    inputs = list((ROOT / "common/src/main").rglob("*")) + [ROOT / "common/build.gradle"]
    latest = max((p.stat().st_mtime_ns for p in inputs if p.is_file()), default=0)
    if not CLASSPATH_FILE.exists() or CLASSPATH_FILE.stat().st_mtime_ns < latest:
        subprocess.run(["bash", str(ROOT / "tools/verify/run-gradle.sh"), "--console=plain", "-q", ":common:settingsCliClasspath", "--no-parallel"],
                       cwd=ROOT, check=True, stdout=sys.stderr)
        CLASSPATH_FILE.touch()
    return CLASSPATH_FILE.read_text(encoding="utf-8").strip()


def invoke(*arguments: str) -> dict:
    java_home = os.environ.get("JAVA_HOME_21_X64") or os.environ.get("JAVA_HOME")
    java = str(pathlib.Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not java:
        raise RuntimeError("Java 21 or later is required for the shared settings-file helper")
    process = subprocess.run([java, "-cp", _classpath(), MAIN_CLASS, *arguments], cwd=ROOT,
                             text=True, encoding="utf-8", capture_output=True)
    if process.returncode:
        raise RuntimeError(process.stderr.strip() or "Settings helper failed")
    return json.loads(process.stdout)


def edit(path: pathlib.Path | str, changes: dict, *, side: str = "server", platform: str = "mod") -> dict:
    """Write one complete, validated, comment-preserving disk transaction."""
    arguments = ["edit", "--path", str(pathlib.Path(path).resolve()), "--side", side, "--platform", platform]
    for key, value in changes.items():
        arguments += ["--set", key + "=" + json.dumps(value, ensure_ascii=False, allow_nan=False)]
    return invoke(*arguments)


def create(path: pathlib.Path | str, *, side: str = "server", platform: str = "mod") -> dict:
    return invoke("create", "--path", str(pathlib.Path(path).resolve()), "--side", side, "--platform", platform)


def validate(path: pathlib.Path | str, *, side: str = "server", platform: str = "mod") -> dict:
    return invoke("validate", "--path", str(pathlib.Path(path).resolve()), "--side", side, "--platform", platform)


def migrate(directory: pathlib.Path | str, *, side: str = "server", platform: str = "mod", prefix: str = "lss") -> dict:
    return invoke("migrate", "--path", str(pathlib.Path(directory).resolve()), "--side", side, "--platform", platform, "--prefix", prefix)


def render(changes: dict | None = None, *, side: str = "server", platform: str = "mod", document: str | None = None) -> str:
    """Render fixture bytes with the product codec; private data stays in a task temp dir."""
    with tempfile.TemporaryDirectory(prefix="lss-settings-render-") as temporary:
        path = pathlib.Path(temporary) / f"lss-{side}-config.yaml"
        if document is None:
            create(path, side=side, platform=platform)
        else:
            path.write_text(document, encoding="utf-8")
            validate(path, side=side, platform=platform)
        if changes:
            edit(path, changes, side=side, platform=platform)
        return path.read_text(encoding="utf-8")


def values(document: str | pathlib.Path, *, side: str = "server", platform: str = "mod", normalized: bool = False) -> dict:
    """Explicit local read, including private values; callers must not put it in diagnostics."""
    if isinstance(document, pathlib.Path):
        result = invoke("read", "--path", str(document.resolve()), "--side", side, "--platform", platform)
    else:
        with tempfile.TemporaryDirectory(prefix="lss-settings-read-") as temporary:
            path = pathlib.Path(temporary) / f"lss-{side}-config.yaml"
            path.write_text(document, encoding="utf-8")
            result = invoke("read", "--path", str(path), "--side", side, "--platform", platform)
    return result["normalized" if normalized else "configured"]


if __name__ == "__main__":
    if len(sys.argv) == 1 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0)
    try:
        print(json.dumps(invoke(*sys.argv[1:]), ensure_ascii=False, indent=2))
    except (RuntimeError, subprocess.CalledProcessError, OSError) as error:
        sys.exit(str(error))

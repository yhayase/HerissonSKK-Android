#!/usr/bin/env python3
"""専用エミュレーターで完全辞書バックアップの SAF E2E だけを実行します。"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys


sys.dont_write_bytecode = True


def load_completion_parser(root):
    path = root / "scripts/test-emulator.py"
    spec = importlib.util.spec_from_file_location("skk_test_emulator", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.instrumentation_succeeded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="専用エミュレーターの adb シリアル")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("実機や共用端末を使わず、専用エミュレーターを指定してください")

    root = Path(__file__).resolve().parents[1]
    succeeded = load_completion_parser(root)

    def adb(*arguments):
        return subprocess.run(
            ["adb", "-s", args.serial, *arguments],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=600,
        ).stdout

    apks = (
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    )
    for apk in apks:
        print(adb("install", "-r", str(root / apk)), end="")

    result = adb(
        "shell", "am", "instrument", "-w", "-r", "-e", "class",
        "jp.hayase.skk.CompleteBackupSafE2eTest",
        "jp.hayase.skk.test/androidx.test.runner.AndroidJUnitRunner",
    )
    report_root = root / "app/build/reports/complete-backup-saf-e2e"
    report_root.mkdir(parents=True, exist_ok=True)
    (report_root / "latest.txt").write_text(result)
    history = report_root / args.serial / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    history.mkdir(parents=True, exist_ok=False)
    (history / "instrumentation.txt").write_text(result)
    metadata = {
        "serial": args.serial,
        "api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "source_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
        "apk_sha256": {apk: hashlib.sha256((root / apk).read_bytes()).hexdigest() for apk in apks},
    }
    (history / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(result)
    if not succeeded(result):
        raise SystemExit("完全辞書バックアップ SAF E2E が失敗・スキップ、または完了しませんでした")
    print(f"完全辞書バックアップ SAF E2E 結果: {history}")


if __name__ == "__main__":
    main()

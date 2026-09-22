#!/usr/bin/env python3
"""専用エミュレーターで接続試験を実行し、終了時に以前の IME 選択を戻します。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time


def instrumentation_succeeded(result):
    """スキップや途中終了を成功件数へ含めません。"""
    statuses = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", result, re.MULTILINE)
    summary = re.search(r"^OK \((\d+) tests?\)\s*$", result, re.MULTILINE)
    return (summary is not None and int(summary.group(1)) > 0
            and statuses.count("0") == int(summary.group(1))
            and statuses.count("1") == statuses.count("0")
            and all(code in ("0", "1") for code in statuses)
            and re.search(r"^INSTRUMENTATION_CODE: -1\s*$", result, re.MULTILINE) is not None
            and "FAILURES!!!" not in result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="専用エミュレーターの adb シリアル")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("実機の入力設定を変更しないため、専用エミュレーターを指定してください")
    root = Path(__file__).resolve().parents[1]

    def adb(*arguments):
        return subprocess.run(["adb", "-s", args.serial, *arguments], check=True,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=180).stdout

    ime = "se.haya.skk/.SkkInputMethodService"
    previous = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    if not previous or previous == "null":
        raise SystemExit("元の IME 選択を復元できないため、設定を変更せず終了します")
    enabled_imes = adb("shell", "ime", "list", "-s").splitlines()
    enabled = ime in enabled_imes
    fallback = next((value for value in enabled_imes if value != ime), None)
    if previous == ime and fallback is None:
        raise SystemExit("再インストール前に切り替えられる別の有効な IME が必要です")
    apks = ("app/build/outputs/apk/debug/app-debug.apk",
                "test-editor/build/outputs/apk/debug/test-editor-debug.apk",
                "test-editor/build/outputs/apk/androidTest/debug/test-editor-debug-androidTest.apk")
    try:
        # 選択中の IME を更新すると Android 側に旧接続が残るため、先に切り替えます。
        if previous == ime:
            print(adb("shell", "ime", "set", fallback), end="")
        for apk in apks:
            print(adb("install", "-r", str(root / apk)), end="")
        deadline = time.monotonic() + 15
        while ime not in adb("shell", "ime", "list", "-a", "-s").splitlines():
            if time.monotonic() > deadline:
                raise SystemExit("IME の登録が反映されませんでした")
            time.sleep(0.2)
        print(adb("shell", "ime", "enable", ime), end="")
        print(adb("shell", "ime", "set", ime), end="")
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                     "se.haya.skk.testeditor.PhysicalInputTest,se.haya.skk.testeditor.CoreUnicodeTest",
                     "se.haya.skk.testeditor.test/androidx.test.runner.AndroidJUnitRunner")
        report = root / "test-editor/build/reports/emulator-instrumentation.txt"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(result)
        history = report.parent / "connection" / args.serial / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
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
        if not instrumentation_succeeded(result):
            raise SystemExit("結合試験が失敗・スキップ、または完了しませんでした")
    finally:
        try:
            if previous and previous != "null":
                adb("shell", "ime", "set", previous)
        finally:
            if not enabled:
                adb("shell", "ime", "disable", ime)


if __name__ == "__main__":
    main()

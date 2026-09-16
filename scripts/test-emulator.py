#!/usr/bin/env python3
"""専用エミュレーターで接続試験を実行し、終了時に以前の IME 選択を戻します。"""
import argparse
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
    if not args.serial.startswith("emulator-"):
        parser.error("実機の入力設定を変更しないため、専用エミュレーターを指定してください")
    root = Path(__file__).resolve().parents[1]

    def adb(*arguments):
        return subprocess.run(["adb", "-s", args.serial, *arguments], check=True,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=180).stdout

    ime = "jp.hayase.skk/.SkkInputMethodService"
    previous = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    enabled = ime in adb("shell", "ime", "list", "-s").splitlines()
    for apk in ("app/build/outputs/apk/debug/app-debug.apk",
                "test-editor/build/outputs/apk/debug/test-editor-debug.apk",
                "test-editor/build/outputs/apk/androidTest/debug/test-editor-debug-androidTest.apk"):
        print(adb("install", "-r", str(root / apk)), end="")
    deadline = time.monotonic() + 15
    while ime not in adb("shell", "ime", "list", "-a", "-s").splitlines():
        if time.monotonic() > deadline:
            raise SystemExit("IME の登録が反映されませんでした")
        time.sleep(0.2)
    try:
        print(adb("shell", "ime", "enable", ime), end="")
        print(adb("shell", "ime", "set", ime), end="")
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                     "jp.hayase.skk.testeditor.PhysicalInputTest",
                     "jp.hayase.skk.testeditor.test/androidx.test.runner.AndroidJUnitRunner")
        report = root / "test-editor/build/reports/emulator-instrumentation.txt"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(result)
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

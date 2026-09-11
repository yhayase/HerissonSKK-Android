#!/usr/bin/env python3
"""専用エミュレーターへ導入し、IME 選択を保ったまま結合試験を実行します。"""
import argparse
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True, help="専用エミュレーターの adb シリアル")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("実機の入力設定を変更しないため、専用エミュレーターを指定してください")
root = Path(__file__).resolve().parents[1]


def adb(*arguments):
    return subprocess.run(["adb", "-s", args.serial, *arguments], check=True,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout


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
    result = adb("shell", "am", "instrument", "-w", "-r",
                 "jp.hayase.skk.testeditor.test/androidx.test.runner.AndroidJUnitRunner")
    report = root / "test-editor/build/reports/emulator-instrumentation.txt"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(result)
    print(result)
    if "OK (" not in result or "FAILURES!!!" in result:
        raise SystemExit("結合試験が失敗しました")
finally:
    if previous and previous != "null":
        adb("shell", "ime", "set", previous)
    if not enabled:
        adb("shell", "ime", "disable", ime)

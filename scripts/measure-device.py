#!/usr/bin/env python3
"""指定端末の測定用 IME で合成入力を測定し、終了時に以前の IME 選択を戻します。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime, timezone


def bounded_count(name, maximum):
    def parse(value):
        try:
            count = int(value)
        except ValueError as error:
            raise argparse.ArgumentTypeError(f"{name} は 1 以上 {maximum} 以下の整数で指定します") from error
        if not 1 <= count <= maximum:
            raise argparse.ArgumentTypeError(f"{name} は 1 以上 {maximum} 以下で指定します")
        return count
    return parse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="測定する adb シリアル")
    parser.add_argument("--adb", default="adb", help="adb の実行ファイル")
    parser.add_argument("--skip-install", action="store_true", help="端末で APK を手動導入済みの場合に指定します")
    parser.add_argument("--startup-samples", type=bounded_count("startup-samples", 30), default=30,
                        help="初回起動と起動済みの測定回数（1〜30、既定30）")
    parser.add_argument("--input-samples", type=bounded_count("input-samples", 1000), default=1000,
                        help="準備入力後の打鍵測定回数（1〜1000、既定1000）")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    report_dir = root / "test-editor/build/reports/performance" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    report_dir.mkdir(parents=True, exist_ok=True)

    def adb(*arguments):
        return subprocess.run([args.adb, "-s", args.serial, *arguments], check=True,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=600).stdout

    package = "se.haya.skk.benchmark"
    ime = package + "/se.haya.skk.SkkInputMethodService"
    previous = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    enabled_imes = adb("shell", "ime", "list", "-s").splitlines()
    enabled = ime in enabled_imes
    fallback = previous if previous and previous != "null" and previous != ime else next(
        (value for value in enabled_imes if value != ime), None)
    if fallback is None:
        raise RuntimeError("初回起動測定には測定用以外の有効な IME が必要です")
    apks = ["app/build/outputs/apk/benchmark/app-benchmark.apk",
            "test-editor/build/outputs/apk/debug/test-editor-debug.apk",
            "test-editor/build/outputs/apk/androidTest/debug/test-editor-debug-androidTest.apk"]
    metadata = {
        "started_utc": datetime.now(timezone.utc).isoformat(),
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "source_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
        "apk_sha256": {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in apks},
        "dictionary": "BasicSkkEngine の限定試験辞書。外部辞書なし",
        "startup_boundary": "初回は IME 再選択要求、起動済みは入力確認 Activity の起動要求から IME 状態表示のアクセシビリティ観測まで",
        "input_boundary": "合成キー注入要求から入力欄の OnDraw まで。IME 受信時刻・画面への提示完了ではありません",
        "startup_samples": args.startup_samples,
        "input_samples": args.input_samples,
    }
    for prop in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.build.display.id"):
        metadata[prop] = adb("shell", "getprop", prop).strip()
    (report_dir / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    selection_changed = False
    measurement_started = False
    try:
        if not args.skip_install:
            for apk in apks:
                print(adb("install", "-r", "-t", str(root / apk)), end="", flush=True)
        for installed_package, apk in zip(
                (package, "se.haya.skk.testeditor", "se.haya.skk.testeditor.test"), apks):
            paths = adb("shell", "pm", "path", installed_package).strip().splitlines()
            if len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
                raise RuntimeError(f"測定用 APK を確認できません: {installed_package}")
            installed_hash = adb("shell", "sha256sum", paths[0].removeprefix("package:")).split()[0]
            if installed_hash != metadata["apk_sha256"][apk]:
                raise RuntimeError(f"端末の APK と測定用ビルドが一致しません: {installed_package}")
        info = adb("shell", "dumpsys", "package", package)
        if "DEBUGGABLE" in info:
            raise RuntimeError("測定用 IME がデバッグ可能になっています")
        selection_changed = True
        print(adb("shell", "ime", "enable", ime), end="", flush=True)
        print(adb("shell", "ime", "set", ime), end="", flush=True)
        if adb("shell", "settings", "get", "secure", "default_input_method").strip() != ime:
            raise RuntimeError("測定用 IME が選択されていません")
        measurement_started = True
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                     "se.haya.skk.testeditor.PerformanceTest", "-e", "performance", "true",
                     "-e", "fallback_ime", fallback,
                     "-e", "startup_samples", str(args.startup_samples),
                     "-e", "input_samples", str(args.input_samples),
                     "se.haya.skk.testeditor.test/androidx.test.runner.AndroidJUnitRunner")
        (report_dir / "instrumentation.txt").write_text(result)
        print(result)
        if "OK (1 test)" not in result or "FAILURES!!!" in result:
            raise RuntimeError("性能測定に失敗しました。レポートを確認してください")
    finally:
        try:
            if measurement_started:
                adb("shell", "am", "force-stop", "se.haya.skk.testeditor")
        finally:
            if selection_changed:
                try:
                    if previous and previous != "null":
                        adb("shell", "ime", "set", previous)
                finally:
                    if not enabled:
                        adb("shell", "ime", "disable", ime)
    print(f"測定結果: {report_dir}")


if __name__ == "__main__":
    main()

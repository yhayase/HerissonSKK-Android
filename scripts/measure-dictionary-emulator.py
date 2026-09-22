#!/usr/bin/env python3
"""専用エミュレーターで SQLite 辞書の初期化・未キャッシュ検索・キャッシュ検索を測定します。"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess


def bounded(value, name, minimum, maximum):
    try:
        number = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"{name} は整数で指定します") from error
    if not minimum <= number <= maximum:
        raise argparse.ArgumentTypeError(f"{name} は {minimum}〜{maximum} にします")
    return number


def instrumentation_succeeded(root, result):
    path = root / "scripts/test-emulator.py"
    spec = importlib.util.spec_from_file_location("skk_test_emulator", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.instrumentation_succeeded(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="専用エミュレーターの adb シリアル")
    parser.add_argument("--entries", default="1000,100000", help="カンマ区切りの辞書見出し件数（既定: 1000,100000）")
    parser.add_argument("--samples", default="256", help="キャッシュヒット検索の反復数（既定: 256）")
    parser.add_argument("--smoke", action="store_true", help="1,000 件・16 回の短い接続確認にします")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("実機や共用端末を変更しないため、専用エミュレーターを指定してください")
    entries = "1000" if args.smoke else args.entries
    tokens = entries.split(",")
    if not tokens or any(not item.strip() for item in tokens):
        parser.error("entries を一件以上指定してください")
    counts = [bounded(item.strip(), "entries", 4, 100_000) for item in tokens]
    if len(counts) > 4:
        parser.error("entries は最大4件にしてください")
    if len(set(counts)) != len(counts):
        parser.error("entries に重複は指定できません")
    samples = bounded("16" if args.smoke else args.samples, "samples", 1, 2_048)
    root = Path(__file__).resolve().parents[1]

    def adb(*arguments):
        return subprocess.run(["adb", "-s", args.serial, *arguments], check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600).stdout

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("指定先がエミュレーターとして確認できません")
    apks = ["app/build/outputs/apk/debug/app-debug.apk",
            "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    report = root / "app/build/reports/dictionary-performance" / args.serial / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    report.mkdir(parents=True, exist_ok=False)
    metadata = {
        "started_utc": datetime.now(timezone.utc).isoformat(), "serial": args.serial,
        "api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "source_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
        "apk_sha256": {apk: hashlib.sha256((root / apk).read_bytes()).hexdigest() for apk in apks},
        "fixture_entries": counts, "cached_query_samples": samples,
        "measurement_kind": "debug Android instrumentation。release build と利用者端末の受入結果は測定対象外",
    }
    (report / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    for apk in apks:
        print(adb("install", "-r", str(root / apk)), end="", flush=True)
    result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                 "se.haya.skk.dictionary.DictionaryPerformanceAndroidTest", "-e",
                 "dictionaryPerformanceEntries", ",".join(map(str, counts)), "-e",
                 "dictionaryPerformanceSamples", str(samples),
                 "se.haya.skk.test/androidx.test.runner.AndroidJUnitRunner")
    (report / "instrumentation.txt").write_text(result)
    measurements = [line for line in result.splitlines() if "DICTIONARY_PERFORMANCE" in line]
    (report / "measurements.txt").write_text("\n".join(measurements) + "\n")
    print(result)
    if not instrumentation_succeeded(root, result):
        raise SystemExit(f"辞書性能測定が失敗しました: {report}")
    print(f"辞書性能測定結果: {report}")


if __name__ == "__main__":
    main()

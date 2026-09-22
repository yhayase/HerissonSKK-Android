#!/usr/bin/env python3
"""専用エミュレーターで狭幅・大文字の IME 表示を検証し、画面設定を復元します。"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def parse_size(value):
    physical = re.search(r"^Physical size: ([0-9]+)x([0-9]+)$", value, re.M)
    override = re.search(r"^Override size: ([0-9]+)x([0-9]+)$", value, re.M)
    if physical is None:
        raise RuntimeError("画面の物理サイズを確認できません")
    baseline = tuple(map(int, physical.groups()))
    previous = tuple(map(int, override.groups())) if override else None
    if any(dimension <= 0 for dimension in baseline + (previous or ())):
        raise RuntimeError("画面サイズが不正です")
    return baseline, previous


def parse_density(value):
    physical = re.search(r"^Physical density: ([0-9]+)$", value, re.M)
    override = re.search(r"^Override density: ([0-9]+)$", value, re.M)
    if physical is None:
        raise RuntimeError("画面密度を確認できません")
    density = int((override or physical).group(1))
    if density <= 0:
        raise RuntimeError("画面密度が不正です")
    return density


def restore_display(adb, previous_size, previous_font):
    failures = []
    actions = [lambda: adb("wm", "size", f"{previous_size[0]}x{previous_size[1]}" if previous_size else "reset"),
               lambda: adb("settings", "delete", "system", "font_scale") if previous_font == "null"
               else adb("settings", "put", "system", "font_scale", previous_font)]
    for action in actions:
        try:
            action()
        except BaseException as error:
            failures.append(str(error))
    try:
        if parse_size(adb("wm", "size"))[1] != previous_size:
            failures.append("画面サイズの復元が一致しません")
        matched = 0
        for attempt in range(20):
            observed = adb("settings", "get", "system", "font_scale")
            if observed == previous_font:
                matched += 1
                if matched == 3:
                    break
            else:
                matched = 0
                if previous_font == "null" and observed == "1.0":
                    # 構成変更の完了時に API 26 が既定値を再登録する場合があります。
                    adb("settings", "delete", "system", "font_scale")
                else:
                    break
            time.sleep(.1)
        if matched != 3:
            failures.append("文字倍率の復元が一致しません")
    except BaseException as error:
        failures.append(str(error))
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--font-scale", choices=("1.3", "1.5", "2.0"), default="2.0",
                        help="端末が許可する文字倍率（API 30 の検証環境は 1.3）")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("専用エミュレーターを指定してください")
    root = Path(__file__).resolve().parents[1]

    def adb(*arguments):
        result = subprocess.run(["adb", "-s", args.serial, "shell", *arguments],
                                text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError(f"ADB 操作が失敗しました: {arguments}: {result.stdout} {result.stderr}")
        return result.stdout.strip()

    if adb("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("専用エミュレーターとして確認できません")
    physical, previous_size = parse_size(adb("wm", "size"))
    density = parse_density(adb("wm", "density"))
    previous_font = adb("settings", "get", "system", "font_scale")
    if previous_font != "null" and not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", previous_font):
        raise RuntimeError("開始時の文字倍率を確認できません")
    width = max(1, round(240 * density / 160))
    height = max((previous_size or physical)[1], round(640 * density / 160))
    target_size = f"{width}x{height}"
    report = root / "test-editor/build/reports/display-e2e" / args.serial / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    report.mkdir(parents=True, exist_ok=False)
    metadata = dict(serial=args.serial, api=adb("getprop", "ro.build.version.sdk"),
                    physical_size=physical, previous_override=previous_size,
                    density=density, previous_font_scale=previous_font,
                    requested_size=target_size, requested_width_dp=240, requested_font_scale=args.font_scale)
    (report / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    primary = None
    touched = False
    try:
        touched = True
        adb("wm", "size", target_size)
        adb("settings", "put", "system", "font_scale", args.font_scale)
        if parse_size(adb("wm", "size"))[1] != (width, height) or float(adb("settings", "get", "system", "font_scale")) != float(args.font_scale):
            raise RuntimeError("試験用の画面設定を確認できません")
        completed = subprocess.run([sys.executable, str(root / "scripts/test-customization-emulator.py"),
                                    "--serial", args.serial, "--test-class",
                                    "se.haya.skk.testeditor.CandidateDisplayE2eTest"],
                                   cwd=root, text=True, stdout=subprocess.PIPE,
                                   # 子の各 ADB 操作に期限があります。親から強制終了すると
                                   # 子の finally による設定復元を中断するため、ここでは待ちます。
                                   stderr=subprocess.STDOUT, env=os.environ.copy())
        (report / "instrumentation-runner.txt").write_text(completed.stdout)
        print(completed.stdout)
        if completed.returncode:
            raise RuntimeError("狭幅・大文字の表示試験が失敗しました")
    except BaseException as error:
        primary = error
    finally:
        failures = []
        if touched:
            failures = restore_display(adb, previous_size, previous_font)
        (report / "recovery.json").write_text(json.dumps(dict(restored=not failures, errors=failures), ensure_ascii=False, indent=2) + "\n")
        if failures:
            raise RuntimeError(f"画面設定の復元に失敗しました: {report}: {failures}; 試験={primary!r}")
    if primary:
        raise primary
    print(f"表示試験結果: {report}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""専用エミュレーターで入力設定 UI と IME 反映を、設定ファイルの復元付きで検証します。"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time


sys.dont_write_bytecode = True

PACKAGE = "se.haya.skk"
IME = f"{PACKAGE}/.SkkInputMethodService"
# 辞書本体と SQLite の補助ファイルを一組で退避します。
DICTIONARY_PATHS = tuple("databases/skk-dictionaries.db" + suffix
                         for suffix in ("", "-journal", "-wal", "-shm"))
# テストが触れ得る状態だけを、アプリ所有の固定パスとして扱います。
STATE_PATHS = (
    "files/customization-settings.json",
    "files/customization-settings.json.bak",
    "files/customization-settings.json.new",
    "shared_prefs/settings.xml",
    "shared_prefs/settings.xml.bak",
    "shared_prefs/app-emacs-editing.xml",
    "shared_prefs/app-emacs-editing.xml.bak",
) + DICTIONARY_PATHS
MISSING_EXIT = 44
PATH_PROBE = (
    'if [ -f "$1" ]; then exit 0; fi; '
    f'if [ ! -e "$1" ]; then exit {MISSING_EXIT}; fi; exit 45'
)


def load_completion_parser(root):
    path = root / "scripts/test-emulator.py"
    spec = importlib.util.spec_from_file_location("skk_test_emulator", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.instrumentation_succeeded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="専用エミュレーターの adb シリアル")
    parser.add_argument("--test-class", default="se.haya.skk.testeditor.CustomizationE2eTest",
                        choices=("se.haya.skk.testeditor.CustomizationE2eTest",
                                 "se.haya.skk.testeditor.CandidateDisplayE2eTest",
                                 "se.haya.skk.testeditor.TouchInputTest",
                                 "se.haya.skk.testeditor.PhysicalPopupLifecycleTest",
                                 "se.haya.skk.testeditor.SettingsLayoutTest",
                                 "se.haya.skk.testeditor.KeyboardVisibilityTest"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("設定ファイルを一時変更するため、専用エミュレーターを指定してください")
    root = Path(__file__).resolve().parents[1]
    succeeded = load_completion_parser(root)

    def adb(*arguments, timeout=300):
        return subprocess.run(["adb", "-s", args.serial, *arguments], check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout).stdout

    def adb_bytes(*arguments, input_bytes=None, check=True):
        result = subprocess.run(["adb", "-s", args.serial, *arguments], input=input_bytes,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
        if check and result.returncode:
            raise subprocess.CalledProcessError(result.returncode, result.args, result.stdout, result.stderr)
        return result

    report_root = root / "test-editor/build/reports/customization-e2e"
    history = report_root / args.serial / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup_dir = history / "pre-mutation-state"
    backup_dir.mkdir(parents=True, exist_ok=False)
    # APK の更新や IME 切替より先に、復旧に必要な選択状態を記録します。
    previous = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    enabled_imes = adb("shell", "ime", "list", "-s").splitlines()
    enabled = IME in enabled_imes
    fallback = previous if previous and previous != "null" and previous != IME else next(
        (value for value in enabled_imes if value != IME), None)
    if fallback is None:
        raise RuntimeError("設定を退避するには、試験対象以外の有効な IME が必要です")
    write_json(history / "recovery-plan.json", {
        "package": PACKAGE,
        "paths": list(STATE_PATHS),
        "previous_input_method": previous,
        "ime_was_enabled": enabled,
        "fallback_input_method": fallback,
    })

    apks = (
        "app/build/outputs/apk/debug/app-debug.apk",
        "test-editor/build/outputs/apk/debug/test-editor-debug.apk",
        "test-editor/build/outputs/apk/androidTest/debug/test-editor-debug-androidTest.apk",
    )
    backup = None
    primary_failure = None
    ime_state_touched = False

    try:
        ime_state_touched = True
        quiesce_ime(adb, fallback)
        for apk in apks:
            print(adb("install", "-r", str(root / apk)), end="")
        # 読出し前にプロセスを停止し、JSON と AtomicFile の補助ファイルの組を固定します。
        adb("shell", "am", "force-stop", PACKAGE)
        backup = capture_state(adb_bytes, backup_dir)
        write_json(backup_dir / "manifest.json", backup)
        # 候補順位と使用履歴を端末に残った辞書に依存させません。
        reset_dictionary(adb_bytes)

        print(adb("shell", "ime", "enable", IME), end="")
        print(adb("shell", "ime", "set", IME), end="")
        result = adb(
            "shell", "am", "instrument", "-w", "-r", "-e", "class",
            args.test_class,
            "se.haya.skk.testeditor.test/androidx.test.runner.AndroidJUnitRunner",
        )
        report_root.mkdir(parents=True, exist_ok=True)
        (report_root / "latest.txt").write_text(result)
        (history / "instrumentation.txt").write_text(result)
        metadata = {
            "serial": args.serial,
            "test_class": args.test_class,
            "api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
            "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
            "source_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)),
            "apk_sha256": {apk: hashlib.sha256((root / apk).read_bytes()).hexdigest() for apk in apks},
            "backup_manifest": str(backup_dir / "manifest.json"),
        }
        write_json(history / "metadata.json", metadata)
        print(result)
        if not succeeded(result):
            raise SystemExit("入力設定 E2E が失敗・スキップ、または完了しませんでした。復元用 state を確認してください")
    except BaseException as error:
        primary_failure = error
    finally:
        recovery_error = None
        if backup is not None:
            try:
                quiesce_ime(adb, fallback)
                adb("shell", "am", "force-stop", PACKAGE)
                restore_state(adb_bytes, backup_dir, backup)
                write_json(history / "recovery.json", {"restored": True})
            except BaseException as error:
                recovery_error = error
                write_json(history / "recovery.json", {"restored": False, "error": str(error)})
        if ime_state_touched:
            try:
                restore_ime_state(adb, previous, enabled)
            except BaseException as error:
                if recovery_error is None:
                    recovery_error = error
                    write_json(history / "recovery.json", {"restored": False, "error": str(error)})
        if recovery_error is not None:
            raise SystemExit(
                f"入力設定の復元に失敗しました。backup={backup_dir}: {recovery_error}; "
                f"試験結果={primary_failure!r}"
            ) from recovery_error
    if primary_failure is not None:
        raise primary_failure
    print(f"入力設定 E2E 結果: {history}")


def capture_state(adb_bytes, backup_dir):
    manifest = {"paths": {}}
    for index, path in enumerate(STATE_PATHS):
        present = probe_path(adb_bytes, path)
        if present:
            result = remote(adb_bytes, "run-as", PACKAGE, "cat", path, check=False)
            if result.returncode != 0 or result.stderr:
                raise RuntimeError(f"バックアップを読めません: {path}: {format_result(result)}")
            name = f"{index:02d}.bin"
            (backup_dir / name).write_bytes(result.stdout)
            manifest["paths"][path] = {
                "present": True,
                "file": name,
                "sha256": hashlib.sha256(result.stdout).hexdigest(),
                "size": len(result.stdout),
            }
        else:
            manifest["paths"][path] = {"present": False}
    return manifest


def reset_dictionary(adb_bytes):
    for path in DICTIONARY_PATHS:
        remote(adb_bytes, "run-as", PACKAGE, "rm", "-f", "--", path)


def restore_state(adb_bytes, backup_dir, manifest):
    payloads = validate_backup(backup_dir, manifest)
    # 先に固定対象を消し、開始時点で欠落していた補助ファイルも正確に欠落へ戻します。
    for path in STATE_PATHS:
        remote(adb_bytes, "run-as", PACKAGE, "rm", "-f", "--", path)
    for path in STATE_PATHS:
        if path not in payloads:
            continue
        remote(adb_bytes, "run-as", PACKAGE, "sh", "-c", 'cat > "$1"', "sh", path,
               input_bytes=payloads[path])
    for path in STATE_PATHS:
        present = probe_path(adb_bytes, path)
        if path not in payloads:
            if present:
                raise RuntimeError(f"復元後に存在してはいけないファイルがあります: {path}")
            continue
        if not present:
            raise RuntimeError(f"復元したファイルを照合できません: {path}")
        result = remote(adb_bytes, "run-as", PACKAGE, "cat", path, check=False)
        if result.returncode != 0 or result.stderr or result.stdout != payloads[path]:
            raise RuntimeError(f"復元したファイルを照合できません: {path}: {format_result(result)}")


def remote(adb_bytes, *arguments, input_bytes=None, check=True):
    command = " ".join(shlex.quote(argument) for argument in arguments)
    return adb_bytes("shell", "-T", command, input_bytes=input_bytes, check=check)


def probe_path(adb_bytes, path):
    result = remote(adb_bytes, "run-as", PACKAGE, "sh", "-c", PATH_PROBE, "sh", path, check=False)
    if not result.stdout and not result.stderr:
        if result.returncode == 0:
            return True
        if result.returncode == MISSING_EXIT:
            return False
    raise RuntimeError(f"設定ファイルの存在を確認できません: {path}: {format_result(result)}")


def validate_backup(backup_dir, manifest):
    if not isinstance(manifest, dict) or set(manifest) != {"paths"}:
        raise RuntimeError("バックアップ manifest の形式が不正です")
    entries = manifest["paths"]
    if not isinstance(entries, dict) or set(entries) != set(STATE_PATHS):
        raise RuntimeError("バックアップ manifest の対象パスが不正です")
    payloads = {}
    for index, path in enumerate(STATE_PATHS):
        entry = entries[path]
        if not isinstance(entry, dict) or not isinstance(entry.get("present"), bool):
            raise RuntimeError(f"バックアップ manifest の項目が不正です: {path}")
        if not entry["present"]:
            if set(entry) != {"present"}:
                raise RuntimeError(f"欠落ファイルの manifest が不正です: {path}")
            continue
        expected_file = f"{index:02d}.bin"
        if set(entry) != {"present", "file", "sha256", "size"} or entry["file"] != expected_file:
            raise RuntimeError(f"バックアップ manifest のファイル指定が不正です: {path}")
        payload = (backup_dir / expected_file).read_bytes()
        if entry["size"] != len(payload) or entry["sha256"] != hashlib.sha256(payload).hexdigest():
            raise RuntimeError(f"バックアップの照合に失敗しました: {path}")
        payloads[path] = payload
    return payloads


def format_result(result):
    stdout = result.stdout.decode(errors="replace")
    stderr = result.stderr.decode(errors="replace")
    return f"exit={result.returncode}, stdout={stdout!r}, stderr={stderr!r}"


def quiesce_ime(adb, fallback):
    selected = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    if selected == IME:
        adb("shell", "ime", "set", fallback)
    if IME in adb("shell", "ime", "list", "-s").splitlines():
        adb("shell", "ime", "disable", IME)
    wait_for_ime_state(adb, fallback, False)


def restore_ime_state(adb, previous, enabled):
    if enabled:
        adb("shell", "ime", "enable", IME)
    if previous and previous != "null":
        adb("shell", "ime", "set", previous)
    if not enabled:
        adb("shell", "ime", "disable", IME)
    wait_for_ime_state(adb, previous if previous and previous != "null" else None, enabled)


def wait_for_ime_state(adb, selected, enabled):
    for _ in range(30):
        actual_selected = adb("shell", "settings", "get", "secure", "default_input_method").strip()
        actual_enabled = IME in adb("shell", "ime", "list", "-s").splitlines()
        if (selected is None or actual_selected == selected) and actual_enabled == enabled:
            return
        time.sleep(0.1)
    raise RuntimeError("IME の選択・有効状態が安定しませんでした")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()

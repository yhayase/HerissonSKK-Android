#!/usr/bin/env python3
"""同一署名 APK 更新で独立した永続化 fixture が保持・移行されることを検証します。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import uuid


sys.dont_write_bytecode = True

PACKAGE = "jp.hayase.skk"
TEST_PACKAGE = f"{PACKAGE}.test"
IME = f"{PACKAGE}/.SkkInputMethodService"
RUNNER = f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
CURRENT_APK = "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-apk", required=True, type=Path, help="更新前 APK の明示パス")
    parser.add_argument("--serial", required=True, help="専用エミュレーターの adb シリアル")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("実機や共用端末を変更しないため、専用エミュレーターを指定してください")

    root = Path(__file__).resolve().parents[1]
    baseline_apk = args.baseline_apk.expanduser().resolve()
    current_apk = (root / CURRENT_APK).resolve()
    test_apk = (root / TEST_APK).resolve()
    artifacts = {"baseline": baseline_apk, "current": current_apk, "test": test_apk}

    # 端末へ触れる前に、すべてのローカル artifact 契約を検証します。
    tools = {"aapt": find_android_tool("aapt"), "apksigner": find_android_tool("apksigner")}
    inspections = {
        name: inspect_apk(path, tools, require_version=name != "test")
        for name, path in artifacts.items()
    }
    validate_artifacts(artifacts, inspections)

    fixture_id = str(uuid.uuid4())
    history = root / "app/build/reports/upgrade-persistence" / args.serial / utc_stamp()
    history.mkdir(parents=True, exist_ok=False)
    write_json(history / "preflight.json", {
        "fixture_id": fixture_id,
        "artifacts": inspections,
        "tools": tools,
    })

    def adb(*arguments, timeout=300, check=True):
        result = subprocess.run(
            ["adb", "-s", args.serial, *arguments],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
        if check and result.returncode:
            raise subprocess.CalledProcessError(result.returncode, result.args, result.stdout)
        return result.stdout

    if adb("get-state").strip() != "device":
        raise RuntimeError("指定エミュレーターが online ではありません")
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("指定端末は ro.kernel.qemu=1 のエミュレーターではありません")
    # 復旧時に端末が切断されても記録を書けるよう、変更前に取得します。
    device_api = adb("shell", "getprop", "ro.build.version.sdk").strip()
    device_fingerprint = adb("shell", "getprop", "ro.build.fingerprint").strip()
    source = source_metadata(root)
    previous = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    enabled_imes = adb("shell", "ime", "list", "-s").splitlines()
    ime_was_enabled = IME in enabled_imes
    fallback = previous if previous and previous != "null" and previous != IME else next(
        (value for value in enabled_imes if value != IME), None)
    if fallback is None:
        raise RuntimeError("試験対象以外の有効なフォールバック IME が必要です")

    mutation_started = False
    ime_state_touched = False
    current_restored = False
    primary_failure = None
    phase_results = {}
    recovery_errors = []
    try:
        ime_state_touched = True
        quiesce_ime(adb, fallback)
        adb("shell", "am", "force-stop", PACKAGE)
        wait_for_process_exit(adb)

        mutation_started = True
        phase_results["install_baseline"] = adb("install", "-r", "-d", str(baseline_apk), timeout=600)
        phase_results["install_test"] = adb("install", "-r", str(test_apk), timeout=600)
        phase_results["seed"] = run_instrumentation(
            adb, "jp.hayase.skk.UpgradePersistenceSeedTest", "seed", fixture_id)
        require_instrumentation_success(phase_results["seed"], "更新前 fixture 作成")

        phase_results["install_current"] = adb("install", "-r", str(current_apk), timeout=600)
        current_restored = True
        phase_results["verify"] = run_instrumentation(
            adb, "jp.hayase.skk.UpgradePersistenceTest", "verify", fixture_id)
        require_instrumentation_success(phase_results["verify"], "更新後 fixture 検証")
    except BaseException as error:
        primary_failure = error
    finally:
        if mutation_started and not current_restored:
            try:
                quiesce_ime(adb, fallback)
                adb("shell", "am", "force-stop", PACKAGE)
                wait_for_process_exit(adb)
                phase_results["recovery_install_current"] = adb(
                    "install", "-r", str(current_apk), timeout=600)
                current_restored = True
            except BaseException as error:
                recovery_errors.append(f"現行 APK の復元: {error}")
        if mutation_started and current_restored:
            try:
                adb("shell", "am", "force-stop", PACKAGE)
                cleanup_fixture(adb, fixture_id)
            except BaseException as error:
                recovery_errors.append(f"fixture の削除: {error}")
        if ime_state_touched and (not mutation_started or current_restored):
            try:
                restore_ime_state(adb, previous, ime_was_enabled)
            except BaseException as error:
                recovery_errors.append(f"IME 状態の復元: {error}")

        for name, output in phase_results.items():
            (history / f"{name}.txt").write_text(output)
        metadata = {
            "serial": args.serial,
            "fixture_id": fixture_id,
            "api": device_api,
            "device_fingerprint": device_fingerprint,
            "source": source,
            "configuration": {
                "package": PACKAGE,
                "test_package": TEST_PACKAGE,
                "runner": RUNNER,
                "previous_input_method": previous,
                "ime_was_enabled": ime_was_enabled,
                "fallback_input_method": fallback,
                "baseline_install_flags": ["-r", "-d"],
                "current_install_flags": ["-r"],
            },
            "artifacts": inspections,
            "completed_phases": list(phase_results),
            "current_restored": current_restored,
            "recovery_errors": recovery_errors,
            "failure": None if primary_failure is None else repr(primary_failure),
        }
        write_json(history / "metadata.json", metadata)

    if recovery_errors:
        raise SystemExit(
            f"更新試験の復旧に失敗しました。APK と IME の状態は復旧記録を確認してください: {history}; "
            + "; ".join(recovery_errors)
        )
    if primary_failure is not None:
        raise primary_failure
    print(f"APK 更新永続化試験に成功しました: {history}")


def find_android_tool(name):
    direct = shutil.which(name)
    if direct:
        return str(Path(direct).resolve())
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    candidates = []
    for value in filter(None, roots):
        candidates.extend(Path(value).glob(f"build-tools/*/{name}"))
    if not candidates:
        raise RuntimeError(f"Android SDK の {name} が見つかりません")
    return str(sorted(candidates, key=lambda path: version_key(path.parent.name))[-1].resolve())


def version_key(value):
    return tuple((0, int(part)) if part.isdigit() else (1, part) for part in re.split(r"[.-]", value))


def inspect_apk(path, tools, require_version):
    if not path.is_file():
        raise RuntimeError(f"APK がありません: {path}")
    badging = run_local([tools["aapt"], "dump", "badging", str(path)])
    package, version_code, version_name = parse_badging(badging)
    if require_version and (version_code is None or version_code <= 0 or not version_name):
        raise RuntimeError(f"app APK の正の versionCode/versionName を取得できません: {path}")
    xmltree = run_local([tools["aapt"], "dump", "xmltree", str(path), "AndroidManifest.xml"])
    instrumentation = parse_instrumentation(xmltree)
    certs = run_local([tools["apksigner"], "verify", "--verbose", "--print-certs", str(path)])
    digests = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", certs, re.MULTILINE)
    if not digests:
        raise RuntimeError(f"APK 署名 SHA-256 を取得できません: {path}")
    return {
        "path": str(path),
        "sha256": sha256_file(path),
        "package": package,
        "version_code": version_code,
        "version_name": version_name,
        "instrumentation": instrumentation,
        "signer_sha256": sorted(value.lower() for value in digests),
    }


def parse_badging(badging):
    match = re.search(r"^package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", badging, re.MULTILINE)
    if match is None:
        raise RuntimeError("APK package 情報を厳密に取得できません")
    version_code = int(match.group(2)) if match.group(2).isdigit() else None
    return match.group(1), version_code, match.group(3)


def validate_artifacts(artifacts, inspections):
    if artifacts["baseline"] == artifacts["current"]:
        raise RuntimeError("baseline APK と現行 APK に同じパスは指定できません")
    if inspections["baseline"]["package"] != PACKAGE or inspections["current"]["package"] != PACKAGE:
        raise RuntimeError("baseline/current APK の package が試験対象と一致しません")
    if inspections["test"]["package"] != TEST_PACKAGE:
        raise RuntimeError("test APK の package が試験対象と一致しません")
    if inspections["test"]["instrumentation"] != {
        "runner": "androidx.test.runner.AndroidJUnitRunner",
        "target_package": PACKAGE,
    }:
        raise RuntimeError("test APK の instrumentation runner または target package が一致しません")
    signatures = {tuple(value["signer_sha256"]) for value in inspections.values()}
    if len(signatures) != 1:
        raise RuntimeError("baseline/current/test APK の署名が一致しません")
    if inspections["baseline"]["version_code"] > inspections["current"]["version_code"]:
        raise RuntimeError("baseline APK の versionCode が現行 APK より新しいため更新試験になりません")
    if inspections["baseline"]["sha256"] == inspections["current"]["sha256"]:
        raise RuntimeError("baseline APK と現行 APK の内容が同一です")


def parse_instrumentation(xmltree):
    lines = xmltree.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^(\s*)E: instrumentation(?:\s|$)", line)
        if match is None:
            continue
        indent = len(match.group(1))
        block = []
        for child in lines[index + 1:]:
            child_indent = len(child) - len(child.lstrip())
            if child.strip().startswith("E:") and child_indent <= indent:
                break
            block.append(child)
        text = "\n".join(block)
        runner = re.search(r'^\s*A: android:name[^=]*="([^"]+)"', text, re.MULTILINE)
        target = re.search(r'^\s*A: android:targetPackage[^=]*="([^"]+)"', text, re.MULTILINE)
        if runner is None or target is None:
            raise RuntimeError("instrumentation 要素の name/targetPackage を取得できません")
        return {"runner": runner.group(1), "target_package": target.group(1)}
    return None


def run_local(command):
    return subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=120).stdout


def run_instrumentation(adb, class_name, phase, fixture_id):
    return adb(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", class_name,
        "-e", "phase", phase,
        "-e", "fixture_id", fixture_id,
        RUNNER,
        timeout=600,
    )


def require_instrumentation_success(result, label):
    statuses = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)\s*$", result, re.MULTILINE)
    summary = re.search(r"^OK \(([0-9]+) tests?\)\s*$", result, re.MULTILINE)
    valid = (
        summary is not None
        and int(summary.group(1)) == 1
        and statuses == ["1", "0"]
        and re.search(r"^INSTRUMENTATION_CODE: -1\s*$", result, re.MULTILINE) is not None
        and "FAILURES!!!" not in result
    )
    if not valid:
        raise RuntimeError(f"{label}が失敗・スキップ・途中終了しました")


def quiesce_ime(adb, fallback):
    selected = adb("shell", "settings", "get", "secure", "default_input_method").strip()
    if selected != fallback:
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
    for _ in range(50):
        actual_selected = adb("shell", "settings", "get", "secure", "default_input_method").strip()
        actual_enabled = IME in adb("shell", "ime", "list", "-s").splitlines()
        if (selected is None or actual_selected == selected) and actual_enabled == enabled:
            return
        time.sleep(0.1)
    raise RuntimeError("IME の選択・有効状態が安定しませんでした")


def wait_for_process_exit(adb):
    for _ in range(50):
        if not adb("shell", "pidof", PACKAGE, check=False).strip():
            return
        time.sleep(0.1)
    raise RuntimeError("試験対象プロセスが停止しませんでした")


def cleanup_fixture(adb, fixture_id):
    # fixture_id はホスト生成 UUID で、ここで再検証して固定相対パスだけを組み立てます。
    parsed = uuid.UUID(fixture_id)
    if str(parsed) != fixture_id:
        raise RuntimeError("cleanup fixture_id が正規 UUID ではありません")
    database = f"databases/upgrade-{fixture_id}.db"
    customization = f"files/upgrade-{fixture_id}-customization.json"
    sentinel = f"files/upgrade-{fixture_id}-sentinel.bin"
    paths = [
        database, database + "-journal", database + "-wal", database + "-shm",
        customization, customization + ".bak", customization + ".new", sentinel,
    ]
    command = " ".join(shlex.quote(value) for value in ["run-as", PACKAGE, "rm", "-f", "--", *paths])
    adb("shell", "-T", command)


def source_metadata(root):
    result = {"root": str(root)}
    try:
        result["commit"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True, timeout=30).strip()
        result["dirty"] = bool(subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=root, text=True, timeout=30))
    except (OSError, subprocess.SubprocessError):
        result["commit"] = None
        result["dirty"] = None
    return result


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def utc_stamp():
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()

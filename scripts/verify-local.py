#!/usr/bin/env python3
"""単体試験・lint・配布物検査を実行し、機械可読の結果を保存します。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET


REQUIRED_NOTICES = {"META-INF/HerissonSKK-MIT.txt", "META-INF/license-scope.txt", "META-INF/icon-usage.txt", "META-INF/icu-LICENSE.txt", "META-INF/Apache-2.0.txt", "META-INF/third-party-notices.txt", "META-INF/external-dictionaries.txt", "META-INF/GPL-2.0.txt"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="既存 Gradle キャッシュだけで検証します")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    report = root / "build/reports/local-verification" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    report.mkdir(parents=True, exist_ok=False)
    data = {"started_utc": datetime.now(timezone.utc).isoformat(), "steps": [], "artifacts": [],
            "not_executed": ["端末 instrumentation", "実機・物理キーボード", "本番署名と公開"]}

    def run(command, name):
        result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (report / f"{name}.txt").write_text(result.stdout)
        data["steps"].append({"name": name, "command": command, "exit_code": result.returncode})
        print(result.stdout, end="", flush=True)
        result.check_returncode()
        return result.stdout

    try:
        bundled_mit = root / "core/src/main/resources/META-INF/HerissonSKK-MIT.txt"
        if bundled_mit.read_bytes() != (root / "LICENSE").read_bytes():
            raise RuntimeError("本体の MIT 本文と APK 用リソースが一致しません")
        data["commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
        data["dirty"] = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True))
        run(["java", "-version"], "java")
        host_runner = (
            "import sys, unittest\n"
            "suite = unittest.defaultTestLoader.discover('scripts', pattern='test*test.py')\n"
            "result = unittest.TextTestRunner().run(suite)\n"
            "sys.exit(0 if result.wasSuccessful() and result.testsRun > 0 and not result.skipped else 1)\n"
        )
        run(["python3", "-c", host_runner], "host-tests")
        # Robolectric 4.14.1 は異なる SDK のネイティブ資源を同じ JVM で開くと競合します。
        # 各 SDK を別実行し、上書きされる前の XML を保存して全件を集計します。
        android_results = report / "android-unit-results"
        data["android_unit_tests_by_sdk"] = {}
        for sdk in (26, 30, 35):
            command = ["./gradlew", ":app:testDebugUnitTest", f"-Probolectric.enabledSdks={sdk}"]
            if args.offline:
                command.append("--offline")
            run(command, f"android-unit-api{sdk}")
            shutil.copytree(root / "app/build/test-results/testDebugUnitTest", android_results / str(sdk))
            data["android_unit_tests_by_sdk"][str(sdk)] = validated_test_totals(
                (android_results / str(sdk)).glob("TEST-*.xml"), f"API {sdk}")
        command = ["./gradlew", ":core:test", ":app:lintDebug", ":app:lintRelease",
                   ":app:assembleDebug", ":app:assembleRelease", ":app:assembleBenchmark", ":app:bundleRelease",
                   ":app:assembleDebugAndroidTest", ":test-editor:assembleDebug", ":test-editor:assembleDebugAndroidTest", ":test-editor:lintDebug"]
        if args.offline:
            command.append("--offline")
        run(command, "gradle")
        data["unit_tests"] = {}
        for module, task in (("core", "test"), ("app", "testDebugUnitTest")):
            result_files = (android_results.glob("*/TEST-*.xml") if module == "app" else
                            (root / module / "build/test-results" / task).glob("TEST-*.xml"))
            data["unit_tests"][module] = validated_test_totals(result_files, module)
        aapt = find_aapt(root)
        for variant, filename in [("debug", "app-debug.apk"), ("release", "app-release-unsigned.apk"), ("benchmark", "app-benchmark.apk")]:
            path = root / "app/build/outputs/apk" / variant / filename
            with zipfile.ZipFile(path) as apk:
                missing = REQUIRED_NOTICES - set(apk.namelist())
                if missing:
                    raise RuntimeError(f"ライセンス通知が APK にありません: {variant}: {sorted(missing)}")
                for notice in REQUIRED_NOTICES:
                    expected = root / "core/src/main/resources" / notice
                    if apk.read(notice) != expected.read_bytes():
                        raise RuntimeError(f"APK のライセンス本文がソースと一致しません: {variant}: {notice}")
                if apk.testzip() is not None:
                    raise RuntimeError(f"APK の ZIP 検査に失敗しました: {variant}")
            manifest = run([aapt, "dump", "xmltree", str(path), "AndroidManifest.xml"], f"manifest-{variant}")
            permissions = run([aapt, "dump", "permissions", str(path)], f"permissions-{variant}")
            badging = run([aapt, "dump", "badging", str(path)], f"badging-{variant}")
            if "sdkVersion:'26'" not in badging or "targetSdkVersion:'36'" not in badging:
                raise RuntimeError(f"配布物の最低 API／対象 API が一致しません: {variant}")
            if "android.permission.INTERNET" not in permissions:
                raise RuntimeError(f"ネットワーク辞書に必要な INTERNET 権限がありません: {variant}")
            if variant != "debug" and "DictionaryCrashTestService" in manifest:
                raise RuntimeError(f"デバッグ専用サービスが配布ソースセットにあります: {variant}")
            if "CompleteBackupFaultProvider" in manifest:
                raise RuntimeError(f"試験 APK 専用プロバイダーがアプリに含まれています: {variant}")
            data["artifacts"].append({"variant": variant, "path": str(path.relative_to(root)),
                                      "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                                      "notices": sorted(REQUIRED_NOTICES)})
        bundle = root / "app/build/outputs/bundle/release/app-release.aab"
        with zipfile.ZipFile(bundle) as archive:
            if archive.testzip() is not None:
                raise RuntimeError("AAB の ZIP 検査に失敗しました")
            for notice in REQUIRED_NOTICES:
                if archive.read("base/root/" + notice) != (root / "core/src/main/resources" / notice).read_bytes():
                    raise RuntimeError(f"AAB のライセンス本文がソースと一致しません: {notice}")
        data["artifacts"].append({"variant": "release-bundle", "path": str(bundle.relative_to(root)),
                                  "bytes": bundle.stat().st_size,
                                  "sha256": hashlib.sha256(bundle.read_bytes()).hexdigest(),
                                  "notices": sorted(REQUIRED_NOTICES)})
        data["success"] = True
    except BaseException as error:
        data["success"] = False
        data["error"] = repr(error)
        raise
    finally:
        data["finished_utc"] = datetime.now(timezone.utc).isoformat()
        (report / "result.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
        print(f"ローカル検証記録: {report}")


def validated_test_totals(result_files, label):
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for result_file in result_files:
        suite = ET.parse(result_file).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    if not totals["tests"] or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise RuntimeError(f"単体試験の未実行・失敗・スキップがあります: {label}: {totals}")
    return totals


def find_aapt(root):
    direct = shutil.which("aapt")
    if direct:
        return direct
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    properties = root / "local.properties"
    if not sdk and properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.removeprefix("sdk.dir=")
    if not sdk:
        raise RuntimeError("Android SDK の場所を指定してください")
    aapt = Path(sdk) / "build-tools/35.0.0/aapt"
    if not aapt.is_file():
        raise RuntimeError("Build Tools 35.0.0 の aapt がありません")
    return str(aapt)


if __name__ == "__main__":
    main()

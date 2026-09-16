import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("upgrade_runner", ROOT / "scripts/test-upgrade-emulator.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)

# build-tools 35.0.0 で実際の androidTest APK から取得した必要部分です。
TEST_BADGING = """\
package: name='jp.hayase.skk.test' versionCode='' versionName='' compileSdkVersion='35' compileSdkVersionCodename='15'
sdkVersion:'26'
targetSdkVersion:'35'
"""

TEST_XMLTREE = """\
N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    A: package="jp.hayase.skk.test" (Raw: "jp.hayase.skk.test")
    E: instrumentation (line=9)
      A: android:label(0x01010001)="Tests for jp.hayase.skk" (Raw: "Tests for jp.hayase.skk")
      A: android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner" (Raw: "androidx.test.runner.AndroidJUnitRunner")
      A: android:targetPackage(0x01010021)="jp.hayase.skk" (Raw: "jp.hayase.skk")
      A: android:handleProfiling(0x01010022)=(type 0x12)0x0
    E: application (line=24)
      A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff
"""


class UpgradeRunnerPreflightTest(unittest.TestCase):
    def test_test_apk_allows_missing_version_and_reads_xmltree_instrumentation(self):
        self.assertEqual(
            ("jp.hayase.skk.test", None, ""),
            RUNNER.parse_badging(TEST_BADGING),
        )
        self.assertEqual(
            {
                "runner": "androidx.test.runner.AndroidJUnitRunner",
                "target_package": "jp.hayase.skk",
            },
            RUNNER.parse_instrumentation(TEST_XMLTREE),
        )

    def test_signature_mismatch_is_rejected_by_local_validation(self):
        artifacts, inspections = valid_artifacts()
        inspections["test"]["signer_sha256"] = ["different"]
        with self.assertRaisesRegex(RuntimeError, "署名"):
            RUNNER.validate_artifacts(artifacts, inspections)

    def test_package_and_instrumentation_mismatches_are_rejected(self):
        artifacts, inspections = valid_artifacts()
        inspections["baseline"]["package"] = "example.invalid"
        with self.assertRaisesRegex(RuntimeError, "package"):
            RUNNER.validate_artifacts(artifacts, inspections)

        artifacts, inspections = valid_artifacts()
        inspections["test"]["instrumentation"]["target_package"] = "example.invalid"
        with self.assertRaisesRegex(RuntimeError, "instrumentation"):
            RUNNER.validate_artifacts(artifacts, inspections)

    def test_version_direction_and_identical_apps_are_rejected(self):
        artifacts, inspections = valid_artifacts()
        inspections["baseline"]["version_code"] = 2
        with self.assertRaisesRegex(RuntimeError, "versionCode"):
            RUNNER.validate_artifacts(artifacts, inspections)

        artifacts, inspections = valid_artifacts()
        inspections["baseline"]["sha256"] = inspections["current"]["sha256"]
        with self.assertRaisesRegex(RuntimeError, "同一"):
            RUNNER.validate_artifacts(artifacts, inspections)


def valid_artifacts():
    artifacts = {
        "baseline": Path("/tmp/baseline.apk"),
        "current": Path("/tmp/current.apk"),
        "test": Path("/tmp/test.apk"),
    }
    app = {
        "package": "jp.hayase.skk",
        "version_code": 1,
        "sha256": "baseline",
        "signer_sha256": ["same"],
        "instrumentation": None,
    }
    inspections = {
        "baseline": dict(app),
        "current": dict(app, sha256="current"),
        "test": {
            "package": "jp.hayase.skk.test",
            "version_code": None,
            "sha256": "test",
            "signer_sha256": ["same"],
            "instrumentation": {
                "runner": "androidx.test.runner.AndroidJUnitRunner",
                "target_package": "jp.hayase.skk",
            },
        },
    }
    return artifacts, inspections


if __name__ == "__main__":
    unittest.main()

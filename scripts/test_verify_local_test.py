"""SDK ごとの単体試験集計が欠落・失敗を受け入れないことを確認します。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("verify_local", Path(__file__).with_name("verify-local.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class UnitResultTest(unittest.TestCase):
    def test_empty_sdk_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "API 26"):
            VERIFY.validated_test_totals([], "API 26")

    def test_failure_error_and_skip_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "TEST-result.xml"
            for key in ("failures", "errors", "skipped"):
                with self.subTest(key=key):
                    path.write_text(f'<testsuite tests="2" {key}="1"/>')
                    with self.assertRaises(RuntimeError):
                        VERIFY.validated_test_totals([path], "API 35")

    def test_all_suites_are_counted(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / f"TEST-{index}.xml" for index in range(2)]
            for path, count in zip(paths, (2, 3)):
                path.write_text(f'<testsuite tests="{count}" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(dict(tests=5, failures=0, errors=0, skipped=0),
                             VERIFY.validated_test_totals(iter(paths), "API 30"))


if __name__ == "__main__":
    unittest.main()

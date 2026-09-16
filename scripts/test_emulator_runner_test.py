"""接続試験の合否判定でスキップ・途中終了を成功扱いしないことを検証します。"""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("emulator_runner", Path(__file__).with_name("test-emulator.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def report(codes, count, final=True):
    lines = [f"INSTRUMENTATION_STATUS_CODE: {code}" for code in codes]
    lines.append(f"OK ({count} tests)")
    if final:
        lines.append("INSTRUMENTATION_CODE: -1")
    return "\n".join(lines) + "\n"


class InstrumentationResultTest(unittest.TestCase):
    def test_all_completed_cases_pass(self):
        self.assertTrue(runner.instrumentation_succeeded(report([1, 0, 1, 0, 1, 0], 3)))

    def test_assumption_skip_is_not_success_despite_ok_summary(self):
        self.assertFalse(runner.instrumentation_succeeded(report([1, -4, 1, 0], 2)))

    def test_ignored_failed_or_error_case_is_not_success(self):
        for code in (-3, -2, -1):
            with self.subTest(code=code):
                self.assertFalse(runner.instrumentation_succeeded(report([1, code], 1)))

    def test_incomplete_run_is_not_success(self):
        self.assertFalse(runner.instrumentation_succeeded(report([1, 0, 1], 1)))
        self.assertFalse(runner.instrumentation_succeeded(report([1, 0], 1, final=False)))

    def test_empty_or_mismatched_summary_is_not_success(self):
        for value in ("", report([], 0), report([1, 0], 2)):
            with self.subTest(value=value):
                self.assertFalse(runner.instrumentation_succeeded(value))


if __name__ == "__main__":
    unittest.main()

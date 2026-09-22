"""接続試験の合否判定でスキップ・途中終了を成功扱いしないことを検証します。"""
import importlib.util
from pathlib import Path
import unittest
from unittest import mock
import subprocess

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


class ReinstallLifecycleTest(unittest.TestCase):
    IME = "se.haya.skk/.SkkInputMethodService"
    FALLBACK = "com.example/.Keyboard"

    def run_until_failure(self, enabled, failure, previous=None):
        calls = []

        def run(command, **_):
            arguments = tuple(command[3:])
            calls.append(arguments)
            if arguments == ("shell", "settings", "get", "secure", "default_input_method"):
                output = self.IME if previous is None else previous
            elif arguments == ("shell", "ime", "list", "-s"):
                output = "\n".join(enabled)
            elif arguments == ("shell", "ime", "list", "-a", "-s"):
                output = "\n".join([self.IME, self.FALLBACK])
            elif arguments[0] == failure or arguments[:3] == ("shell", "am", failure):
                raise subprocess.CalledProcessError(1, command)
            else:
                output = ""
            return subprocess.CompletedProcess(command, 0, output)

        with mock.patch("sys.argv", ["test-emulator.py", "--serial", "emulator-5584"]), \
                mock.patch.object(runner.subprocess, "run", side_effect=run), \
                mock.patch("builtins.print"):
            with self.assertRaises((subprocess.CalledProcessError, SystemExit)):
                runner.main()
        return calls

    def test_switches_before_install_and_restores_selection_when_install_fails(self):
        calls = self.run_until_failure([self.IME, self.FALLBACK], "install")
        switch = calls.index(("shell", "ime", "set", self.FALLBACK))
        install = next(index for index, value in enumerate(calls) if value[0] == "install")
        self.assertLess(switch, install)
        self.assertEqual(("shell", "ime", "set", self.IME), calls[-1])

    def test_instrumentation_failure_restores_previously_disabled_state(self):
        calls = self.run_until_failure([self.FALLBACK], "instrument", previous=self.FALLBACK)
        self.assertEqual(("shell", "ime", "set", self.FALLBACK), calls[-2])
        self.assertEqual(("shell", "ime", "disable", self.IME), calls[-1])

    def test_missing_original_selection_stops_before_mutation(self):
        for previous in ("", "null"):
            with self.subTest(previous=previous):
                calls = self.run_until_failure([self.IME, self.FALLBACK], "install", previous=previous)
                self.assertEqual([("shell", "settings", "get", "secure", "default_input_method")], calls)

    def test_missing_fallback_stops_before_mutation(self):
        calls = self.run_until_failure([self.IME], "install")
        self.assertEqual(2, len(calls))


if __name__ == "__main__":
    unittest.main()

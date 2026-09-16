import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("display_runner", Path(__file__).with_name("test-display-emulator.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class DisplayRecoveryTest(unittest.TestCase):
    def test_physical_and_override_values_are_distinct(self):
        self.assertEqual(((320, 640), None), runner.parse_size("Physical size: 320x640"))
        self.assertEqual(((320, 640), (240, 640)), runner.parse_size("Physical size: 320x640\nOverride size: 240x640"))
        self.assertEqual(160, runner.parse_density("Physical density: 160"))
        self.assertEqual(240, runner.parse_density("Physical density: 160\nOverride density: 240"))

    def test_unknown_or_zero_screen_values_fail_closed(self):
        for value in ("permission denied", "Physical size: 0x640"):
            with self.assertRaises(RuntimeError):
                runner.parse_size(value)
        with self.assertRaises(RuntimeError):
            runner.parse_density("Physical density: 0")

    def test_restore_failure_does_not_skip_font_restore(self):
        calls = []
        def adb(*args):
            calls.append(args)
            if args == ("wm", "size", "reset"):
                raise RuntimeError("injected size failure")
            if args == ("wm", "size"):
                return "Physical size: 320x640\nOverride size: 240x640"
            if args == ("settings", "get", "system", "font_scale"):
                return "null"
            return ""
        failures = runner.restore_display(adb, None, "null")
        self.assertIn(("settings", "delete", "system", "font_scale"), calls)
        self.assertEqual(2, len(failures))

    def test_preexisting_override_is_restored_exactly(self):
        calls = []
        def adb(*args):
            calls.append(args)
            if args == ("wm", "size"):
                return "Physical size: 320x640\nOverride size: 300x700"
            if args == ("settings", "get", "system", "font_scale"):
                return "1.25"
            return ""
        self.assertEqual([], runner.restore_display(adb, (300, 700), "1.25"))
        self.assertIn(("wm", "size", "300x700"), calls)
        self.assertIn(("settings", "put", "system", "font_scale", "1.25"), calls)

    def test_default_reinserted_during_configuration_change_is_removed(self):
        reads = iter(["1.0", "null", "null", "null"])
        deletes = []
        def adb(*args):
            if args == ("wm", "size"):
                return "Physical size: 320x640"
            if args == ("settings", "get", "system", "font_scale"):
                return next(reads)
            if args == ("settings", "delete", "system", "font_scale"):
                deletes.append(args)
            return ""
        self.assertEqual([], runner.restore_display(adb, None, "null"))
        self.assertEqual(2, len(deletes))


if __name__ == "__main__":
    unittest.main()

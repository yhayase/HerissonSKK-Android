#!/usr/bin/env python3
"""入力設定 E2E ランナーの復元境界を端末なしで検証します。"""
import importlib.util
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("test-customization-emulator.py")
SPEC = importlib.util.spec_from_file_location("customization_runner", SCRIPT)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


def completed(code=0, stdout=b"", stderr=b""):
    return subprocess.CompletedProcess([], code, stdout, stderr)


def manifest_for(backup, present_paths=()):
    entries = {}
    for index, path in enumerate(RUNNER.STATE_PATHS):
        if path not in present_paths:
            entries[path] = {"present": False}
            continue
        name = f"{index:02d}.bin"
        payload = backup[name]
        entries[path] = {
            "present": True,
            "file": name,
            "sha256": RUNNER.hashlib.sha256(payload).hexdigest(),
            "size": len(payload),
        }
    return {"paths": entries}


def command_path(arguments):
    return shlex.split(arguments[-1])[-1]


class CustomizationRunnerTest(unittest.TestCase):
    def test_dictionary_reset_is_limited_to_backed_up_database_family(self):
        calls = []
        RUNNER.reset_dictionary(lambda *args, **kwargs: calls.append(args) or completed())
        self.assertEqual(set(RUNNER.DICTIONARY_PATHS), {command_path(args) for args in calls})
        self.assertTrue(set(RUNNER.DICTIONARY_PATHS).issubset(RUNNER.STATE_PATHS))

    def test_dictionary_and_sidecars_are_restored_byte_for_byte(self):
        original = {path: (path + "\x00\xff").encode() for path in RUNNER.DICTIONARY_PATHS}
        state = dict(original)

        def adb(*arguments, input_bytes=None, **_):
            command = arguments[-1]
            path = command_path(arguments)
            if RUNNER.PATH_PROBE in command:
                return completed(0 if path in state else RUNNER.MISSING_EXIT)
            if " rm " in f" {command} ":
                state.pop(path, None)
                return completed()
            if input_bytes is not None:
                state[path] = input_bytes
                return completed()
            return completed(stdout=state[path])

        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory)
            manifest = RUNNER.capture_state(adb, backup)
            RUNNER.reset_dictionary(adb)
            self.assertEqual({}, state)
            state[RUNNER.DICTIONARY_PATHS[0]] = b"test dictionary"
            RUNNER.restore_state(adb, backup, manifest)
            self.assertEqual(original, state)
            # 元々なかった WAL/SHM も、試験で生成された場合は残しません。
            state.clear()
            state[RUNNER.DICTIONARY_PATHS[0]] = original[RUNNER.DICTIONARY_PATHS[0]]
            manifest = RUNNER.capture_state(adb, backup)
            state.update({path: b"created by test" for path in RUNNER.DICTIONARY_PATHS[1:]})
            RUNNER.restore_state(adb, backup, manifest)
            self.assertEqual({RUNNER.DICTIONARY_PATHS[0]: original[RUNNER.DICTIONARY_PATHS[0]]}, state)

    def test_capture_allows_only_exact_missing_files(self):
        payload = b'{"generation":3}'

        def adb(*arguments, **_):
            command = arguments[-1]
            path = command_path(arguments)
            if RUNNER.PATH_PROBE in command:
                return completed(0 if path == RUNNER.STATE_PATHS[0] else RUNNER.MISSING_EXIT)
            return completed(stdout=payload)

        with tempfile.TemporaryDirectory() as directory:
            manifest = RUNNER.capture_state(adb, Path(directory))
            self.assertTrue(manifest["paths"][RUNNER.STATE_PATHS[0]]["present"])
            self.assertEqual(payload, (Path(directory) / "00.bin").read_bytes())
            self.assertTrue(all(not manifest["paths"][path]["present"] for path in RUNNER.STATE_PATHS[1:]))

    def test_capture_rejects_non_enoent_read_failure(self):
        def adb(*arguments, **_):
            command = arguments[-1]
            if RUNNER.PATH_PROBE in command:
                return completed()
            return completed(1, stderr=b"cat: Permission denied\n")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "バックアップを読めません"):
                RUNNER.capture_state(adb, Path(directory))

    def test_capture_rejects_old_transport_false_success_with_merged_diagnostic(self):
        def adb(*_arguments, **_):
            return completed(stdout=b"cat: files/customization-settings.json: No such file or directory\n")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "存在を確認できません"):
                RUNNER.capture_state(adb, Path(directory))

    def test_capture_rejects_read_stderr_even_with_zero_exit(self):
        calls = 0

        def adb(*arguments, **_):
            nonlocal calls
            calls += 1
            return completed() if RUNNER.PATH_PROBE in arguments[-1] else completed(stderr=b"I/O error\n")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "バックアップを読めません"):
                RUNNER.capture_state(adb, Path(directory))
        self.assertEqual(2, calls)

    def test_restore_writes_binary_with_single_safely_quoted_shell_command(self):
        payload = b"\x00original\xff\n"
        calls = []

        def adb(*arguments, input_bytes=None, **_):
            calls.append((arguments, input_bytes))
            command = arguments[-1]
            if RUNNER.PATH_PROBE in command:
                path = command_path(arguments)
                return completed(0 if path == RUNNER.STATE_PATHS[0] else RUNNER.MISSING_EXIT)
            if command.endswith(RUNNER.STATE_PATHS[0]) and " cat " in f" {command} ":
                return completed(stdout=payload)
            return completed()

        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory)
            (backup / "00.bin").write_bytes(payload)
            manifest = manifest_for({"00.bin": payload}, {RUNNER.STATE_PATHS[0]})
            RUNNER.restore_state(adb, backup, manifest)
        writes = [(arguments, data) for arguments, data in calls if data is not None]
        self.assertEqual(1, len(writes))
        arguments, data = writes[0]
        self.assertEqual(("shell", "-T"), arguments[:2])
        self.assertIn("'cat > \"$1\"'", arguments[2])
        self.assertEqual(payload, data)

    def test_restore_fails_when_written_bytes_do_not_match_backup(self):
        payload = b"original"

        def adb(*arguments, **_):
            command = arguments[-1]
            if RUNNER.PATH_PROBE in command:
                path = command_path(arguments)
                return completed(0 if path == RUNNER.STATE_PATHS[0] else RUNNER.MISSING_EXIT)
            if command.endswith(RUNNER.STATE_PATHS[0]) and " cat " in f" {command} ":
                return completed(stdout=b"changed")
            return completed()

        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory)
            (backup / "00.bin").write_bytes(payload)
            manifest = manifest_for({"00.bin": payload}, {RUNNER.STATE_PATHS[0]})
            with self.assertRaisesRegex(RuntimeError, "照合できません"):
                RUNNER.restore_state(adb, backup, manifest)

    def test_restore_rejects_backup_hash_mismatch_before_device_mutation(self):
        calls = []

        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory)
            (backup / "00.bin").write_bytes(b"corrupt")
            manifest = manifest_for({"00.bin": b"original"}, {RUNNER.STATE_PATHS[0]})
            with self.assertRaisesRegex(RuntimeError, "バックアップの照合に失敗"):
                RUNNER.restore_state(lambda *args, **kwargs: calls.append((args, kwargs)), backup, manifest)
        self.assertEqual([], calls)

    def test_quiesce_switches_away_and_disables_before_returning(self):
        selected = RUNNER.IME
        enabled = {RUNNER.IME, "com.example/.FallbackIme"}
        calls = []

        def adb(*arguments):
            nonlocal selected
            calls.append(arguments)
            if arguments[1:4] == ("settings", "get", "secure"):
                return selected + "\n"
            if arguments[1:4] == ("ime", "list", "-s"):
                return "\n".join(enabled) + "\n"
            if arguments[1:3] == ("ime", "set"):
                selected = arguments[3]
            if arguments[1:3] == ("ime", "disable"):
                enabled.discard(arguments[3])
            return ""

        with mock.patch.object(RUNNER.time, "sleep"):
            RUNNER.quiesce_ime(adb, "com.example/.FallbackIme")
        self.assertLess(calls.index(("shell", "ime", "set", "com.example/.FallbackIme")),
                        calls.index(("shell", "ime", "disable", RUNNER.IME)))


if __name__ == "__main__":
    unittest.main()

import base64
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/debug-signing.py"
spec = importlib.util.spec_from_file_location("debug_signing", SCRIPT)
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class DebugSigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.original = Path(cls.fixture.name) / "original.keystore"
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(cls.original),
                        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
                        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Signing Test"],
                       check=True, capture_output=True)
        cls.fingerprint = signing.certificate(cls.original)

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def invoke(self, directory, encoded=None, *options):
        env = dict(os.environ)
        env.pop("ANDROID_DEBUG_KEYSTORE_BASE64", None)
        env.pop("GITHUB_STEP_SUMMARY", None)
        env.pop("GITHUB_OUTPUT", None)
        if encoded is not None:
            env["ANDROID_DEBUG_KEYSTORE_BASE64"] = encoded
        pin = directory / "certificate.sha256"
        if not pin.exists():
            pin.write_text(self.fingerprint)
        return subprocess.run(["python3", str(SCRIPT), "--keystore", str(directory / "debug.keystore"),
                               "--certificate", str(pin), "--github-output", str(directory / "output"), *options],
                              env=env, capture_output=True, text=True)

    def test_missing_key_does_not_generate_a_new_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            result = self.invoke(directory)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((directory / "debug.keystore").exists())

    def test_restoring_twice_keeps_the_same_certificate(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            encoded = base64.b64encode(self.original.read_bytes()).decode()
            self.assertEqual(0, self.invoke(directory, encoded).returncode)
            self.assertEqual(0, self.invoke(directory).returncode)
            self.assertEqual(self.fingerprint, signing.certificate(directory / "debug.keystore"))
            self.assertIn("fixed=true", (directory / "output").read_text())
            self.assertEqual(0o600, (directory / "debug.keystore").stat().st_mode & 0o777)

    def test_wrong_certificate_does_not_replace_an_existing_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            previous = directory / "debug.keystore"
            previous.write_bytes(b"existing key")
            (directory / "certificate.sha256").write_text("0" * 64)
            encoded = base64.b64encode(self.original.read_bytes()).decode()
            self.assertNotEqual(0, self.invoke(directory, encoded).returncode)
            self.assertEqual(b"existing key", previous.read_bytes())

    def test_malformed_secret_is_not_echoed(self):
        with tempfile.TemporaryDirectory() as temporary:
            encoded = "not-a-base64-private-key!"
            result = self.invoke(Path(temporary), encoded)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(encoded, result.stdout + result.stderr)

    def test_temporary_testing_key_is_not_marked_for_distribution(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.assertEqual(0, self.invoke(directory, None, "--allow-temporary-for-tests").returncode)
            self.assertIn("fixed=false", (directory / "output").read_text())
            self.assertNotEqual(self.fingerprint, signing.certificate(directory / "debug.keystore"))

    def test_apk_with_a_different_signer_is_rejected(self):
        with patch.object(signing, "run", return_value=b"Signer #1 certificate SHA-256 digest: 0000\n"):
            with self.assertRaises(ValueError):
                signing.verify_apk(Path("other.apk"), self.fingerprint)


if __name__ == "__main__":
    unittest.main()

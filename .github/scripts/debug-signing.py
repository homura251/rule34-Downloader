#!/usr/bin/env python3
"""Restore the existing debug identity without publishing a throwaway-signed APK."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def run(*args):
    return subprocess.run(args, check=True, capture_output=True).stdout


def certificate(keystore):
    der = run("keytool", "-exportcert", "-keystore", str(keystore),
              "-alias", "androiddebugkey", "-storepass", "android")
    return hashlib.sha256(der).hexdigest()


def expected_certificate(path):
    value = path.read_text().strip().lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Invalid public signing certificate fingerprint")
    return value


def verify_apk(apk, expected):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    signer = Path(sdk) / "build-tools/36.0.0/apksigner" if sdk else Path("apksigner")
    report = run(str(signer), "verify", "--print-certs", str(apk)).decode()
    fingerprints = set(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", report))
    if {x.lower() for x in fingerprints} != {expected}:
        raise ValueError("APK certificate does not match the fixed debug identity; refusing distribution")
    print(f"APK signature verified: {expected}")


def prepare(args, expected):
    destination = args.keystore.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    encoded = os.environ.get("ANDROID_DEBUG_KEYSTORE_BASE64", "").strip()
    fixed = True
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        candidate = Path(temporary) / "debug.keystore"
        if encoded:
            candidate.write_bytes(base64.b64decode("".join(encoded.split()), validate=True))
        elif args.import_from:
            shutil.copyfile(args.import_from, candidate)
        elif destination.is_file():
            shutil.copyfile(destination, candidate)
        elif args.allow_temporary_for_tests:
            run("keytool", "-genkeypair", "-keystore", str(candidate), "-storepass", "android",
                "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "10000", "-dname", "CN=Temporary CI Test")
            fixed = False
        else:
            raise ValueError("Missing fixed debug key. Set ANDROID_DEBUG_KEYSTORE_BASE64 or use --import-from; no new signing identity was generated")
        os.chmod(candidate, 0o600)
        actual = certificate(candidate)
        if fixed and actual != expected:
            raise ValueError("Keystore certificate does not match signing/debug-certificate.sha256; the existing key was not replaced")
        candidate.replace(destination)
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"fixed={'true' if fixed else 'false'}\ncertificate={actual}\n")
    if fixed:
        print(f"Fixed debug signing ready: {actual}")
    else:
        notice = "ANDROID_DEBUG_KEYSTORE_BASE64 is missing: using a temporary key for internal tests only; no installable APK will be uploaded"
        print(f"::notice::{notice}" if os.environ.get("GITHUB_ACTIONS") else notice)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as output:
                output.write(f"### Debug APK unavailable\n\n{notice}.\n")
    return fixed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", type=Path, default=ROOT / ".signing/debug.keystore")
    parser.add_argument("--certificate", type=Path, default=ROOT / "signing/debug-certificate.sha256")
    parser.add_argument("--import-from", type=Path)
    parser.add_argument("--allow-temporary-for-tests", action="store_true")
    parser.add_argument("--github-output", type=Path, default=os.environ.get("GITHUB_OUTPUT"))
    parser.add_argument("--verify-apk", type=Path)
    args = parser.parse_args()
    try:
        expected = expected_certificate(args.certificate)
        if args.verify_apk:
            verify_apk(args.verify_apk, expected)
        else:
            prepare(args, expected)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Do not print subprocess output or base64 key material.
        print(f"Debug signing failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

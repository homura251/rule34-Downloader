"""Restore a signing key without writing its contents or passwords to logs."""

import base64
import os
from pathlib import Path
import tempfile
import uuid


def main():
    names = (
        "ANDROID_KEYSTORE_BASE64",
        "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_ALIAS",
        "ANDROID_KEY_PASSWORD",
    )
    missing = [name for name in names if not os.environ.get(name, "").strip()]
    if missing:
        raise SystemExit("Missing repository Actions secrets: " + ", ".join(missing))
    try:
        data = base64.b64decode(
            "".join(os.environ["ANDROID_KEYSTORE_BASE64"].split()), validate=True
        )
    except ValueError:
        raise SystemExit(
            "ANDROID_KEYSTORE_BASE64 must contain the Base64-encoded keystore"
        ) from None
    if not data:
        raise SystemExit("The signing keystore is empty")

    env_path = Path(os.environ["GITHUB_ENV"])
    fd, path = tempfile.mkstemp(
        prefix="rule34-signing-", suffix=".jks", dir=os.environ["RUNNER_TEMP"]
    )
    try:
        with os.fdopen(fd, "wb") as keystore:
            keystore.write(data)
        values = {name: os.environ[name] for name in names[1:]}
        values["ANDROID_KEYSTORE_PATH"] = path
        with env_path.open("a", encoding="utf-8") as output:
            for name, value in values.items():
                delimiter = "signing_" + uuid.uuid4().hex
                output.write(f"{name}<<{delimiter}\n{value}\n{delimiter}\n")
    except BaseException:
        Path(path).unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    main()

"""Create a private release key and sign APKs locally; never store passwords."""
import argparse
import getpass
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ALIAS = "taceto-release"


def keytool():
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin/keytool" if home else None
    result = str(candidate) if candidate and candidate.is_file() else shutil.which("keytool")
    if not result:
        raise ValueError("Install an OpenJDK or set JAVA_HOME to find keytool")
    return result


def private_key_path(path):
    path = path.expanduser().absolute()
    if path.resolve().is_relative_to(ROOT):
        raise ValueError("Keep the signing key outside the source repository")
    if path.is_symlink():
        raise ValueError("The signing key path must not be a symlink")
    return path


def password_environment(password):
    env = os.environ.copy()
    env["TACETO_SIGNING_PASSWORD"] = password
    return env


def create_key(path, password):
    path = private_key_path(path)
    if path.exists():
        raise ValueError("Refusing to overwrite an existing signing key")
    if len(password) < 12:
        raise ValueError("Use a signing password of at least 12 characters")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    previous = os.umask(0o077)
    try:
        # Exclusive reservation also protects against overwriting an existing key.
        with path.open("xb"):
            pass
        try:
            # keytool expects a nonexistent output or a valid existing keystore.
            # Generate into a fresh private temporary directory, then copy to reservation.
            with tempfile.TemporaryDirectory(dir=path.parent) as directory:
                generated = Path(directory) / "release.p12"
                subprocess.run([keytool(), "-genkeypair", "-noprompt", "-storetype", "PKCS12",
                    "-keystore", str(generated), "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "4096",
                    "-validity", "10000", "-dname", "CN=chubacca-dev, O=Taceto",
                    "-storepass:env", "TACETO_SIGNING_PASSWORD", "-keypass:env", "TACETO_SIGNING_PASSWORD"],
                    check=True, env=password_environment(password))
                path.write_bytes(generated.read_bytes())
        except BaseException:
            path.unlink(missing_ok=True)
            raise
    finally:
        os.umask(previous)
    print(f"Created signing key: {path}")
    print("Back up this file and its password separately before publishing an APK.")


def sign_apk(apk, output, keystore, apksigner, password):
    keystore = private_key_path(keystore)
    apk = apk.expanduser().resolve()
    output = output.expanduser().absolute()
    if not apk.is_file() or not keystore.is_file() or not apksigner.is_file():
        raise ValueError("APK, keystore and apksigner must exist")
    if output.exists() or output.is_symlink():
        raise ValueError("Refusing to overwrite the output APK")
    if not output.parent.is_dir():
        raise ValueError("Output directory must exist")
    # Sign to a private temporary file, verify, then publish the verified output exclusively.
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        signed = Path(directory) / "signed.apk"
        subprocess.run([str(apksigner), "sign", "--ks", str(keystore), "--ks-key-alias", ALIAS,
            "--ks-pass", "env:TACETO_SIGNING_PASSWORD", "--key-pass", "env:TACETO_SIGNING_PASSWORD",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
            "--out", str(signed), str(apk)], check=True, env=password_environment(password))
        subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(signed)], check=True)
        with output.open("xb") as target, signed.open("rb") as source:
            shutil.copyfileobj(source, target)
    print(f"Signed APK: {output}")
    print(f"SHA-256: {hashlib.sha256(output.read_bytes()).hexdigest()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create-key")
    create.add_argument("--keystore", type=Path, required=True)
    sign = commands.add_parser("sign")
    sign.add_argument("--keystore", type=Path, required=True)
    sign.add_argument("--apk", type=Path, required=True)
    sign.add_argument("--output", type=Path, required=True)
    sign.add_argument("--apksigner", type=Path, required=True,
                      help="Path to Android build-tools 34.0.0 apksigner for F-Droid compatibility")
    args = parser.parse_args()
    if not sys.stdin.isatty():
        parser.error("Run this command in an interactive terminal; passwords must not be piped")
    try:
        # Prompts only: no password CLI option, file or source-code configuration.
        password = getpass.getpass("Signing password (not displayed): ")
        if args.command == "create-key":
            if password != getpass.getpass("Repeat signing password: "):
                raise ValueError("Passwords do not match")
            create_key(args.keystore, password)
        else:
            sign_apk(args.apk, args.output, args.keystore, args.apksigner.expanduser().resolve(), password)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release operation failed: {error}\n")


if __name__ == "__main__":
    main()

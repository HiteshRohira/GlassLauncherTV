#!/usr/bin/env python3
"""Build and sign local ABI APKs; never publish or replace an existing artifact."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "glasslauncher"
NATIVE_LIBRARIES = {"libairplay_native.so", "libcrypto.so", "liboboe.so", "libc++_shared.so"}


def validate_native_payload(archive, expected_abis):
    entries = [name for name in archive.namelist() if name.startswith("lib/") and not name.endswith("/")]
    expected = {f"lib/{abi}/{name}" for abi in expected_abis for name in NATIVE_LIBRARIES}
    if len(entries) != len(set(entries)) or set(entries) != expected:
        raise SystemExit("Native libraries differ from the required ABI payload")


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], check=True, **kwargs)


def certificate(signer, apk):
    result = run([signer, "verify", "--min-sdk-version", "26", "--print-certs", apk],
                 capture_output=True, text=True)
    return re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", result.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=["baseline", "exports", "crypto-size"], default="crypto-size")
    parser.add_argument("--gradle", default=os.environ.get("GRADLE", "gradle"))
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")))
    parser.add_argument("--key-dir", type=Path, default=Path.home() / ".android-signing/glass-launcher")
    parser.add_argument("--keystore", type=Path, help="Signing keystore (defaults to glass-release.jks in --key-dir)")
    parser.add_argument("--password-file", type=Path, help="Keystore password file (defaults to password.txt in --key-dir)")
    parser.add_argument("--key-alias", default="glass", help="Alias in the supplied signing keystore")
    parser.add_argument("--output", type=Path, required=True)
    identity = parser.add_mutually_exclusive_group(required=True)
    identity.add_argument("--baseline-apk", type=Path)
    identity.add_argument("--certificate-sha256", help="Previously verified installed signing certificate")
    parser.add_argument("--include-universal", action="store_true", help="Also sign an opt-in universal comparison APK")
    parser.add_argument("--no-build", action="store_true", help="Sign an already checked build of this profile")
    args = parser.parse_args()
    text = (PROJECT / "app/build.gradle.kts").read_text()
    version = re.search(r'^\s*versionName\s*=\s*"([^\"]+)"', text, re.M).group(1)
    code = int(re.search(r"^\s*versionCode\s*=\s*(\d+)", text, re.M).group(1))
    package = re.search(r'^\s*applicationId\s*=\s*"([^\"]+)"', text, re.M).group(1)
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Output directory is not empty; choose a new output directory")
    output.mkdir(parents=True, exist_ok=True)
    tools = args.sdk / "build-tools/36.0.0"
    signer = tools / "apksigner"
    cert = certificate(signer, args.baseline_apk) if args.baseline_apk else [args.certificate_sha256]
    if not all(re.fullmatch(r"[0-9a-f]{64}", value) for value in cert):
        raise SystemExit("Invalid signing certificate digest")
    if len(cert) != 1:
        raise SystemExit("Baseline must have one verified signer")
    # Refuse existing destinations before doing an expensive build. The final
    # hard-link also provides an atomic no-replace guard against concurrent runs.
    if (output / f"manifest-{args.profile}.json").exists():
        raise SystemExit("Output manifest exists; choose a new output directory")
    for abi in ("armeabi-v7a", "arm64-v8a", "universal"):
        if (output / f"Mirage-{version}-{abi}-{args.profile}.apk").exists():
            raise SystemExit("Output exists; choose a new output directory")
    if not args.no_build:
        run([args.gradle, "--no-daemon", "--max-workers=4",
             "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8",
             f"-PnativeProfile={args.profile}", f"-PuniversalApk={str(args.include_universal).lower()}", ":app:assembleRelease",
             ":app:testReleaseUnitTest", ":app:lintRelease"], cwd=PROJECT)
    release = PROJECT / f".build/{args.profile}/app/outputs/apk/release"
    metadata = json.loads((release / "output-metadata.json").read_text())
    if metadata["applicationId"] != package:
        raise SystemExit("Unexpected package identity")
    manifest = []
    selected = [entry for entry in metadata["elements"] if entry["filters"] or args.include_universal]
    abi_names = [next((x["value"] for x in entry["filters"] if x["filterType"] == "ABI"), "universal") for entry in selected]
    expected_abis = {"armeabi-v7a", "arm64-v8a"} | ({"universal"} if args.include_universal else set())
    if len(abi_names) != len(set(abi_names)) or set(abi_names) != expected_abis:
        raise SystemExit("Expected one complete, unique ABI set")
    # Stage and verify the whole batch before publishing any signed APK.
    staged = []
    batch = tempfile.TemporaryDirectory(prefix=".verified-apks-", dir=output)
    for entry in selected:
        filters = entry["filters"]
        abi = next((x["value"] for x in filters if x["filterType"] == "ABI"), "universal")
        if abi == "universal" and not args.include_universal:
            continue
        if abi not in ("armeabi-v7a", "arm64-v8a", "universal") or entry["versionName"] != version or entry["versionCode"] != code:
            raise SystemExit("Unexpected variant metadata")
        source = (release / entry["outputFile"]).resolve()
        if source.parent != release.resolve():
            raise SystemExit("Unexpected APK output path")
        with zipfile.ZipFile(source) as archive:
            expected = {"armeabi-v7a", "arm64-v8a"} if abi == "universal" else {abi}
            validate_native_payload(archive, expected)
        badging = run([tools / "aapt2", "dump", "badging", source], capture_output=True, text=True).stdout
        if not badging.startswith(f"package: name='{package}' versionCode='{code}' versionName='{version}'"):
            raise SystemExit("Actual APK identity differs from source/metadata")
        destination = output / f"Mirage-{version}-{abi}-{args.profile}.apk"
        with tempfile.TemporaryDirectory(prefix=".signing-", dir=output) as temporary:
            signed = Path(temporary) / "signed.apk"
            run([signer, "sign", "--ks", args.keystore or args.key_dir / "glass-release.jks",
                 "--ks-key-alias", args.key_alias, "--ks-pass", f"file:{args.password_file or args.key_dir / 'password.txt'}",
                 "--v4-signing-enabled", "false", "--out", signed, source])
            if certificate(signer, signed) != cert:
                raise SystemExit("Signing certificate differs from the installed baseline")
            run([tools / "zipalign", "-c", "-P", "16", "4", signed], capture_output=True)
            verified = Path(batch.name) / destination.name
            os.link(signed, verified)
            staged.append((verified, destination))
        manifest.append({"apk": destination.name, "abi": abi, "versionCode": entry["versionCode"],
                         "bytes": verified.stat().st_size,
                         "sha256": hashlib.sha256(verified.read_bytes()).hexdigest(),
                         "certificateSha256": cert[0]})
    for verified, destination in staged:
        os.link(verified, destination)  # Atomic no-replace, including concurrent runs.
    batch.cleanup()
    with (output / f"manifest-{args.profile}.json").open("x") as stream:
        json.dump(manifest, stream, indent=2)
        stream.write("\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()

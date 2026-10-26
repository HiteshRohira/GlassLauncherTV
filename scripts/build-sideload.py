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

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "glasslauncher"


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], check=True, **kwargs)


def certificate(signer, apk):
    result = run([signer, "verify", "--min-sdk-version", "26", "--print-certs", apk],
                 capture_output=True, text=True)
    return re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", result.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=["baseline", "exports", "crypto-size"], default="exports")
    parser.add_argument("--gradle", default=os.environ.get("GRADLE", "gradle"))
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools")))
    parser.add_argument("--key-dir", type=Path, default=Path.home() / ".android-signing/glass-launcher")
    parser.add_argument("--output", type=Path, required=True)
    identity = parser.add_mutually_exclusive_group(required=True)
    identity.add_argument("--baseline-apk", type=Path)
    identity.add_argument("--certificate-sha256", help="Previously verified installed signing certificate")
    parser.add_argument("--no-build", action="store_true", help="Sign an already checked build of this profile")
    args = parser.parse_args()
    text = (PROJECT / "app/build.gradle.kts").read_text()
    version = re.search(r'^\s*versionName\s*=\s*"([^\"]+)"', text, re.M).group(1)
    output = args.output.resolve()
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
    for abi in ("armeabi-v7a", "arm64-v8a", "universal"):
        if (output / f"GlassLauncher-{version}-{abi}-{args.profile}.apk").exists():
            raise SystemExit("Output exists; choose a new output directory")
    if not args.no_build:
        run([args.gradle, "--no-daemon", "--max-workers=4",
             "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8",
             f"-PnativeProfile={args.profile}", ":app:assembleRelease",
             ":app:testReleaseUnitTest", ":app:lintRelease"], cwd=PROJECT)
    release = PROJECT / f".build/{args.profile}/app/outputs/apk/release"
    metadata = json.loads((release / "output-metadata.json").read_text())
    if metadata["applicationId"] != "tv.glass.launcher":
        raise SystemExit("Unexpected package identity")
    manifest = []
    for entry in metadata["elements"]:
        filters = entry["filters"]
        abi = next((x["value"] for x in filters if x["filterType"] == "ABI"), "universal")
        if abi not in ("armeabi-v7a", "arm64-v8a", "universal") or entry["versionName"] != version:
            raise SystemExit("Unexpected variant metadata")
        source = release / entry["outputFile"]
        destination = output / f"GlassLauncher-{version}-{abi}-{args.profile}.apk"
        with tempfile.TemporaryDirectory(prefix=".signing-", dir=output) as temporary:
            signed = Path(temporary) / "signed.apk"
            run([signer, "sign", "--ks", args.key_dir / "glass-release.jks",
                 "--ks-key-alias", "glass", "--ks-pass", f"file:{args.key_dir / 'password.txt'}",
                 "--v4-signing-enabled", "false", "--out", signed, source])
            if certificate(signer, signed) != cert:
                raise SystemExit("Signing certificate differs from the installed baseline")
            run([tools / "zipalign", "-c", "-P", "16", "4", signed], capture_output=True)
            os.link(signed, destination)  # Fails if the destination already exists.
        manifest.append({"apk": destination.name, "abi": abi, "versionCode": entry["versionCode"],
                         "bytes": destination.stat().st_size,
                         "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
                         "certificateSha256": cert[0]})
    with (output / f"manifest-{args.profile}.json").open("x") as stream:
        json.dump(manifest, stream, indent=2)
        stream.write("\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()

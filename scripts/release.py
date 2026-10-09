#!/usr/bin/env python3
"""Build, sign, verify and publish a Glass Launcher release on git.namillis.me.

Run from the repo root on an up-to-date, clean `main`:

    python3 scripts/release.py --dry-run
    python3 scripts/release.py

Steps:
  1. Check that HEAD is origin/main and the worktree is clean.
  2. Read versionName / versionCode from glasslauncher/app/build.gradle.kts.
     The release tag is v<versionName> and must not exist yet.
  3. Run unit tests, lint and the release build, signed with the release key.
  4. Check the APK: package tv.glass.launcher, the expected version, and the
     release certificate below. A build signed with any other key is refused,
     because it could not update existing installs.
  5. Create the Forgejo release (which creates the tag on HEAD) with the
     matching CHANGELOG section as notes, and attach GlassLauncher.apk.
     --dry-run stops before this step and leaves the APK in dist/.

Environment:
  GLASS_KEYSTORE        path to the PKCS12 release keystore (alias "glass")
  GLASS_KEYSTORE_PASS   keystore password; or GLASS_KEYSTORE_PASS_FILE, a
                        file whose "Keystore password:" line holds it
  FORGEJO_TOKEN         token with write:repository; or FORGEJO_TOKEN_FILE.
                        Not needed for --dry-run.
  GRADLE                Gradle 8.14.x executable (default: gradle on PATH)
  ANDROID_HOME          Android SDK with build-tools 36.0.0
  JAVA_HOME             JDK 21
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

PACKAGE = "tv.glass.launcher"
KEY_ALIAS = "glass"
CERT_SHA256 = "9dfc94a6f2072559ca3e594e8689336e47a0d65d194810277556178cdebd3316"
API = "https://git.namillis.me/api/v1/repos/namillis/GlassLauncherTV"
ASSET = "GlassLauncher.apk"
BUILD_TOOLS = "36.0.0"

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "glasslauncher"
GRADLE_FILE = PROJECT / "app" / "build.gradle.kts"
APK_OUT = PROJECT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DIST = ROOT / "dist"


def die(msg: str) -> None:
    print(f"release: {msg}", file=sys.stderr)
    sys.exit(1)


def run(cmd: list[str], stderr=subprocess.PIPE) -> str:
    return subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=stderr, text=True).stdout


def git(*args: str) -> str:
    return run(["git", "-C", str(ROOT), *args]).strip()


def secret(name: str) -> str | None:
    value = os.environ.get(name)
    if value:
        return value
    path = os.environ.get(name + "_FILE")
    if not path:
        return None
    text = Path(path).expanduser().read_text()
    m = re.search(r"^Keystore password: (\S+)$", text, re.M)
    return m.group(1) if m else text.strip()


def version() -> tuple[str, int]:
    src = GRADLE_FILE.read_text()
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', src, re.M)
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)", src, re.M)
    if not name or not code:
        die(f"could not read versionName/versionCode from {GRADLE_FILE}")
    return name.group(1), int(code.group(1))


def release_notes(ver: str) -> str:
    lines, found = [], False
    for line in (ROOT / "CHANGELOG.md").read_text().splitlines():
        if line.startswith("## ["):
            if found:
                break
            found = line.startswith(f"## [{ver}]")
        if found:
            lines.append(line)
    notes = "\n".join(lines).strip() or f"Glass Launcher {ver}."
    return notes + "\n\nInstall instructions are in the [README](https://git.namillis.me/namillis/GlassLauncherTV#install).\n"


def build(keystore: str, password: str) -> None:
    env = dict(os.environ)
    env.update({
        "ORG_GRADLE_PROJECT_android.injected.signing.store.file": keystore,
        "ORG_GRADLE_PROJECT_android.injected.signing.store.password": password,
        "ORG_GRADLE_PROJECT_android.injected.signing.store.type": "PKCS12",
        "ORG_GRADLE_PROJECT_android.injected.signing.key.alias": KEY_ALIAS,
        "ORG_GRADLE_PROJECT_android.injected.signing.key.password": password,
    })
    gradle = os.environ.get("GRADLE") or shutil.which("gradle") or die("Gradle not found; set GRADLE")
    log = DIST / "build.log"
    print(f"release: running tests, lint and the release build (log: {log})")
    with open(log, "w") as fh:
        rc = subprocess.run(
            [gradle, "--console=plain", "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8",
             "clean", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleRelease"],
            cwd=PROJECT, env=env, stdout=fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL).returncode
    if rc != 0:
        die(f"Gradle failed (exit {rc}); see {log}")


def verify(apk: Path, ver: str, code: int) -> None:
    sdk = os.environ.get("ANDROID_HOME") or die("set ANDROID_HOME")
    tools = Path(sdk) / "build-tools" / BUILD_TOOLS
    badging = run([str(tools / "aapt2"), "dump", "badging", str(apk)]).splitlines()[0]
    want = f"package: name='{PACKAGE}' versionCode='{code}' versionName='{ver}'"
    if not badging.startswith(want):
        die(f"APK reports {badging!r}, expected {want!r}")
    certs = run([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], stderr=subprocess.DEVNULL)
    digests = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs)
    if digests != [CERT_SHA256]:
        die(f"APK is signed with {digests}, not the release certificate {CERT_SHA256}")


def api(method: str, path: str, token: str, data: bytes | None = None, ctype: str = "application/json"):
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "token " + token)
    if data is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            body = r.read()
            return r.status, json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:500]


def publish(apk: Path, tag: str, ver: str, sha: str, token: str) -> None:
    st, rel = api("POST", "/releases", token, json.dumps({
        "tag_name": tag, "target_commitish": sha, "name": f"Glass Launcher {ver}",
        "body": release_notes(ver), "draft": False, "prerelease": "-" in ver}).encode())
    if st != 201:
        die(f"creating release {tag} failed: {st} {rel}")
    boundary = "----glass" + os.urandom(8).hex()
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="attachment"; filename="{ASSET}"\r\n'
            "Content-Type: application/vnd.android.package-archive\r\n\r\n").encode()
    body += apk.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    st, asset = api("POST", f"/releases/{rel['id']}/assets?name={ASSET}", token, body,
                    f"multipart/form-data; boundary={boundary}")
    if st != 201:
        die(f"release {tag} was created but uploading {ASSET} failed: {st} {asset}. "
            "Upload it by hand on the release page.")
    print(f"release: published {rel['html_url']}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Build and publish a Glass Launcher release.")
    ap.add_argument("--dry-run", action="store_true", help="build and verify only; publish nothing")
    args = ap.parse_args()

    run(["git", "-C", str(ROOT), "fetch", "--quiet", "--tags", "origin"])
    if git("status", "--porcelain"):
        die("the worktree has uncommitted changes")
    sha = git("rev-parse", "HEAD")
    if sha != git("rev-parse", "origin/main"):
        if not args.dry_run:
            die("HEAD is not origin/main; check out main and pull first")
        print("release: note: HEAD is not origin/main, so only a dry run is possible")

    ver, code = version()
    tag = f"v{ver}"
    if git("ls-remote", "--tags", "origin", f"refs/tags/{tag}"):
        if not args.dry_run:
            die(f"tag {tag} already exists; bump versionName and versionCode first")
        print(f"release: note: {tag} already exists, so only a dry run is possible")

    keystore = os.environ.get("GLASS_KEYSTORE") or die("set GLASS_KEYSTORE")
    password = secret("GLASS_KEYSTORE_PASS") or die("set GLASS_KEYSTORE_PASS or GLASS_KEYSTORE_PASS_FILE")
    token = None if args.dry_run else (secret("FORGEJO_TOKEN") or die("set FORGEJO_TOKEN or FORGEJO_TOKEN_FILE"))

    DIST.mkdir(exist_ok=True)
    build(keystore, password)
    apk = DIST / ASSET
    shutil.copyfile(APK_OUT, apk)
    verify(apk, ver, code)
    digest = run(["sha256sum", str(apk)]).split()[0]
    print(f"release: {PACKAGE} {ver} ({code}) at {sha[:7]}, {apk.stat().st_size} bytes, sha256 {digest}")

    if args.dry_run:
        print("release: dry run, nothing published")
        return
    publish(apk, tag, ver, sha, token)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Compile real UxPlay crypto/SRP/RAOP against explicitly selected libcrypto.

Android builds are COMPILE ONLY. No adb, emulator, Gradle, cleanup, or signing.
Pass profile-specific generated include directory and its libcrypto.so. Metadata
records exact inputs/commands/hashes. Host execution is optional and explicitly
separate; it cannot validate Android's OpenSSL binary or the ARMv7 runtime.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--abi', choices=['host', 'armeabi-v7a', 'arm64-v8a'], required=True)
    p.add_argument('--openssl-include', type=Path, required=True)
    p.add_argument('--libcrypto', type=Path, required=True)
    p.add_argument('--expected-version', default='3.4.4')
    p.add_argument('--ndk', type=Path, default=Path('/opt/homebrew/share/android-commandlinetools/ndk/27.0.12077973'))
    p.add_argument('--api', type=int, default=26)
    p.add_argument('--out-dir', type=Path, required=True)
    p.add_argument('--run-host', action='store_true')
    args = p.parse_args()
    if args.run_host and args.abi != 'host':
        p.error('--run-host requires --abi host; Android execution is intentionally unsupported')
    inc = args.openssl_include.resolve(strict=True)
    crypto = args.libcrypto.resolve(strict=True)
    version = (inc/'openssl/opensslv.h').read_text()
    if not re.search(r'OPENSSL_VERSION_STR\s+"'+re.escape(args.expected_version)+r'"', version):
        p.error('selected headers do not match --expected-version')
    for header in ['opensslconf.h', 'configuration.h']:
        if not (inc/'openssl'/header).is_file():
            p.error('missing generated header: '+header)
    here = Path(__file__).resolve().parent
    lib = here.parents[1]/'main/cpp/third_party/UxPlay/lib'
    sources = [here/'test_crypto.c'] + [lib/(name+'.c') for name in
               ['crypto', 'srp', 'utils', 'raop_buffer', 'byteutils', 'logger']]
    out = args.out_dir.resolve(); out.mkdir(parents=True, exist_ok=True)
    binary = out/('test_crypto-'+args.abi)
    if binary.exists():
        p.error('refusing to overwrite existing binary; use a fresh --out-dir')
    flags = ['-std=gnu11', '-g', '-O1', '-fno-omit-frame-pointer', '-Wall', '-Wextra',
             '-Wno-unused-parameter', '-I'+str(inc), '-I'+str(lib)]
    if args.abi == 'host':
        cc = 'clang'
        flags += ['-fsanitize=address,undefined', '-fno-sanitize-recover=undefined']
        linkflags = ['-Wl,-rpath,'+str(crypto.parent)]
    else:
        triple = {'armeabi-v7a': 'armv7a-linux-androideabi', 'arm64-v8a': 'aarch64-linux-android'}[args.abi]
        cc = str(args.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin'/(triple+str(args.api)+'-clang'))
        linkflags = ['-Wl,-rpath,$ORIGIN', '-Wl,--no-undefined', '-Wl,-z,max-page-size=16384']
    cmd = [cc, *flags, *map(str, sources), str(crypto), *linkflags, '-o', str(binary)]
    metadata = {'abi': args.abi, 'api': args.api if args.abi != 'host' else None,
                'expectedOpenSSLVersion': args.expected_version,
                'libcrypto': str(crypto), 'libcryptoSha256': sha(crypto),
                'inputs': {str(s): sha(s) for s in sources},
                'headers': {str(inc/'openssl'/s): sha(inc/'openssl'/s) for s in
                            ['opensslv.h', 'opensslconf.h', 'configuration.h']},
                'command': cmd, 'androidExecuted': False}
    configdata = inc.parent/'configdata.pm'
    if configdata.is_file():
        metadata['configdata'] = {'path': str(configdata), 'sha256': sha(configdata)}
    print(shlex.join(cmd), flush=True)
    build = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (out/'compile.log').write_text(build.stdout)
    print(build.stdout, end='')
    metadata['compileExitCode'] = build.returncode
    if sha(crypto) != metadata['libcryptoSha256']:
        raise RuntimeError('libcrypto changed during compile; rerun against a stable build profile')
    if build.returncode == 0:
        metadata['binarySha256'] = sha(binary)
    (out/'metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
    if build.returncode:
        return build.returncode
    if args.run_host:
        # Never delete scratch PEMs. No user/system key paths are accepted.
        scratch = Path(tempfile.mkdtemp(prefix='pem-', dir=out)); pem = scratch/'test-key.pem'
        run = subprocess.run([str(binary), str(pem)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (out/'run.log').write_text(run.stdout); print(run.stdout, end='')
        metadata['hostRunExitCode'] = run.returncode
        match = re.search(r'^PEM public: ([0-9a-f]{64})$', run.stdout, re.M)
        if match and pem.exists():
            reload_run = subprocess.run([str(binary), '--reload', str(pem), match[1]],
                                        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (out/'reload.log').write_text(reload_run.stdout); print(reload_run.stdout, end='')
            metadata['hostReloadExitCode'] = reload_run.returncode
        (out/'metadata.json').write_text(json.dumps(metadata, indent=2)+'\n')
        return run.returncode or metadata.get('hostReloadExitCode', 2)
    print('Compiled only; no Android execution performed.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

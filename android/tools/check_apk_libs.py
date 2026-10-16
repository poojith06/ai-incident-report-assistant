#!/usr/bin/env python3
"""Host-side check of the native libraries inside the debug APK (no device needed).

For every lib/arm64-v8a/*.so it runs `llvm-readelf -d` (NDK) and verifies:
  * no two libraries share a SONAME (the ggml clash that broke System.loadLibrary("whisper_jni"));
  * every DT_NEEDED is another library of the APK or a system library of the allow-list below;
  * the Hexagon DSP-side libraries (libggml-htp-v*.so, libQnnHtp*Skel.so) are listed but not checked: they are not loaded by the Android linker.
Exit code 0 = ok, 1 = a problem (printed).

  python tools/check_apk_libs.py [app/build/outputs/apk/debug/app-debug.apk] [--readelf <path to llvm-readelf>]
"""
import argparse
import collections
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

SYSTEM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so", "libjnigraphics.so",
    "libOpenCL.so", "libcdsprpc.so",  # vendor libraries, declared with <uses-native-library> in the manifest
}
DSP_SIDE = re.compile(r"^lib(ggml-htp-v\d+|QnnHtpV\d+Skel)\.so$")


def find_readelf(arg):
    if arg:
        return arg
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk")
    hits = sorted(glob.glob(os.path.join(sdk, "ndk", "*", "toolchains", "llvm", "prebuilt", "*", "bin", "llvm-readelf*")))
    if not hits:
        sys.exit("llvm-readelf not found: pass --readelf")
    return hits[-1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", nargs="?", default="app/build/outputs/apk/debug/app-debug.apk")
    ap.add_argument("--readelf")
    a = ap.parse_args()
    readelf = find_readelf(a.readelf)
    problems = []
    libs = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(a.apk) as z:
        for name in z.namelist():
            if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
                path = os.path.join(tmp, os.path.basename(name))
                with open(path, "wb") as f:
                    f.write(z.read(name))
                out = subprocess.run([readelf, "-d", path], capture_output=True, text=True).stdout
                soname = re.search(r"SONAME.*\[(.*)\]", out)
                needed = re.findall(r"NEEDED.*\[(.*)\]", out)
                libs[os.path.basename(name)] = (soname.group(1) if soname else None, needed, z.getinfo(name).file_size)
        other_abis = sorted({n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.count("/") >= 2} - {"arm64-v8a"})
        if other_abis:
            problems.append(f"unexpected ABIs in the APK: {other_abis}")
    sonames = collections.defaultdict(list)
    for f, (so, needed, size) in sorted(libs.items()):
        sonames[so or f].append(f)
        print(f"{f:28s} {size/1e6:7.1f} MB  soname={so}  needed={needed}")
    for so, files in sonames.items():
        if len(files) > 1:
            problems.append(f"soname {so} is used by {files}")
    for f, (so, needed, size) in libs.items():
        if DSP_SIDE.match(f):
            continue
        for n in needed:
            if n not in libs and n not in SYSTEM:
                problems.append(f"{f}: DT_NEEDED {n} is neither in the APK nor an allowed system library")
    print()
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print(" -", p)
        sys.exit(1)
    print(f"OK: {len(libs)} libraries, all sonames unique, all DT_NEEDED resolve")


if __name__ == "__main__":
    main()

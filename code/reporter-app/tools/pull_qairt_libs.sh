#!/bin/sh
# Pulls the QAIRT runtime libs that GenieLlmEngine needs from a connected board into app/src/main/jniLibs/arm64-v8a.
# Usage: tools/pull_qairt_libs.sh [adb-serial]   (see doc/DEVICE_SETUP.md)
S=${1:-1d9c8f40}; A=/data/local/tmp/qairt/lib
D="$(dirname "$0")/../app/src/main/jniLibs/arm64-v8a"; mkdir -p "$D"
for f in aarch64-android/libGenie.so aarch64-android/libQnnHtp.so aarch64-android/libQnnHtpV79Stub.so \
         aarch64-android/libQnnSystem.so hexagon-v79/unsigned/libQnnHtpV79Skel.so; do
  adb -s "$S" pull "$A/$f" "$D/" || exit 1
done

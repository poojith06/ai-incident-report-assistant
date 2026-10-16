#!/bin/sh
# Downloads the sherpa-onnx Android prebuilt (v1.13.8, Apache-2.0) and copies the two arm64-v8a libraries the TTS needs
# (libsherpa-onnx-jni.so, libonnxruntime.so) into app/src/main/jniLibs/arm64-v8a (git-ignored). Needs curl and tar (bzip2).
# libsherpa-onnx-c-api.so / libsherpa-onnx-cxx-api.so are NOT needed (they are for non-JNI users). Neither library clashes with the
# existing jniLibs (llama/ggml, QAIRT); whisper is built from source and statically linked. Check: python tools/check_apk_libs.py
# v1.13.8 includes OfflineTts emotion_id support (GenerationConfig.extra["emotion_id"], sherpa-onnx PR 3849) that the Rasa model needs.
# The Kotlin API copy in app/src/main/java/com/k2fsa/sherpa/onnx/Tts.kt must come from the same tag.
# Usage: tools/pull_sherpa_libs.sh    (see doc/TTS.md and doc/DEVICE_SETUP.md section 9)
V=v1.13.8
ROOT="$(dirname "$0")/.."
L="$ROOT/app/src/main/jniLibs/arm64-v8a"
W="$ROOT/build/sherpa"
mkdir -p "$L" "$W"
if [ ! -f "$W/sherpa-onnx-$V-android.tar.bz2" ]; then
  curl -sS -L --fail -o "$W/sherpa-onnx-$V-android.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/$V/sherpa-onnx-$V-android.tar.bz2" || exit 1
fi
tar -xjf "$W/sherpa-onnx-$V-android.tar.bz2" -C "$W" ./jniLibs/arm64-v8a/libsherpa-onnx-jni.so ./jniLibs/arm64-v8a/libonnxruntime.so || exit 1
cp "$W/jniLibs/arm64-v8a/libsherpa-onnx-jni.so" "$W/jniLibs/arm64-v8a/libonnxruntime.so" "$L/" || exit 1
ls -l "$L/libsherpa-onnx-jni.so" "$L/libonnxruntime.so"

#!/bin/sh
# Pulls the llama.cpp b11552 Snapdragon-build libraries and headers that LlamaGemmaEngine needs from a connected board
# (READ-ONLY adb pull from /data/local/tmp/models/llama_snap) into app/src/main/jniLibs/arm64-v8a and app/src/main/cpp/third_party/llama/include.
# Usage: tools/pull_llama_libs.sh [adb-serial]   (see doc/DEVICE_SETUP.md section 8)
# Only the Hexagon v79 skel is shipped (SM8750 / QIDK). libllama-common.so (67 MB) and the llama-* tool libs are NOT needed:
# libllama / libmtmd do not depend on them. libggml.so has DT_NEEDED on libggml-rpc.so, so it must ship too (it is never used: no sockets).
S=${1:-1d9c8f40}; A=/data/local/tmp/models/llama_snap
ROOT="$(dirname "$0")/.."
L="$ROOT/app/src/main/jniLibs/arm64-v8a"; H="$ROOT/app/src/main/cpp/third_party/llama/include"
mkdir -p "$L" "$H"
for f in libllama.so libmtmd.so libggml.so libggml-base.so libggml-cpu.so libggml-rpc.so libggml-opencl.so libggml-hexagon.so libggml-htp-v79.so; do
  adb -s "$S" pull "$A/lib/$f" "$L/" || exit 1
done
for f in llama.h llama-cpp.h mtmd.h mtmd-helper.h ggml.h ggml-alloc.h ggml-backend.h ggml-cpu.h ggml-opt.h gguf.h ggml-cpp.h; do
  adb -s "$S" pull "$A/include/$f" "$H/" || exit 1
done

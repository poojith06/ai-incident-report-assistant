cmake_minimum_required(VERSION 3.22.1)
project(genie_jni LANGUAGES CXX)

# libGenie.so is a prebuilt QAIRT library pulled from the board into jniLibs (tools/pull_qairt_libs.sh).
add_library(Genie SHARED IMPORTED)
set_target_properties(Genie PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libGenie.so)

add_library(genie_jni SHARED genie_jni.cpp)
target_include_directories(genie_jni PRIVATE ${CMAKE_SOURCE_DIR}/include)
target_link_libraries(genie_jni Genie log android)

# ---- whisper.cpp (CPU stopgap ASR; NOT the NPU Whisper, see doc/MODEL_INTEGRATION.md) ---------------------------------
# Vendored v1.7.6 (MIT), ggml CPU backend only: no OpenMP, no BLAS, no GPU backend, no curl/network, no examples.
# Wrapped in a function so the flag overrides below stay local to the whisper/ggml targets (genie_jni is unaffected).
function(add_whisper_cpp)
    set(BUILD_SHARED_LIBS OFF)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
    # FORCE in the cache: ggml's option(BUILD_SHARED_LIBS) would otherwise default to ON on Android and build libggml*.so / libwhisper.so,
    # whose sonames clash with the llama.cpp libggml*.so prebuilts (different ggml versions) packaged next to them.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
    set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    # Oryon (SM8750) is armv8.7+: NEON, fp16 arithmetic, dot product (all baseline-safe for this board).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    # Gradle debug builds default to -O0, far too slow for ASR: always optimise the whisper/ggml sources.
    set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
    set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")
    add_subdirectory(third_party/whisper.cpp ${CMAKE_BINARY_DIR}/whisper_cpp EXCLUDE_FROM_ALL)
endfunction()
add_whisper_cpp()

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_options(whisper_jni PRIVATE -O3)
target_include_directories(whisper_jni PRIVATE ${CMAKE_SOURCE_DIR}/third_party/whisper.cpp/include ${CMAKE_SOURCE_DIR}/third_party/whisper.cpp/ggml/include)
target_link_libraries(whisper_jni whisper log android)
# whisper's static ggml must not export ggml_* symbols: libllama_jni.so loads the llama.cpp libggml*.so (another ggml version) in the same process.
target_link_options(whisper_jni PRIVATE -Wl,--exclude-libs,ALL)

# ---- llama.cpp b11552 Snapdragon build (Gemma 4 E2B: LLM on the Hexagon NPU, mmproj on the Adreno GPU) --------------------
# Prebuilt shared libs pulled from the board into jniLibs (tools/pull_llama_libs.sh); headers in third_party/llama/include.
# Only the C APIs of libllama / libmtmd / libggml-base are used. Their DT_NEEDED chain (libggml, -cpu, -rpc, -opencl, -hexagon) must
# be listed too so lld can resolve it. Every soname here is distinct from the QAIRT libs and from whisper (static ggml).
set(LLAMA_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
foreach(lib llama mtmd ggml ggml-base ggml-cpu ggml-rpc ggml-opencl ggml-hexagon)
    add_library(prebuilt_${lib} SHARED IMPORTED)
    set_target_properties(prebuilt_${lib} PROPERTIES IMPORTED_LOCATION ${LLAMA_LIB_DIR}/lib${lib}.so)
endforeach()

add_library(llama_jni SHARED llama_jni.cpp)
target_include_directories(llama_jni PRIVATE ${CMAKE_SOURCE_DIR}/third_party/llama/include)
# Only the Java_* entry points are exported (jni.h marks them default-visible); the static libc++ stays private to this library.
target_compile_options(llama_jni PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(llama_jni PRIVATE -Wl,--exclude-libs,ALL)
target_link_libraries(llama_jni
    prebuilt_mtmd prebuilt_llama prebuilt_ggml prebuilt_ggml-base prebuilt_ggml-cpu prebuilt_ggml-rpc prebuilt_ggml-opencl prebuilt_ggml-hexagon
    log android)

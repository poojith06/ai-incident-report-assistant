// JNI wrapper around the prebuilt llama.cpp b11552 Snapdragon build (libllama.so, libmtmd.so, libggml*.so) for Gemma 4 E2B:
// text, audio and image input; the LLM runs on the Hexagon NPU (device HTP0), the mmproj (audio/image encoder) on the Adreno GPU
// (device GPUOpenCL), exactly what `llama-server --device HTP0 -mmdev GPUOpenCL -ngl 99 ...` does. Only C APIs are used.
// Class: com.example.test_app.ai.llama.LlamaNative. One session = one model + one context; the Kotlin side serialises calls
// on one thread, only nativeAbort may be called from another thread (it only stores an atomic flag).
// Every nativeGenerate call is independent: the KV memory and the sampler are reset first.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "ggml.h"
#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;

int64_t msSince(Clock::time_point t0) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count();
}

enum StopReason : jlong { STOP_EOG = 0, STOP_MAX_TOKENS = 1, STOP_ABORTED = 2 };

struct Session {
    llama_model *model = nullptr;
    llama_context *lctx = nullptr;
    mtmd_context *mctx = nullptr;
    llama_sampler *smpl = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_batch batch{};
    bool batchAllocated = false;
    int nCtx = 0;
    int nBatch = 0;
    std::atomic<bool> abort{false};
    // statistics of the last nativeGenerate call
    jlong promptTokens = 0, promptPositions = 0, generatedTokens = 0, prefillMs = 0, decodeMs = 0, stop = 0, mediaChunks = 0;
};

void throwRuntime(JNIEnv *env, const std::string &msg) {
    LOGE("%s", msg.c_str());
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg.c_str());
}

void ggmlLogToAndroid(enum ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR   ? ANDROID_LOG_ERROR
               : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
               : level == GGML_LOG_LEVEL_INFO  ? ANDROID_LOG_INFO
                                               : ANDROID_LOG_DEBUG;
    __android_log_write(prio, TAG, text);
}

bool abortCallback(void *data) { return static_cast<std::atomic<bool> *>(data)->load(std::memory_order_relaxed); }

// ---- UTF conversion: JNI strings are modified UTF-8; llama.cpp wants standard UTF-8 ------------------------------------

std::string jstringToUtf8(JNIEnv *env, jstring s) {
    const jsize n = env->GetStringLength(s);
    const jchar *u = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(static_cast<size_t>(n) * 3);
    for (jsize i = 0; i < n; i++) {
        uint32_t c = u[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18));
            out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    env->ReleaseStringChars(s, u);
    return out;
}

// Decodes standard UTF-8 (tolerating invalid bytes: they become U+FFFD) into a Java string.
jstring utf8ToJstring(JNIEnv *env, const std::string &s) {
    std::vector<jchar> u;
    u.reserve(s.size());
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char b = static_cast<unsigned char>(s[i]);
        uint32_t c = 0xFFFD;
        size_t len = 1;
        if (b < 0x80) {
            c = b;
        } else if (b >= 0xC2 && b <= 0xDF && i + 1 < n && (s[i + 1] & 0xC0) == 0x80) {
            c = ((b & 0x1F) << 6) | (s[i + 1] & 0x3F);
            len = 2;
        } else if (b >= 0xE0 && b <= 0xEF && i + 2 < n && (s[i + 1] & 0xC0) == 0x80 && (s[i + 2] & 0xC0) == 0x80) {
            const uint32_t v = ((b & 0x0F) << 12) | ((s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F);
            if (v >= 0x800 && !(v >= 0xD800 && v <= 0xDFFF)) {
                c = v;
                len = 3;
            }
        } else if (b >= 0xF0 && b <= 0xF4 && i + 3 < n && (s[i + 1] & 0xC0) == 0x80 && (s[i + 2] & 0xC0) == 0x80 &&
                   (s[i + 3] & 0xC0) == 0x80) {
            const uint32_t v = ((b & 0x07) << 18) | ((s[i + 1] & 0x3F) << 12) | ((s[i + 2] & 0x3F) << 6) | (s[i + 3] & 0x3F);
            if (v >= 0x10000 && v <= 0x10FFFF) {
                c = v;
                len = 4;
            }
        }
        if (c >= 0x10000) {
            c -= 0x10000;
            u.push_back(static_cast<jchar>(0xD800 + (c >> 10)));
            u.push_back(static_cast<jchar>(0xDC00 + (c & 0x3FF)));
        } else {
            u.push_back(static_cast<jchar>(c));
        }
        i += len;
    }
    return env->NewString(u.data(), static_cast<jsize>(u.size()));
}

std::string describeDevices() {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(d, &free, &total);
        const char *type = "?";
        switch (ggml_backend_dev_type(d)) {
            case GGML_BACKEND_DEVICE_TYPE_CPU: type = "CPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_GPU: type = "GPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU: type = "IGPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_ACCEL: type = "ACCEL"; break;
            default: break;
        }
        out += std::string(ggml_backend_dev_name(d)) + "|" + ggml_backend_dev_description(d) + "|" + type + "|" +
               std::to_string(free) + "|" + std::to_string(total) + "\n";
    }
    return out;
}

// Same environment as genie_jni: the Hexagon DSP finds its skel (libggml-htp-v79.so, like libQnnHtpV79Skel.so) via ADSP_LIBRARY_PATH.
void prepareEnvironment(const std::string &libDir) {
    const std::string adsp = libDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
    setenv("LD_LIBRARY_PATH", libDir.c_str(), 1);
    llama_log_set(ggmlLogToAndroid, nullptr);
    mtmd_helper_log_set(ggmlLogToAndroid, nullptr);
    llama_backend_init();
    ggml_backend_load_all(); // no-op for the statically linked Snapdragon build; the llama-server binaries call it too
}

// "none" = CPU only (no offload device). Otherwise the exact ggml device name (HTP0, GPUOpenCL).
ggml_backend_dev_t findDevice(const std::string &name, std::string &err) {
    ggml_backend_dev_t d = ggml_backend_dev_by_name(name.c_str());
    if (d == nullptr) err = "ggml device '" + name + "' not found; available:\n" + describeDevices();
    return d;
}

struct ChunksDeleter { void operator()(mtmd_input_chunks *c) const { mtmd_input_chunks_free(c); } };
struct BitmapDeleter { void operator()(mtmd_bitmap *b) const { mtmd_bitmap_free(b); } };

void freeSession(Session *s) {
    if (s == nullptr) return;
    if (s->smpl) llama_sampler_free(s->smpl);
    if (s->batchAllocated) llama_batch_free(s->batch);
    if (s->mctx) mtmd_free(s->mctx);
    if (s->lctx) llama_free(s->lctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_test_1app_ai_llama_LlamaNative_nativeListDevices(JNIEnv *env, jobject, jstring libDir) {
    prepareEnvironment(jstringToUtf8(env, libDir));
    return utf8ToJstring(env, describeDevices());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_test_1app_ai_llama_LlamaNative_nativeCreate(
        JNIEnv *env, jobject, jstring modelPath, jstring mmprojPath, jstring libDir, jstring llmDevice, jstring mmprojDevice,
        jint nCtx, jint nThreads, jint nGpuLayers) {
    const std::string model = jstringToUtf8(env, modelPath);
    const std::string mmproj = jstringToUtf8(env, mmprojPath);
    const std::string llmDev = jstringToUtf8(env, llmDevice);
    const std::string mmDev = jstringToUtf8(env, mmprojDevice);
    prepareEnvironment(jstringToUtf8(env, libDir));
    LOGI("devices:\n%s", describeDevices().c_str());

    std::string err;
    ggml_backend_dev_t llmDevPtr = nullptr, mmDevPtr = nullptr;
    if (llmDev != "none") {
        llmDevPtr = findDevice(llmDev, err);
        if (llmDevPtr == nullptr) { throwRuntime(env, err); return 0; }
    }
    if (mmDev != "none") {
        mmDevPtr = findDevice(mmDev, err);
        if (mmDevPtr == nullptr) { throwRuntime(env, err); return 0; }
    }

    std::unique_ptr<Session, decltype(&freeSession)> s(new Session(), freeSession);
    s->nCtx = nCtx;

    // --device HTP0 -ngl 99: the only offload device is the NPU; layers that it cannot take stay on the CPU, as in llama-server.
    ggml_backend_dev_t devices[2] = {llmDevPtr, nullptr};
    llama_model_params mp = llama_model_default_params();
    mp.devices = devices; // {nullptr} = no offload device = CPU
    mp.n_gpu_layers = llmDevPtr != nullptr ? nGpuLayers : 0;
    const auto t0 = Clock::now();
    s->model = llama_model_load_from_file(model.c_str(), mp);
    if (s->model == nullptr) { throwRuntime(env, "llama_model_load_from_file failed for " + model); return 0; }
    LOGI("model loaded in %lld ms: %s", static_cast<long long>(msSince(t0)), model.c_str());
    s->vocab = llama_model_get_vocab(s->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = 2048;   // llama-server defaults
    cp.n_ubatch = 512;
    cp.n_seq_max = 1;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.swa_full = false; // llama-server default (common_params::swa_full = false)
    cp.offload_kqv = true;
    cp.op_offload = true;
    s->nBatch = static_cast<int>(cp.n_batch);
    s->lctx = llama_init_from_model(s->model, cp);
    if (s->lctx == nullptr) { throwRuntime(env, "llama_init_from_model failed (n_ctx=" + std::to_string(nCtx) + ")"); return 0; }
    llama_set_abort_callback(s->lctx, abortCallback, &s->abort);

    // --mmproj <file> -mmdev GPUOpenCL (mtmd-cli / llama-server init_vision_context).
    mtmd_context_params mcp = mtmd_context_params_default();
    mcp.use_gpu = mmDevPtr != nullptr;
    mcp.device = mmDevPtr;
    mcp.print_timings = false;
    mcp.n_threads = nThreads;
    mcp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    mcp.warmup = true;
    const auto t1 = Clock::now();
    s->mctx = mtmd_init_from_file(mmproj.c_str(), s->model, mcp);
    if (s->mctx == nullptr) { throwRuntime(env, "mtmd_init_from_file failed for " + mmproj); return 0; }
    LOGI("mmproj loaded in %lld ms (device %s): vision=%d audio=%d audio_rate=%d", static_cast<long long>(msSince(t1)),
         mmDev.c_str(), mtmd_support_vision(s->mctx), mtmd_support_audio(s->mctx), mtmd_get_audio_sample_rate(s->mctx));

    // temperature 0 = greedy argmax (what llama-server does for "temperature": 0).
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    s->smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(s->smpl, llama_sampler_init_greedy());

    s->batch = llama_batch_init(1, 0, 1);
    s->batchAllocated = true;
    return reinterpret_cast<jlong>(s.release());
}

// kinds[i]: 0 = image (next entry of `images`, size in imageDims[2k], imageDims[2k+1]), 1 = audio (next entry of `audio`).
// The i-th media marker of the prompt binds to the i-th entry of kinds. Returns the generated text (special tokens rendered).
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_test_1app_ai_llama_LlamaNative_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jintArray kinds, jobjectArray audio, jobjectArray images,
        jintArray imageDims, jint maxTokens) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) { throwRuntime(env, "llama session is closed"); return nullptr; }
    s->abort.store(false);
    s->promptTokens = s->promptPositions = s->generatedTokens = s->prefillMs = s->decodeMs = s->mediaChunks = 0;
    s->stop = STOP_EOG;

    const std::string text = jstringToUtf8(env, prompt);

    // fresh conversation: no history leaks between calls
    llama_memory_clear(llama_get_memory(s->lctx), true);
    llama_sampler_reset(s->smpl);

    std::vector<std::unique_ptr<mtmd_bitmap, BitmapDeleter>> bitmaps;
    {
        const jsize nKinds = kinds != nullptr ? env->GetArrayLength(kinds) : 0;
        std::vector<jint> k(static_cast<size_t>(nKinds));
        if (nKinds > 0) env->GetIntArrayRegion(kinds, 0, nKinds, k.data());
        std::vector<jint> dims;
        if (imageDims != nullptr) {
            dims.resize(static_cast<size_t>(env->GetArrayLength(imageDims)));
            if (!dims.empty()) env->GetIntArrayRegion(imageDims, 0, static_cast<jsize>(dims.size()), dims.data());
        }
        jsize iAudio = 0, iImage = 0;
        for (jsize i = 0; i < nKinds; i++) {
            if (k[i] == 1) {
                if (!mtmd_support_audio(s->mctx)) { throwRuntime(env, "this mmproj does not support audio input"); return nullptr; }
                auto arr = static_cast<jfloatArray>(env->GetObjectArrayElement(audio, iAudio++));
                const jsize n = env->GetArrayLength(arr);
                jfloat *data = env->GetFloatArrayElements(arr, nullptr);
                bitmaps.emplace_back(mtmd_bitmap_init_from_audio(static_cast<size_t>(n), data));
                env->ReleaseFloatArrayElements(arr, data, JNI_ABORT);
                env->DeleteLocalRef(arr);
            } else {
                if (!mtmd_support_vision(s->mctx)) { throwRuntime(env, "this mmproj does not support image input"); return nullptr; }
                auto arr = static_cast<jbyteArray>(env->GetObjectArrayElement(images, iImage));
                const jint w = dims[static_cast<size_t>(2 * iImage)], h = dims[static_cast<size_t>(2 * iImage + 1)];
                iImage++;
                const jsize n = env->GetArrayLength(arr);
                if (w <= 0 || h <= 0 || static_cast<int64_t>(n) != static_cast<int64_t>(w) * h * 3) {
                    throwRuntime(env, "image bytes (" + std::to_string(n) + ") do not match " + std::to_string(w) + "x" + std::to_string(h) + " RGB");
                    return nullptr;
                }
                jbyte *data = env->GetByteArrayElements(arr, nullptr);
                bitmaps.emplace_back(mtmd_bitmap_init(static_cast<uint32_t>(w), static_cast<uint32_t>(h), reinterpret_cast<const unsigned char *>(data)));
                env->ReleaseByteArrayElements(arr, data, JNI_ABORT);
                env->DeleteLocalRef(arr);
            }
            if (!bitmaps.back()) { throwRuntime(env, "could not create an mtmd bitmap"); return nullptr; }
        }
    }
    std::vector<const mtmd_bitmap *> bitmapPtrs;
    for (const auto &b : bitmaps) bitmapPtrs.push_back(b.get());

    // The chat template (rendered by Kotlin) has no BOS: it is added here (add_special), special tokens in the text are parsed.
    std::unique_ptr<mtmd_input_chunks, ChunksDeleter> chunks(mtmd_input_chunks_init());
    mtmd_input_text input{text.c_str(), text.size(), /*add_special*/ true, /*parse_special*/ true};
    const int32_t tok = mtmd_tokenize(s->mctx, chunks.get(), &input, bitmapPtrs.data(), bitmapPtrs.size());
    if (tok != 0) {
        throwRuntime(env, tok == 1 ? "number of media markers in the prompt does not match the number of media items"
                                   : "media preprocessing failed (mtmd_tokenize returned " + std::to_string(tok) + ")");
        return nullptr;
    }
    s->promptTokens = static_cast<jlong>(mtmd_helper_get_n_tokens(chunks.get()));
    s->promptPositions = static_cast<jlong>(mtmd_helper_get_n_pos(chunks.get()));
    if (s->promptPositions + maxTokens > s->nCtx) {
        throwRuntime(env, "prompt (" + std::to_string(s->promptPositions) + " positions) + max_tokens (" + std::to_string(maxTokens) +
                              ") does not fit the context (" + std::to_string(s->nCtx) + ")");
        return nullptr;
    }

    // prefill = media encode (GPU) + prompt decode (NPU); the abort flag is honoured between chunks
    llama_pos nPast = 0;
    const auto tPrefill = Clock::now();
    const size_t nChunks = mtmd_input_chunks_size(chunks.get());
    for (size_t i = 0; i < nChunks; i++) {
        if (s->abort.load()) { s->stop = STOP_ABORTED; break; }
        const mtmd_input_chunk *chunk = mtmd_input_chunks_get(chunks.get(), i);
        if (mtmd_input_chunk_get_type(chunk) != MTMD_INPUT_CHUNK_TYPE_TEXT) s->mediaChunks++;
        llama_pos newPast = nPast;
        const int32_t r = mtmd_helper_eval_chunk_single(s->mctx, s->lctx, chunk, nPast, 0, s->nBatch, i + 1 == nChunks, &newPast);
        if (r != 0) {
            if (s->abort.load()) { s->stop = STOP_ABORTED; break; }
            throwRuntime(env, "evaluating prompt chunk " + std::to_string(i) + " failed (" + std::to_string(r) + ")");
            return nullptr;
        }
        nPast = newPast;
    }
    s->prefillMs = msSince(tPrefill);
    if (s->stop == STOP_ABORTED) return utf8ToJstring(env, "");

    // greedy decode
    std::string out;
    const auto tDecode = Clock::now();
    s->stop = STOP_MAX_TOKENS;
    char piece[256];
    static const std::string kEndOfTurn = "<turn|>";
    for (int i = 0; i < maxTokens; i++) {
        if (s->abort.load()) { s->stop = STOP_ABORTED; break; }
        const llama_token id = llama_sampler_sample(s->smpl, s->lctx, -1);
        llama_sampler_accept(s->smpl, id);
        if (llama_vocab_is_eog(s->vocab, id)) { s->stop = STOP_EOG; break; }
        const int32_t n = llama_token_to_piece(s->vocab, id, piece, sizeof(piece), 0, /*special*/ true);
        if (n > 0) out.append(piece, static_cast<size_t>(n));
        s->generatedTokens++;
        if (out.size() >= kEndOfTurn.size() && out.compare(out.size() - kEndOfTurn.size(), kEndOfTurn.size(), kEndOfTurn) == 0) {
            out.resize(out.size() - kEndOfTurn.size()); // safeguard when <turn|> is not flagged end-of-generation
            s->stop = STOP_EOG;
            break;
        }
        s->batch.n_tokens = 1;
        s->batch.token[0] = id;
        s->batch.pos[0] = nPast;
        s->batch.n_seq_id[0] = 1;
        s->batch.seq_id[0][0] = 0;
        s->batch.logits[0] = 1;
        const int32_t r = llama_decode(s->lctx, s->batch);
        if (r == 2 || s->abort.load()) { s->stop = STOP_ABORTED; break; }
        if (r != 0) {
            s->decodeMs = msSince(tDecode);
            throwRuntime(env, "llama_decode failed while generating (" + std::to_string(r) + ")");
            return nullptr;
        }
        nPast++;
    }
    s->decodeMs = msSince(tDecode);
    LOGI("generate: prompt_tokens=%lld positions=%lld media_chunks=%lld prefill_ms=%lld gen_tokens=%lld decode_ms=%lld stop=%lld",
         static_cast<long long>(s->promptTokens), static_cast<long long>(s->promptPositions), static_cast<long long>(s->mediaChunks),
         static_cast<long long>(s->prefillMs), static_cast<long long>(s->generatedTokens), static_cast<long long>(s->decodeMs),
         static_cast<long long>(s->stop));
    return utf8ToJstring(env, out);
}

// [promptTokens, promptPositions, generatedTokens, prefillMs (media encode + prompt decode), decodeMs, stop (0 eog, 1 max tokens, 2 aborted), mediaChunks]
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_test_1app_ai_llama_LlamaNative_nativeLastStats(JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    jlong v[7] = {0, 0, 0, 0, 0, 0, 0};
    if (s != nullptr) {
        v[0] = s->promptTokens; v[1] = s->promptPositions; v[2] = s->generatedTokens;
        v[3] = s->prefillMs; v[4] = s->decodeMs; v[5] = s->stop; v[6] = s->mediaChunks;
    }
    jlongArray arr = env->NewLongArray(7);
    env->SetLongArrayRegion(arr, 0, 7, v);
    return arr;
}

// Thread-safe: only stores a flag that nativeGenerate checks between chunks and between tokens (and that ggml polls on the CPU).
extern "C" JNIEXPORT void JNICALL
Java_com_example_test_1app_ai_llama_LlamaNative_nativeAbort(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_test_1app_ai_llama_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    freeSession(reinterpret_cast<Session *>(handle));
}

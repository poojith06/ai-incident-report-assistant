// JNI wrapper around whisper.cpp (ggml, CPU only). CPU STOPGAP ASR: the board has no NPU Whisper model.
// Class: com.example.test_app.ai.whisper.WhisperNative. Not thread-safe per context: the Kotlin side serialises calls.
#include <jni.h>
#include <android/log.h>

#include <string>

#include "whisper.h"

#define TAG "WhisperNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void throwRuntime(JNIEnv *env, const std::string &msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg.c_str());
}

static void ggmlLogToAndroid(enum ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_write(prio, TAG, text);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_test_1app_ai_whisper_WhisperNative_nativeCreate(JNIEnv *env, jobject, jstring modelPath) {
    whisper_log_set(ggmlLogToAndroid, nullptr);
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    std::string p(path);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        throwRuntime(env, "whisper_init_from_file failed for " + p);
        return 0;
    }
    LOGI("model loaded: %s | %s", p.c_str(), whisper_print_system_info());
    return reinterpret_cast<jlong>(ctx);
}

// Greedy decoding, no timestamps, transcribe mode (never translate), language forced to [language].
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_test_1app_ai_whisper_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jint threads, jstring language, jstring initialPrompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        throwRuntime(env, "whisper context is closed");
        return nullptr;
    }
    const jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    const char *prompt = initialPrompt != nullptr ? env->GetStringUTFChars(initialPrompt, nullptr) : nullptr;

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = lang;
    wp.detect_language = false;
    wp.translate = false;
    wp.no_context = true;
    wp.single_segment = false;
    wp.print_special = false;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.no_timestamps = true;
    wp.suppress_nst = true;
    wp.initial_prompt = (prompt != nullptr && prompt[0] != '\0') ? prompt : nullptr;

    const int rc = whisper_full(ctx, wp, samples, n);
    std::string text;
    if (rc == 0) {
        const int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) text += whisper_full_get_segment_text(ctx, i);
    }
    if (prompt != nullptr) env->ReleaseStringUTFChars(initialPrompt, prompt);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        throwRuntime(env, "whisper_full failed with status " + std::to_string(rc));
        return nullptr;
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_test_1app_ai_whisper_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

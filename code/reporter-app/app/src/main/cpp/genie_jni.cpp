// JNI bridge between com.example.test_app.ai.genie.GenieNative and the QAIRT Genie C API (libGenie.so).
// See doc/MODEL_INTEGRATION.md. Not thread-safe: the Kotlin side serialises every call on one thread.
#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <cstdlib>
#include <string>

#include "Genie/GenieCommon.h"
#include "Genie/GenieDialog.h"
#include "Genie/GenieLog.h"
#include <cstdarg>

#define TAG "GenieJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

void onGenieLog(const GenieLog_Handle_t, const char* fmt, GenieLog_Level_t level, uint64_t, va_list args) {
    int prio = level == GENIE_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : level == GENIE_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_vprint(prio, "GenieNative", fmt, args);
}

struct Session {
    GenieDialogConfig_Handle_t config = nullptr;
    GenieDialog_Handle_t dialog = nullptr;
    GenieLog_Handle_t log = nullptr;
    // Timings of the last query, milliseconds.
    long long firstTokenMs = 0;
    long long totalMs = 0;
};

struct QueryState {
    std::string text;
    std::chrono::steady_clock::time_point start;
    bool gotFirst = false;
    Session* session = nullptr;
};

void onResponse(const char* response, const GenieDialog_SentenceCode_t code, const void* userData) {
    auto* st = const_cast<QueryState*>(static_cast<const QueryState*>(userData));
    if (response != nullptr) {
        if (!st->gotFirst) {
            st->gotFirst = true;
            st->session->firstTokenMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - st->start).count();
        }
        st->text += response;
    }
    (void)code;
}

void throwRuntime(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, msg.c_str());
}

std::string utf(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

// long nativeCreate(String configJson, String libDir)
JNIEXPORT jlong JNICALL
Java_com_example_test_1app_ai_genie_GenieNative_nativeCreate(JNIEnv* env, jobject, jstring configJson, jstring libDir) {
    std::string dir = utf(env, libDir);
    // Hexagon DSP loads its skel library via ADSP_LIBRARY_PATH; the stubs via the normal linker path.
    std::string adsp = dir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
    setenv("LD_LIBRARY_PATH", dir.c_str(), 1);

    auto* s = new Session();
    std::string json = utf(env, configJson);
    Genie_Status_t st = GenieDialogConfig_createFromJson(json.c_str(), &s->config);
    if (st != GENIE_STATUS_SUCCESS) {
        delete s;
        throwRuntime(env, "GenieDialogConfig_createFromJson failed: " + std::to_string(st));
        return 0;
    }
    if (GenieLog_create(nullptr, onGenieLog, GENIE_LOG_LEVEL_VERBOSE, &s->log) == GENIE_STATUS_SUCCESS) {
        GenieDialogConfig_bindLogger(s->config, s->log);
    }
    st = GenieDialog_create(s->config, &s->dialog);
    if (st != GENIE_STATUS_SUCCESS) {
        GenieDialogConfig_free(s->config);
        delete s;
        throwRuntime(env, "GenieDialog_create failed: " + std::to_string(st));
        return 0;
    }
    LOGI("Genie dialog created");
    return reinterpret_cast<jlong>(s);
}

// String nativeQuery(long handle, String prompt)
JNIEXPORT jstring JNICALL
Java_com_example_test_1app_ai_genie_GenieNative_nativeQuery(JNIEnv* env, jobject, jlong handle, jstring prompt) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr) { throwRuntime(env, "Genie session is closed"); return nullptr; }
    std::string p = utf(env, prompt);
    QueryState qs;
    qs.session = s;
    qs.start = std::chrono::steady_clock::now();
    s->firstTokenMs = 0;
    Genie_Status_t st = GenieDialog_query(s->dialog, p.c_str(), GENIE_DIALOG_SENTENCE_COMPLETE, onResponse, &qs);
    s->totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - qs.start).count();
    // CONTEXT_EXCEEDED (4) is a warning: the text produced so far is still usable.
    if (st < 0) {
        throwRuntime(env, "GenieDialog_query failed: " + std::to_string(st));
        return nullptr;
    }
    LOGI("query status=%d chars=%zu firstTokenMs=%lld totalMs=%lld", st, qs.text.size(), s->firstTokenMs, s->totalMs);
    return env->NewStringUTF(qs.text.c_str());
}

// long[] nativeLastTimings(long handle)  -> [firstTokenMs, totalMs]
JNIEXPORT jlongArray JNICALL
Java_com_example_test_1app_ai_genie_GenieNative_nativeLastTimings(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    jlong v[2] = {s ? s->firstTokenMs : 0, s ? s->totalMs : 0};
    jlongArray arr = env->NewLongArray(2);
    env->SetLongArrayRegion(arr, 0, 2, v);
    return arr;
}

// void nativeReset(long handle)
JNIEXPORT void JNICALL
Java_com_example_test_1app_ai_genie_GenieNative_nativeReset(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr) return;
    Genie_Status_t st = GenieDialog_reset(s->dialog);
    if (st != GENIE_STATUS_SUCCESS) throwRuntime(env, "GenieDialog_reset failed: " + std::to_string(st));
}

// void nativeFree(long handle)
JNIEXPORT void JNICALL
Java_com_example_test_1app_ai_genie_GenieNative_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr) return;
    if (s->dialog) GenieDialog_free(s->dialog);
    if (s->config) GenieDialogConfig_free(s->config);
    delete s;
}

}  // extern "C"

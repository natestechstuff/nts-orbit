// JNI bridge: com.natestechstuff.jarvis.LlamaBridge <-> jarvis::Engine
#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "jarvis_llm.h"

#define TAG "JarvisLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java strings -> UTF-8 via String.getBytes("UTF-8") so emoji survive (GetStringUTFChars is "modified" UTF-8).
static std::string jstr_utf8(JNIEnv * env, jstring s) {
    if (!s) return "";
    jclass cls = env->GetObjectClass(s);
    jmethodID getBytes = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    jbyteArray arr = (jbyteArray) env->CallObjectMethod(s, getBytes, enc);
    jsize n = env->GetArrayLength(arr);
    std::string out((size_t) n, '\0');
    env->GetByteArrayRegion(arr, 0, n, (jbyte *) &out[0]);
    env->DeleteLocalRef(arr); env->DeleteLocalRef(enc); env->DeleteLocalRef(cls);
    return out;
}

static std::once_flag g_backends;

extern "C" JNIEXPORT void JNICALL
Java_com_natestechstuff_jarvis_LlamaBridge_nativeInit(JNIEnv * env, jclass, jstring nativeLibDir) {
    std::string dir = jstr(env, nativeLibDir);
    std::call_once(g_backends, [&] {
        // Picks the fastest CPU variant this phone supports (dotprod / fp16 / i8mm ...).
        ggml_backend_load_all_from_path(dir.c_str());
        LOGI("backends loaded from %s: %zu devices", dir.c_str(), ggml_backend_dev_count());
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_natestechstuff_jarvis_LlamaBridge_nativeCreate(JNIEnv *, jclass) {
    return (jlong) new jarvis::Engine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_natestechstuff_jarvis_LlamaBridge_nativeDestroy(JNIEnv *, jclass, jlong h) {
    delete (jarvis::Engine *) h;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_natestechstuff_jarvis_LlamaBridge_nativeLoad(JNIEnv * env, jclass, jlong h, jstring path, jint nCtx, jint nThreads) {
    auto * e = (jarvis::Engine *) h;
    std::string err = e->load(jstr(env, path), nCtx, nThreads);
    LOGI("load: %s", err.empty() ? e->describe().c_str() : err.c_str());
    return env->NewStringUTF(err.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_natestechstuff_jarvis_LlamaBridge_nativeUnload(JNIEnv *, jclass, jlong h) {
    ((jarvis::Engine *) h)->unload();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_natestechstuff_jarvis_LlamaBridge_nativeDescribe(JNIEnv * env, jclass, jlong h) {
    return env->NewStringUTF(((jarvis::Engine *) h)->describe().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_natestechstuff_jarvis_LlamaBridge_nativeStop(JNIEnv *, jclass, jlong h) {
    ((jarvis::Engine *) h)->request_stop();
}

// Returns {promptTokens, reusedTokens, genTokens, promptMs, genMs, stopped, hitLimit, droppedTurns}
// and the error (if any) through errOut[0]. Pieces are delivered as UTF-8 bytes to cb.onPiece(byte[]).
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_natestechstuff_jarvis_LlamaBridge_nativeGenerate(JNIEnv * env, jclass, jlong h,
        jobjectArray roles, jobjectArray contents, jint maxTokens,
        jfloat temp, jint topK, jfloat topP, jfloat minP, jfloat repeatPenalty,
        jobject cb, jobjectArray errOut) {
    auto * e = (jarvis::Engine *) h;
    std::vector<jarvis::Message> msgs;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        msgs.push_back({jstr(env, r), jstr_utf8(env, c)});
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    jarvis::SampleParams sp;
    sp.temperature = temp; sp.top_k = topK; sp.top_p = topP; sp.min_p = minP; sp.repeat_penalty = repeatPenalty;

    jclass cbCls = env->GetObjectClass(cb);
    jmethodID onPiece = env->GetMethodID(cbCls, "onPiece", "([B)Z");
    auto stream = [&](const std::string & p) -> bool {
        jbyteArray arr = env->NewByteArray((jsize) p.size());
        env->SetByteArrayRegion(arr, 0, (jsize) p.size(), (const jbyte *) p.data());
        jboolean keep = env->CallBooleanMethod(cb, onPiece, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return keep == JNI_TRUE;
    };
    jarvis::GenStats st = e->generate(msgs, maxTokens, sp, stream, nullptr);
    if (!st.error.empty() && errOut && env->GetArrayLength(errOut) > 0)
        env->SetObjectArrayElement(errOut, 0, env->NewStringUTF(st.error.c_str()));
    double vals[8] = {(double) st.prompt_tokens, (double) st.reused_tokens, (double) st.gen_tokens,
                      st.prompt_ms, st.gen_ms, st.stopped ? 1.0 : 0.0, st.hit_limit ? 1.0 : 0.0, (double) st.dropped_turns};
    jdoubleArray out = env->NewDoubleArray(8);
    env->SetDoubleArrayRegion(out, 0, 8, vals);
    return out;
}

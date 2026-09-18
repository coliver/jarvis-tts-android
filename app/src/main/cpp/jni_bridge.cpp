#include <jni.h>
#include <cstring>
#include <string>

extern "C" {
void* ptt_create(const char* models_dir, const char* voices_dir,
                  const char* tokenizer_path, const char* precision,
                  float temperature, int lsd_steps, int num_threads);
double ptt_warmup(void* handle);
void ptt_free_audio(float* samples);
void ptt_destroy(void* handle);
void* ptt_stream_start(void* handle, const char* text, const char* voice);
int ptt_stream_read(void* stream_ctx, float** out_samples, int* out_len);
void ptt_stream_end(void* stream_ctx);
}

namespace {
const char* dupCString(JNIEnv* env, jstring s, std::string& storage) {
    if (!s) return nullptr;
    const char* chars = env->GetStringUTFChars(s, nullptr);
    storage.assign(chars);
    env->ReleaseStringUTFChars(s, chars);
    return storage.c_str();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jarvistts_NativeBridge_create(JNIEnv* env, jobject, jstring modelsDir,
                                        jstring voicesDir, jstring tokenizerPath,
                                        jstring precision, jfloat temperature,
                                        jint lsdSteps, jint numThreads) {
    std::string m, v, t, p;
    const char* modelsC = dupCString(env, modelsDir, m);
    const char* voicesC = dupCString(env, voicesDir, v);
    const char* tokenC = dupCString(env, tokenizerPath, t);
    const char* precC = dupCString(env, precision, p);
    void* handle = ptt_create(modelsC, voicesC, tokenC, precC, temperature, lsdSteps, numThreads);
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_jarvistts_NativeBridge_warmup(JNIEnv*, jobject, jlong handle) {
    return ptt_warmup(reinterpret_cast<void*>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvistts_NativeBridge_destroy(JNIEnv*, jobject, jlong handle) {
    ptt_destroy(reinterpret_cast<void*>(handle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jarvistts_NativeBridge_streamStart(JNIEnv* env, jobject, jlong handle,
                                             jstring text, jstring voice) {
    std::string t, v;
    const char* textC = dupCString(env, text, t);
    const char* voiceC = dupCString(env, voice, v);
    void* ctx = ptt_stream_start(reinterpret_cast<void*>(handle), textC, voiceC);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jarvistts_NativeBridge_streamRead(JNIEnv* env, jobject, jlong streamCtx) {
    float* samples = nullptr;
    int len = 0;
    int status = ptt_stream_read(reinterpret_cast<void*>(streamCtx), &samples, &len);
    if (status <= 0) {
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(len);
    if (result) {
        env->SetFloatArrayRegion(result, 0, len, samples);
    }
    ptt_free_audio(samples);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvistts_NativeBridge_streamEnd(JNIEnv*, jobject, jlong streamCtx) {
    ptt_stream_end(reinterpret_cast<void*>(streamCtx));
}

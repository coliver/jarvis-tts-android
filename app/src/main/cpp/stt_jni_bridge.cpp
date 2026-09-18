// JNI bridge for on-device STT via whisper.cpp. Mirrors jni_bridge.cpp's
// thin-wrapper style: no logic here beyond JNI<->C++ marshalling.
#include <algorithm>
#include <android/log.h>
#include <jni.h>
#include <string>
#include <thread>
#include <vector>

#include "ggml.h"
#include "whisper.h"

static void whisperLog(enum ggml_log_level level, const char *text, void *) {
    __android_log_print(ANDROID_LOG_DEBUG, "JarvisSTTNative", "%s", text);
}

static int nThreads() {
    // Tensor G3 is big.LITTLE (1 Cortex-X3 + 4 A715 + 4 A510). ggml's thread pool
    // synchronizes every round on the slowest participating thread, so pulling in
    // the slow LITTLE cores can make things SLOWER than fewer, faster threads.
    // Stick to roughly the fast+mid cluster (X3 + A715) and skip the LITTLE cores.
    return 4;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jarvistts_NativeSTT_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    whisper_log_set(whisperLog, nullptr);
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    // Default flash_attn=true hits a pathologically slow path on this CPU backend
    // (encode time alone was 22s for a 4s clip -- confirmed via whisper_print_timings).
    // The plain attention kernel is dramatically faster here.
    cparams.flash_attn = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvistts_NativeSTT_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize len = env->GetArrayLength(pcm);
    std::vector<float> samples(len);
    env->GetFloatArrayRegion(pcm, 0, len, samples.data());

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language = "en";
    wparams.translate = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_special = false;
    wparams.no_context = true;
    wparams.single_segment = false;
    wparams.n_threads = nThreads();
    // Tighten the quality gates that trigger whisper's temperature-fallback
    // retry. Defaults (logprob -1.0, entropy 2.4, no_speech 0.6) let a fair
    // amount of low-confidence or noise-driven garbage through as final
    // text. Now that -O3 is actually enabled (see CMakeLists.txt), we have
    // plenty of headroom to let it retry more often in exchange for accuracy.
    wparams.logprob_thold = -0.4f;
    wparams.entropy_thold = 1.8f;
    wparams.no_speech_thold = 0.4f;
    __android_log_print(ANDROID_LOG_DEBUG, "JarvisSTT",
                         "whisper_full starting with n_threads=%d, hw_concurrency=%u, "
                         "arm_fma=%d, fp16_va=%d",
                         wparams.n_threads, std::thread::hardware_concurrency(),
                         ggml_cpu_has_arm_fma(), ggml_cpu_has_fp16_va());

    if (whisper_full(ctx, wparams, samples.data(), static_cast<int>(samples.size())) != 0) {
        return env->NewStringUTF("");
    }
    whisper_print_timings(ctx);

    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvistts_NativeSTT_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

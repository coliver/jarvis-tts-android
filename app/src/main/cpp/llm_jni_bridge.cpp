// JNI bridge for on-device LLM inference via llama.cpp. Mirrors the other
// bridges' thin-wrapper style. Prompt formatting (chat template) is done on
// the Kotlin side; this just tokenizes, decodes, and detokenizes.
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <exception>
#include <jni.h>
#include <string>
#include <vector>

#include "ggml.h"
#include "llama.h"

struct LlmSession {
    llama_model *model;
    llama_context *ctx;
    std::atomic<bool> cancelRequested{false};
    // Tokens whose KV entries are currently valid in ctx (the last prompt, not its reply).
    std::vector<llama_token> cachedTokens;
};

static int nThreads() {
    // Tensor G3 is big.LITTLE (1 Cortex-X3 + 4 A715 + 4 A510). Same fix as
    // stt_jni_bridge.cpp's nThreads(): ggml's thread pool (llama.cpp vendors
    // its own copy, same synchronize-every-round design) waits on the
    // slowest participating thread each round, so requesting more threads
    // than the fast+mid cluster spills onto the slow LITTLE cores and makes
    // generation SLOWER, not faster. Measured: uncapped at 6 threads,
    // decode ran at ~1.0 tok/s; this was never applied to the LLM path when
    // it was found for STT.
    return 4;
}

// Tried pinning the compute thread to cpu4-8 (the fast+mid cluster) here via
// sched_setaffinity, on the theory that nThreads()'s cap alone doesn't say
// WHICH cores the OS schedules those threads onto. Verified the pin itself
// took correctly (/proc/<pid>/task/*/status showed the right Cpus_allowed
// mask), but measured tok/s got WORSE (0.9-4.4 vs an unpinned 3.2-6.6
// baseline) -- most likely because confining every turn's compute to the
// same fixed 5 cores concentrates heat there instead of letting the OS
// spread load across all 9 and let hot cores cool. Reverted 2026-09-21;
// don't reintroduce without a controlled cold-device A/B, not back-to-back
// turns on an already-warm phone.

struct ProgressCtx {
    JNIEnv *env;
    jobject listener; // NativeLLM.ProgressListener, local ref valid on this thread
    jmethodID method;
};

static bool onLoadProgress(float progress, void *user_data) {
    auto *ctx = reinterpret_cast<ProgressCtx *>(user_data);
    if (ctx->listener != nullptr) {
        ctx->env->CallVoidMethod(ctx->listener, ctx->method, progress);
    }
    return true; // returning false would abort loading
}

// A C++ exception escaping an extern "C" JNI function is undefined behavior and in
// practice aborts the whole process. llama.cpp signals most bad-file cases by
// returning nullptr, but allocation failures (std::bad_alloc on a low-RAM device) and
// gguf parse errors are thrown, so nativeInit catches them and returns 0 -- the same
// "load failed" result Kotlin already handles -- instead of taking the app down.
extern "C" JNIEXPORT jlong JNICALL
Java_com_jarvistts_NativeLLM_nativeInit(JNIEnv *env, jobject, jstring modelPath, jint nCtx, jobject listener) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;

    try {
        ProgressCtx pctx{env, nullptr, nullptr};
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        if (listener != nullptr) {
            jclass listenerClass = env->GetObjectClass(listener);
            pctx.listener = listener;
            pctx.method = env->GetMethodID(listenerClass, "onProgress", "(F)V");
            model_params.progress_callback = onLoadProgress;
            model_params.progress_callback_user_data = &pctx;
        }
        model = llama_load_model_from_file(path, model_params);
        env->ReleaseStringUTFChars(modelPath, path);
        path = nullptr;
        if (model == nullptr) {
            return 0;
        }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = nCtx;
        ctx_params.n_batch = nCtx;
        ctx_params.n_threads = nThreads();
        ctx_params.n_threads_batch = nThreads();
        ctx = llama_new_context_with_model(model, ctx_params);
        if (ctx == nullptr) {
            llama_free_model(model);
            return 0;
        }

        auto *session = new LlmSession{model, ctx};
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, "JarvisLLM", "nativeInit failed: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "JarvisLLM", "nativeInit failed: unknown exception");
    }
    if (path != nullptr) {
        env->ReleaseStringUTFChars(modelPath, path);
    }
    if (ctx != nullptr) {
        llama_free(ctx);
    }
    if (model != nullptr) {
        llama_free_model(model);
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvistts_NativeLLM_nativeFormatPrompt(JNIEnv *env, jobject, jlong handle, jstring systemPrompt,
                                                 jobjectArray historyRoles, jobjectArray historyTexts,
                                                 jstring userText) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (session == nullptr) {
        return env->NewStringUTF("");
    }

    const char *sys_chars = env->GetStringUTFChars(systemPrompt, nullptr);
    const char *user_chars = env->GetStringUTFChars(userText, nullptr);
    jsize historyLen = env->GetArrayLength(historyRoles);

    // Local refs + UTF chars for each history entry need to stay alive until
    // after llama_chat_apply_template() runs, since messages[] just holds
    // borrowed const char* pointers into them.
    std::vector<jstring> roleRefs(historyLen);
    std::vector<jstring> textRefs(historyLen);
    std::vector<const char *> roleChars(historyLen);
    std::vector<const char *> textChars(historyLen);
    for (jsize i = 0; i < historyLen; i++) {
        roleRefs[i] = (jstring) env->GetObjectArrayElement(historyRoles, i);
        textRefs[i] = (jstring) env->GetObjectArrayElement(historyTexts, i);
        roleChars[i] = env->GetStringUTFChars(roleRefs[i], nullptr);
        textChars[i] = env->GetStringUTFChars(textRefs[i], nullptr);
    }

    std::vector<llama_chat_message> messages;
    messages.reserve(historyLen + 2);
    messages.push_back({"system", sys_chars});
    for (jsize i = 0; i < historyLen; i++) {
        messages.push_back({roleChars[i], textChars[i]});
    }
    messages.push_back({"user", user_chars});

    // nullptr tmpl: use whichever template is embedded in this model's GGUF
    // metadata (or llama.cpp's best-guess match for known families), so
    // swapping the .gguf asset doesn't require touching any formatting code.
    std::vector<char> buf(1024);
    int32_t n = llama_chat_apply_template(session->model, nullptr, messages.data(), messages.size(), true,
                                           buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(session->model, nullptr, messages.data(), messages.size(), true,
                                       buf.data(), (int32_t) buf.size());
    }

    env->ReleaseStringUTFChars(systemPrompt, sys_chars);
    env->ReleaseStringUTFChars(userText, user_chars);
    for (jsize i = 0; i < historyLen; i++) {
        env->ReleaseStringUTFChars(roleRefs[i], roleChars[i]);
        env->ReleaseStringUTFChars(textRefs[i], textChars[i]);
    }

    if (n < 0) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(std::string(buf.data(), n).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvistts_NativeLLM_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (session == nullptr) {
        return env->NewStringUTF("");
    }
    llama_model *model = session->model;
    llama_context *ctx = session->ctx;
    session->cancelRequested.store(false);
    const char *prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    const int n_prompt = -llama_tokenize(model, prompt_str.c_str(), (int) prompt_str.size(), nullptr, 0, true, true);
    std::vector<llama_token> prompt_tokens(n_prompt);
    if (llama_tokenize(model, prompt_str.c_str(), (int) prompt_str.size(),
                        prompt_tokens.data(), (int) prompt_tokens.size(), true, true) < 0) {
        return env->NewStringUTF("");
    }

    // Every call gets the full prompt (persona + history + utterance), but the start of it
    // (persona, earlier turns) is usually identical to last time. Keep those KV entries and
    // only evaluate the new tail: prompt evaluation is the slowest part of a turn on this
    // phone (18-30s for ~300 tokens uncached, ~7s with the prefix reused). Everything after
    // the shared prefix, including last turn's generated reply, is dropped so positions never
    // pile up past the context window (which made llama_decode fail instantly, returning "").
    // cachedTokens stays empty until this prompt's first decode succeeds, so a cancelled or
    // failed call falls back to a full clear next time.
    size_t common = 0;
    while (common < session->cachedTokens.size() && common < prompt_tokens.size() &&
           session->cachedTokens[common] == prompt_tokens[common]) {
        common++;
    }
    if (common >= prompt_tokens.size()) common = prompt_tokens.size() - 1; // need logits for the last token
    session->cachedTokens.clear();
    if (!llama_kv_cache_seq_rm(ctx, 0, (llama_pos) common, -1)) {
        llama_kv_cache_clear(ctx);
        common = 0;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    // Greedy decoding on a small 1B model can fall into runaway repetition
    // loops that never hit an end-of-generation token, silently burning the
    // full maxTokens budget (30+ seconds) instead of stopping naturally.
    // A repetition penalty makes that far less likely without needing
    // full sampling (still deterministic-ish, just avoids reusing recent tokens).
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
        llama_n_vocab(model), llama_token_eos(model), llama_token_nl(model),
        64, 1.3f, 0.0f, 0.0f, false, false));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string result;
    llama_batch batch = llama_batch_get_one(prompt_tokens.data() + common, (int) (prompt_tokens.size() - common));

    __android_log_print(ANDROID_LOG_DEBUG, "JarvisLLM",
                         "n_threads=%d, arm_fma=%d, fp16_va=%d, n_prompt=%d",
                         nThreads(), ggml_cpu_has_arm_fma(), ggml_cpu_has_fp16_va(), n_prompt);

    auto genStart = std::chrono::steady_clock::now();
    long long prefillMs = -1;
    int n_decode = 0;
    llama_token new_token_id;
    for (int n_pos = (int) common; n_pos + batch.n_tokens < n_prompt + maxTokens;) {
        if (session->cancelRequested.load()) {
            break;
        }
        if (llama_decode(ctx, batch)) {
            break;
        }
        if (prefillMs < 0) {
            session->cachedTokens = prompt_tokens;
            prefillMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - genStart).count();
            __android_log_print(ANDROID_LOG_DEBUG, "JarvisLLM",
                                 "prefill (%d prompt tokens, %d reused) took %lldms", n_prompt, (int) common, prefillMs);
        }
        n_pos += batch.n_tokens;

        new_token_id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_token_is_eog(model, new_token_id)) {
            break;
        }

        char buf[128];
        int n = llama_token_to_piece(model, new_token_id, buf, sizeof(buf), 0, true);
        if (n < 0) {
            break;
        }
        result.append(buf, n);

        batch = llama_batch_get_one(&new_token_id, 1);
        n_decode += 1;
        if (n_decode >= maxTokens) {
            break;
        }
    }

    llama_sampler_free(smpl);

    auto genMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - genStart).count();
    __android_log_print(ANDROID_LOG_DEBUG, "JarvisLLM",
                         "generated %d tokens in %lldms (%.1f tok/s), hit_eog=%d",
                         n_decode, (long long) genMs,
                         genMs > 0 ? n_decode * 1000.0 / genMs : 0.0,
                         n_decode < maxTokens);

    return env->NewStringUTF(result.c_str());
}

// Called from a different thread than the one blocked inside nativeGenerate
// (that call doesn't return until generation stops), so this can't return
// partial output directly -- it just flags the loop to stop at the next
// token boundary. nativeGenerate then returns whatever it had accumulated
// so far, same as hitting maxTokens or an end-of-generation token.
extern "C" JNIEXPORT void JNICALL
Java_com_jarvistts_NativeLLM_nativeCancelGenerate(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (session != nullptr) {
        session->cancelRequested.store(true);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvistts_NativeLLM_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<LlmSession *>(handle);
    if (session != nullptr) {
        llama_free(session->ctx);
        llama_free_model(session->model);
        delete session;
    }
}

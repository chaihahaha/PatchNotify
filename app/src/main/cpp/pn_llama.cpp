// llama.cpp + mtmd 的 JNI 薄封装：初始化一次，之后每帧只做一次 ocr() 调用。
#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include <cstdarg>

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "pnllama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model*        g_model = nullptr;
static llama_context*      g_ctx   = nullptr;
static mtmd_context*       g_mctx  = nullptr;
static const llama_vocab*  g_vocab = nullptr;
static std::mutex          g_mu;
static std::string         g_logPath;

static void pnLog(const char* fmt, ...) {
    if (g_logPath.empty()) return;
    FILE* f = fopen(g_logPath.c_str(), "a");
    if (!f) return;
    va_list ap; va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

static void releaseAll() {
    if (g_mctx)  { mtmd_free(g_mctx); g_mctx = nullptr; }
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_patchnotify_NativeLlama_init(JNIEnv* env, jclass, jstring jmodel,
                                              jstring jmmproj, jint nThreads) {
    std::lock_guard<std::mutex> lk(g_mu);
    releaseAll();
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();   // 纯 CPU


    std::string modelPath = jstr(env, jmodel);
    std::string mmprojPath = jstr(env, jmmproj);
    g_model = llama_model_load_from_file(modelPath.c_str(), mp);
    if (!g_model) return env->NewStringUTF("ERR:模型加载失败");

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1024;
    cp.n_batch = 1024;
    cp.n_ubatch = 1024;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { releaseAll(); return env->NewStringUTF("ERR:context 创建失败"); }

    mtmd_context_params mparams = mtmd_context_params_default();
    mparams.use_gpu = false;
    mparams.print_timings = false;
    mparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    // 与旧 ONNX 的 smart_resize 对齐：495x173 的框约 75 个图像 token。
    // 之前压到 64 会退成 52，首位 0 会偶发读成 9，所以放到 128（等于不压缩）。
    mparams.image_max_tokens = 128;
    mparams.n_threads = nThreads;
    g_mctx = mtmd_init_from_file(mmprojPath.c_str(), g_model, mparams);
    if (!g_mctx) { releaseAll(); return env->NewStringUTF("ERR:mmproj 加载失败"); }

    g_vocab = llama_model_get_vocab(g_model);
    pnLog("cpp: init ok threads=%d model=%s", nThreads, modelPath.c_str());
    return env->NewStringUTF("ok");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_patchnotify_NativeLlama_setLogPath(JNIEnv* env, jclass, jstring p) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_logPath = jstr(env, p);
}

// rgb: w*h*3 字节；返回模型原始输出（已去掉 <think> 段）
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_patchnotify_NativeLlama_ocr(JNIEnv* env, jclass, jbyteArray jrgb,
                                             jint w, jint h, jstring jprompt, jint maxTok) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_ctx || !g_mctx || !g_vocab) return env->NewStringUTF("ERR:not_init");

    std::string prompt = jstr(env, jprompt);
    jsize n = env->GetArrayLength(jrgb);
    if (n < (jsize)w * h * 3) return env->NewStringUTF("ERR:bad_bitmap");
    jbyte* data = env->GetByteArrayElements(jrgb, nullptr);

    // Qwen 对话格式 + 图像占位符
    // 关掉 thinking：按官方 chat template 的 no-think 分支预填一个空 think 块
    std::string text = std::string("<|im_start|>user\n") + mtmd_default_marker() +
                       prompt + "<|im_end|>\n<|im_start|>assistant\n" + "<think>\n\n</think>\n\n";

    int64_t tTok0 = ggml_time_us();
    mtmd_bitmap* bmp = mtmd_bitmap_init((uint32_t)w, (uint32_t)h, (const unsigned char*)data);
    const mtmd_bitmap* bmps[1] = { bmp };
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    mtmd_input_text itext{ text.c_str(), text.size(), true, true };
    int32_t tr = mtmd_tokenize(g_mctx, chunks, &itext, bmps, 1);
    if (tr != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        env->ReleaseByteArrayElements(jrgb, data, JNI_ABORT);
        return env->NewStringUTF("ERR:tokenize");
    }

    int64_t tTok1 = ggml_time_us();
    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_pos n_past = 0;
    int32_t ev = 0;
    {
        size_t nc = mtmd_input_chunks_size(chunks);
        for (size_t i = 0; i < nc; i++) {
            const mtmd_input_chunk* ch = mtmd_input_chunks_get(chunks, i);
            ev = mtmd_helper_eval_chunk_single(g_mctx, g_ctx, ch, n_past, 0, 1024, true, &n_past);
            if (ev != 0) break;
        }
    }
    int64_t tPre1 = ggml_time_us();
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bmp);
    env->ReleaseByteArrayElements(jrgb, data, JNI_ABORT);
    if (ev != 0) return env->NewStringUTF("ERR:prefill");
    pnLog("cpp: prefill=%lldms tokens_in=%d", (long long)((tPre1-tTok1)/1000), (int)n_past);

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    std::string all;
    int nGen = 0;
    for (int i = 0; i < maxTok; i++) {
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) break;
        char buf[512];
        int len = llama_token_to_piece(g_vocab, id, buf, sizeof(buf), 0, true);
        if (len > 0) all.append(buf, len);
        nGen++;
        // 生成到 turn 结束标记就停
        if (all.find("<|im_end|>") != std::string::npos) break;
        llama_batch b = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, b) != 0) break;
    }
    llama_sampler_free(smpl);

    if (out.empty()) {
        size_t tp = all.find("</think>");
        out = (tp == std::string::npos) ? all : all.substr(tp + 8);
    }
    // 去掉尾部标记
    const char* stops[] = {"<|im_end|>", "<|endoftext|>", "<|im_start|>"};
    for (auto* sp : stops) {
        size_t p = out.find(sp);
        if (p != std::string::npos) out = out.substr(0, p);
    }
    while (!out.empty() && (out.back() == '\n' || out.back() == '\r' || out.back() == ' ')) out.pop_back();
    pnLog("cpp: decode=%lldms out_tokens=%d total=%lldms",
         (long long)((ggml_time_us()-tPre1)/1000), nGen, (long long)((ggml_time_us()-tTok0)/1000));
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_patchnotify_NativeLlama_release(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mu);
    releaseAll();
}

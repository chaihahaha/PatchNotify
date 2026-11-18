// llama.cpp + mtmd 的 JNI 薄封装：初始化一次，之后每帧只做一次推理。
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
    // 长时间监控会不断写 native.log，超过 2 MB 轮转一次，避免无限增长
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    if (sz > 2L * 1024 * 1024) {
        fclose(f);
        std::string oldPath = g_logPath + ".1";
        remove(oldPath.c_str());
        rename(g_logPath.c_str(), oldPath.c_str());
        f = fopen(g_logPath.c_str(), "a");
        if (!f) return;
    }
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
    cp.n_ctx = 4096;
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
    // 不额外限制图像 token 数：0 = 用模型 metadata 里的默认切块，分辨率由原图决定。
    // 限制这里等于先把画面压小再喂给视觉塔，细节（数码管笔画、小字）直接丢。
    mparams.image_max_tokens = 0;
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

// 返回当前 mtmd 版本使用的图像占位符，Kotlin 端按「参考图...标记...实时图标记」拼接
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_patchnotify_NativeLlama_marker(JNIEnv* env, jclass) {
    return env->NewStringUTF(mtmd_default_marker());
}

// images: 每张 w*h*3 的 RGB；prompt 里必须已经包含同样数量的图像占位符
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_patchnotify_NativeLlama_infer(JNIEnv* env, jclass, jobjectArray jimages,
                                             jintArray jwidths, jintArray jheights,
                                             jstring jprompt, jint maxTok,
                                             jboolean allowThink, jstring jgrammar) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_ctx || !g_mctx || !g_vocab) return env->NewStringUTF("ERR:not_init");
    if (!jimages || !jwidths || !jheights) return env->NewStringUTF("ERR:bad_images");

    jsize count = env->GetArrayLength(jimages);
    if (count <= 0) return env->NewStringUTF("ERR:no_images");
    if (env->GetArrayLength(jwidths) < count || env->GetArrayLength(jheights) < count) {
        return env->NewStringUTF("ERR:bad_image_meta");
    }
    std::vector<jint> widths((size_t)count), heights((size_t)count);
    env->GetIntArrayRegion(jwidths, 0, count, widths.data());
    env->GetIntArrayRegion(jheights, 0, count, heights.data());
    for (jsize i = 0; i < count; i++) {
        if (widths[(size_t)i] <= 0 || heights[(size_t)i] <= 0) {
            return env->NewStringUTF("ERR:bad_size");
        }
    }

    std::vector<jbyteArray> arrays;
    std::vector<jbyte*> datas;
    std::vector<mtmd_bitmap*> bitmaps;
    std::vector<const mtmd_bitmap*> bmps;
    arrays.reserve((size_t)count);
    datas.reserve((size_t)count);
    bitmaps.reserve((size_t)count);
    bmps.reserve((size_t)count);

    auto cleanupImages = [&]() {
        for (auto* b : bitmaps) if (b) mtmd_bitmap_free(b);
        for (size_t i = 0; i < arrays.size(); i++) {
            if (datas[i]) env->ReleaseByteArrayElements(arrays[i], datas[i], JNI_ABORT);
        }
        bitmaps.clear();
        datas.clear();
        arrays.clear();
        bmps.clear();
    };

    for (jsize i = 0; i < count; i++) {
        auto arr = (jbyteArray)env->GetObjectArrayElement(jimages, i);
        if (!arr) { cleanupImages(); return env->NewStringUTF("ERR:null_image"); }
        int w = widths[(size_t)i];
        int h = heights[(size_t)i];
        jsize len = env->GetArrayLength(arr);
        if (len < (jsize)w * h * 3) {
            env->DeleteLocalRef(arr);
            cleanupImages();
            return env->NewStringUTF("ERR:bad_bitmap");
        }
        jbyte* data = env->GetByteArrayElements(arr, nullptr);
        if (!data) {
            env->DeleteLocalRef(arr);
            cleanupImages();
            return env->NewStringUTF("ERR:bitmap_pin");
        }
        mtmd_bitmap* bmp = mtmd_bitmap_init((uint32_t)w, (uint32_t)h,
                                           (const unsigned char*)data);
        if (!bmp) {
            env->ReleaseByteArrayElements(arr, data, JNI_ABORT);
            env->DeleteLocalRef(arr);
            cleanupImages();
            return env->NewStringUTF("ERR:bitmap_init");
        }
        arrays.push_back(arr);
        datas.push_back(data);
        bitmaps.push_back(bmp);
        bmps.push_back(bmp);
    }

    std::string prompt = jstr(env, jprompt);
    // Qwen 对话格式；图像占位符已经由 Kotlin 按图片顺序写在 prompt 里。
    // 默认关 thinking：按官方 chat template 的 no-think 分支预填空 think 块。
    // 用户勾了「允许思考」就不预填，让模型自己推理（慢，但开放式判断准得多）。
    std::string text = std::string("<|im_start|>user\n") + prompt +
                       "<|im_end|>\n<|im_start|>assistant\n";
    if (!allowThink) text += "<think>\n\n</think>\n\n";

    int64_t tTok0 = ggml_time_us();
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    mtmd_input_text itext{ text.c_str(), text.size(), true, true };
    int32_t tr = mtmd_tokenize(g_mctx, chunks, &itext, bmps.data(), bmps.size());
    if (tr != 0) {
        mtmd_input_chunks_free(chunks);
        cleanupImages();
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
    cleanupImages();
    if (ev != 0) return env->NewStringUTF("ERR:prefill");
    pnLog("cpp: prefill=%lldms tokens_in=%d images=%d",
          (long long)((tPre1-tTok1)/1000), (int)n_past, (int)count);

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // GBNF 语法约束放在 greedy 前面：先把候选 token 裁到语法允许的集合，再挑最大概率的。
    // 注意思考模式下不能用（思维链不满足 "是|否" 的语法），调用方已经保证不这么传。
    std::string grammar = jgrammar ? jstr(env, jgrammar) : std::string();
    if (!grammar.empty()) {
        llama_sampler* gs = llama_sampler_init_grammar(g_vocab, grammar.c_str(), "root");
        if (!gs) {
            llama_sampler_free(smpl);
            return env->NewStringUTF("ERR:grammar");
        }
        llama_sampler_chain_add(smpl, gs);
        pnLog("cpp: grammar=%s", grammar.c_str());
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

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

    // 去掉思考段（正常由 Grammar 截断，这里兜底）
    std::string out = all;
    size_t tp = out.find("</think>");
    if (tp != std::string::npos) out = out.substr(tp + 8);
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

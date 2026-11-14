// PatchNotify 本地 Qwen OCR —— 纯 C++ 推理（ONNX Runtime C API）
// 组成：Qwen smart-resize → patchify → vision_encoder → embed_tokens → decoder 自回归循环
#ifdef __ANDROID__
#include <jni.h>
#include <android/log.h>
#endif
#include <cmath>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>
#include <chrono>
#include "onnxruntime_c_api.h"
#ifdef __ANDROID__
#include <android/api-level.h>
#endif

#ifdef __ANDROID__
// ORT 的 Android 包仍导出该符号，但新版头文件不再声明（NNAPI 已废弃）
extern "C" OrtStatus* OrtSessionOptionsAppendExecutionProvider_Nnapi(OrtSessionOptions*, uint32_t);
#define NNAPI_FLAG_USE_FP16 0x001
#define NNAPI_FLAG_USE_NCHW 0x004
#define NNAPI_FLAG_CPU_DISABLED 0x008
#endif

#define TAG "pnocr"
#ifdef __ANDROID__
#include <cstdarg>
static FILE* g_logFile = nullptr;
static void pnFileLog(const char* fmt, ...) {
    if (!g_logFile) return;
    va_list ap; va_start(ap, fmt); vfprintf(g_logFile, fmt, ap); va_end(ap);
    fputc('\n', g_logFile); fflush(g_logFile);
}
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); pnFileLog(__VA_ARGS__); } while (0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); pnFileLog(__VA_ARGS__); } while (0)
#else
#include <cstdio>
#define LOGI(...) do { printf(__VA_ARGS__); printf("\n"); } while (0)
#define LOGE(...) do { printf("ERR " __VA_ARGS__); printf("\n"); } while (0)
#endif

static const OrtApi* g_ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);

// ---- Qwen 常量 ----
static const int PATCH = 16;
static const int MERGE = 2;
static const int FACTOR = PATCH * MERGE;      // 32
static const int HIDDEN = 2048;
static const int PATCH_DIM = 3 * 2 * PATCH * PATCH;  // 1536 (C*T*P*P)
static const int IMG_TOK = 248056;
static const int EOS = 248044;
static const long MIN_PIXELS = 65536;
static const long MAX_PIXELS = 16777216;
static const int MAX_IMG_TOKENS = 256;   // 框太大就报错，不悄悄缩图

static const int PREFIX[] = {248045, 846, 198, 248053};  // <|im_start|>user\n<|vision_start|>
static const int TAIL_COMMON[] = {248054, 248046, 198, 248045, 74455, 198, 248068, 271, 248069, 271};

static void check(OrtStatus* st, const char* what) {
    if (st) {
        LOGE("%s failed: %s", what, g_ort->GetErrorMessage(st));
        g_ort->ReleaseStatus(st);
    }
}

// ------------------------------------------------------------------ 数学

static inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

/** bilinear resize，RGB8 → 归一化后的 CHW float（(v/255-0.5)/0.5） */
static void resizeToCHW(const uint8_t* rgb, int w, int h, int W, int H, float* out, int C = 3) {
    const float sx = (float)w / (float)W;
    const float sy = (float)h / (float)H;
    for (int c = 0; c < C; c++) out[c * W * H] = 0;  // 占位，实际逐像素写
    for (int y = 0; y < H; y++) {
        float fy = (y + 0.5f) * sy - 0.5f;
        if (fy < 0) fy = 0;
        int y0 = (int)fy;
        if (y0 > h - 1) y0 = h - 1;
        int y1 = y0 + 1 < h ? y0 + 1 : h - 1;
        float wy = fy - y0;
        for (int x = 0; x < W; x++) {
            float fx = (x + 0.5f) * sx - 0.5f;
            if (fx < 0) fx = 0;
            int x0 = (int)fx;
            if (x0 > w - 1) x0 = w - 1;
            int x1 = x0 + 1 < w ? x0 + 1 : w - 1;
            float wx = fx - x0;
            for (int c = 0; c < C; c++) {
                float v00 = rgb[(y0 * w + x0) * C + c];
                float v01 = rgb[(y0 * w + x1) * C + c];
                float v10 = rgb[(y1 * w + x0) * C + c];
                float v11 = rgb[(y1 * w + x1) * C + c];
                float v = (v00 * (1 - wx) + v01 * wx) * (1 - wy) + (v10 * (1 - wx) + v11 * wx) * wy;
                out[c * W * H + y * W + x] = v / 127.5f - 1.0f;
            }
        }
    }
}

/** Qwen smart_resize：返回 (H, W)，均为 32 的倍数 */
static void smartResize(int h, int w, int* outH, int* outW) {
    if (h < FACTOR || w < FACTOR) {
        float s = std::max((float)FACTOR / h, (float)FACTOR / w);
        h = (int)std::lround(h * s);
        w = (int)std::lround(w * s);
    }
    int hbar = (int)std::lround((float)h / FACTOR) * FACTOR;
    int wbar = (int)std::lround((float)w / FACTOR) * FACTOR;
    if ((long)hbar * wbar > MAX_PIXELS) {
        double beta = std::sqrt((double)h * w / MAX_PIXELS);
        hbar = std::max(FACTOR, (int)std::floor(h / beta / FACTOR) * FACTOR);
        wbar = std::max(FACTOR, (int)std::floor(w / beta / FACTOR) * FACTOR);
    }
    if ((long)hbar * wbar < MIN_PIXELS) {
        double beta = std::sqrt((double)MIN_PIXELS / ((double)h * w));
        hbar = (int)std::ceil(h * beta / FACTOR) * FACTOR;
        wbar = (int)std::ceil(w * beta / FACTOR) * FACTOR;
    }
    *outH = hbar;
    *outW = wbar;
}

/** CHW float → [num_patches, 1536]，顺序与 HF 的 permute(0,3,6,4,7,2,1,5,8) 一致 */
static void patchify(const float* chw, int W, int H, std::vector<float>& out) {
    const int gh = H / PATCH, gw = W / PATCH;
    const int n = gh * gw;
    out.assign((size_t)n * PATCH_DIM, 0.0f);
    int row = 0;
    for (int i = 0; i < gh / MERGE; i++) {           // 2x2 块行
        for (int j = 0; j < gw / MERGE; j++) {       // 2x2 块列
            for (int m1 = 0; m1 < MERGE; m1++) {
                for (int m2 = 0; m2 < MERGE; m2++) {
                    float* dst = &out[(size_t)row * PATCH_DIM];
                    int baseY = (i * MERGE + m1) * PATCH;
                    int baseX = (j * MERGE + m2) * PATCH;
                    for (int c = 0; c < 3; c++) {
                        for (int t = 0; t < 2; t++) {     // 静态图复制两帧
                            for (int py = 0; py < PATCH; py++) {
                                for (int px = 0; px < PATCH; px++) {
                                    int di = ((c * 2 + t) * PATCH + py) * PATCH + px;
                                    dst[di] = chw[c * W * H + (baseY + py) * W + (baseX + px)];
                                }
                            }
                        }
                    }
                    row++;
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 引擎

struct Session {
    OrtSession* s = nullptr;
    std::vector<std::string> inNames, outNames;
};

static OrtEnv* g_env = nullptr;
static std::mutex g_mu;
static bool g_loaded = false;
static Session g_vis, g_emb, g_dec;

static std::string g_epInfo = "cpu";
static bool g_cpuOnly = false;
static std::vector<int> g_customIds;   // 自定义指令 token（自然语言规则用）
static bool g_returnIds = false;       // true 时返回生成 token id 而不是数字串

std::string runOcr(const uint8_t* rgbPtr, int wid, int hei, int kindIdx);  // 前向声明
static std::string probeNnapiDecode(const std::string& dir);

// 桌面端测试用：设置自定义指令并返回 token id
void pnSetCustomPrompt(const std::vector<int>& ids, bool returnIds) {
    g_customIds = ids;
    g_returnIds = returnIds;
}
static std::string g_modelDir;

/** 先试 NNAPI（ARM 手机的 GPU/DSP），失败就退回 CPU。返回实际用的 EP */
#ifdef __ANDROID__
static bool openWithNnapi(Session& out, const std::string& path, int threads, uint32_t flags) {
    if (android_get_device_api_level() < 27) return false;   // NNAPI 从 API 27 才有
    OrtSessionOptions* opts = nullptr;
    g_ort->CreateSessionOptions(&opts);
    g_ort->SetIntraOpNumThreads(opts, threads);
    g_ort->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL);
    OrtStatus* st = OrtSessionOptionsAppendExecutionProvider_Nnapi(opts, flags);
    if (st) {
        LOGI("NNAPI not available: %s", g_ort->GetErrorMessage(st));
        g_ort->ReleaseStatus(st);
        g_ort->ReleaseSessionOptions(opts);
        return false;
    }
    st = g_ort->CreateSession(g_env, path.c_str(), opts, &out.s);
    if (st) {
        LOGI("NNAPI session failed (%s), falling back to CPU", g_ort->GetErrorMessage(st));
        g_ort->ReleaseStatus(st);
        out.s = nullptr;
        g_ort->ReleaseSessionOptions(opts);
        return false;
    }
    g_ort->ReleaseSessionOptions(opts);
    return true;
}

#else
static bool openWithNnapi(Session&, const std::string&, int, uint32_t) { return false; }
#endif

static void fillNames(Session& out) {
    size_t n = 0;
    g_ort->SessionGetInputCount(out.s, &n);
    OrtAllocator* alloc = nullptr;
    g_ort->GetAllocatorWithDefaultOptions(&alloc);
    out.inNames.clear();
    out.outNames.clear();
    for (size_t i = 0; i < n; i++) {
        char* nm = nullptr;
        g_ort->SessionGetInputName(out.s, i, alloc, &nm);
        out.inNames.push_back(nm);
        g_ort->AllocatorFree(alloc, nm);
    }
    g_ort->SessionGetOutputCount(out.s, &n);
    for (size_t i = 0; i < n; i++) {
        char* nm = nullptr;
        g_ort->SessionGetOutputName(out.s, i, alloc, &nm);
        out.outNames.push_back(nm);
        g_ort->AllocatorFree(alloc, nm);
    }
}

static void openSession(Session& out, const std::string& path, int threads, bool preferNnapi = false) {
#ifdef __ANDROID__
    if (preferNnapi) {
        // GPU/DSP 优先：fp16 + NCHW，失败自动退回 CPU
        if (openWithNnapi(out, path, threads, NNAPI_FLAG_USE_FP16 | NNAPI_FLAG_USE_NCHW)) {
            g_epInfo = "nnapi";
        } else {
            out.s = nullptr;
        }
    }
#else
    (void)preferNnapi;
#endif
    if (!out.s) {
        OrtSessionOptions* opts = nullptr;
        g_ort->CreateSessionOptions(&opts);
        g_ort->SetIntraOpNumThreads(opts, threads);
        g_ort->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL);
        OrtStatus* st = g_ort->CreateSession(g_env, path.c_str(), opts, &out.s);
        check(st, path.c_str());
        g_ort->ReleaseSessionOptions(opts);
    }
    if (!out.s) return;
    fillNames(out);          // ★ NNAPI/CPU 都要填，否则 Run 时 0 个输出
}

std::string loadModels(const std::string& dir) {
    std::lock_guard<std::mutex> lk(g_mu);
#ifdef __ANDROID__
    if (!g_logFile) {
        std::string base = dir;
        size_t slash = base.find_last_of('/');
        if (slash != std::string::npos && slash > 0) base = base.substr(0, slash);
        std::string lp = base + "/pnocr_cpp.log";
        g_logFile = fopen(lp.c_str(), "a");
        if (g_logFile) { pnFileLog("=== pnocr start === dir=%s", dir.c_str()); }
    }
#endif
    if (!g_env) g_ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "pnocr", &g_env);
    g_modelDir = dir;
    if (!g_loaded) {
        // 重新加载前先释放旧会话，否则 2GB 内存会翻倍
        if (g_vis.s) { g_ort->ReleaseSession(g_vis.s); g_vis.s = nullptr; }
        if (g_emb.s) { g_ort->ReleaseSession(g_emb.s); g_emb.s = nullptr; }
        if (g_dec.s) { g_ort->ReleaseSession(g_dec.s); g_dec.s = nullptr; }
        g_vis.inNames.clear(); g_vis.outNames.clear();
        g_emb.inNames.clear(); g_emb.outNames.clear();
        g_dec.inNames.clear(); g_dec.outNames.clear();
        openSession(g_vis, dir + "/onnx/vision_encoder_fp16.onnx", 4, !g_cpuOnly);
        openSession(g_emb, dir + "/onnx/embed_tokens_q4.onnx", 2);
        openSession(g_dec, dir + "/onnx/decoder_model_merged_q4.onnx", 4);
        g_loaded = g_vis.s && g_emb.s && g_dec.s;
    }
    char buf[512];
    snprintf(buf, sizeof(buf), "vis=%d emb=%d dec=%d ep=%s decIn=%zu decOut=%zu",
             g_vis.s ? 1 : 0, g_emb.s ? 1 : 0, g_dec.s ? 1 : 0, g_epInfo.c_str(),
             g_dec.inNames.size(), g_dec.outNames.size());
    LOGI("%s", buf);
    return std::string(buf);
}

#ifdef __ANDROID__
extern "C" JNIEXPORT void JNICALL
Java_com_example_patchnotify_NativeOcr_setCpuOnly(JNIEnv*, jclass, jboolean v) {
    bool nv = v == JNI_TRUE;
    if (nv != g_cpuOnly) {
        g_loaded = false;
        g_epInfo = "cpu";
    }
    g_cpuOnly = nv;
    LOGI("cpuOnly=%d", (int)g_cpuOnly);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_patchnotify_NativeOcr_ocrCustom(JNIEnv* env, jclass, jbyteArray jrgb, jint w, jint h,
                                                 jintArray jids) {
    jsize len = env->GetArrayLength(jrgb);
    jbyte* jb = env->GetByteArrayElements(jrgb, nullptr);
    jsize n = env->GetArrayLength(jids);
    jint* ji = env->GetIntArrayElements(jids, nullptr);
    {
        std::lock_guard<std::mutex> lk(g_mu);
        g_customIds.assign(ji, ji + n);
        g_returnIds = true;
    }
    std::string r = runOcr((const uint8_t*)jb, w, h, -1);
    {
        std::lock_guard<std::mutex> lk(g_mu);
        g_customIds.clear();
        g_returnIds = false;
    }
    env->ReleaseIntArrayElements(jids, ji, JNI_ABORT);
    env->ReleaseByteArrayElements(jrgb, jb, JNI_ABORT);
    return env->NewStringUTF(r.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_patchnotify_NativeOcr_probeNnapi(JNIEnv* env, jclass, jstring jdir) {
    const char* d = env->GetStringUTFChars(jdir, nullptr);
    std::string dir(d ? d : "");
    if (d) env->ReleaseStringUTFChars(jdir, d);
    std::string r = probeNnapiDecode(dir);
    return env->NewStringUTF(r.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_patchnotify_NativeOcr_probe(JNIEnv* env, jclass, jstring jDir) {
    const char* d = env->GetStringUTFChars(jDir, nullptr);
    std::string r = loadModels(std::string(d));
    env->ReleaseStringUTFChars(jDir, d);
    return env->NewStringUTF(r.c_str());
}
#endif

// ------------------------------------------------------------------ 推理
#include "prompt_tokens.h"

#include <algorithm>

struct Buf {
    std::vector<float> f;
    std::vector<int64_t> i;
};

static OrtValue* tensorF(std::vector<float>& v, const std::vector<int64_t>& shape) {
    static float dummy = 0.f;
    OrtMemoryInfo* mi = nullptr;
    g_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mi);
    OrtValue* val = nullptr;
    void* data = v.empty() ? nullptr : (void*)v.data();
    OrtStatus* _st = g_ort->CreateTensorWithDataAsOrtValue(mi, data, v.size() * sizeof(float), shape.data(),
                                          shape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &val);
    if (_st) { LOGE("tensorF fail shape0=%lld: %s", (long long)(shape.empty()?-1:shape[0]), g_ort->GetErrorMessage(_st)); g_ort->ReleaseStatus(_st); }
    g_ort->ReleaseMemoryInfo(mi);
    return val;
}
static OrtValue* tensorI(std::vector<int64_t>& v, const std::vector<int64_t>& shape) {
    static int64_t dummy = 0;
    OrtMemoryInfo* mi = nullptr;
    g_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mi);
    OrtValue* val = nullptr;
    void* data = v.empty() ? (void*)&dummy : (void*)v.data();
    g_ort->CreateTensorWithDataAsOrtValue(mi, data, v.size() * sizeof(int64_t), shape.data(),
                                          shape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &val);
    g_ort->ReleaseMemoryInfo(mi);
    return val;
}

/** 运行一次 session；输入名与 OrtValue 由调用方给，输出拷进 out */
static bool runSession(Session& s, std::vector<const char*>& names, std::vector<OrtValue*>& vals,
                       std::vector<std::vector<float>>* outF,
                       std::vector<std::vector<int64_t>>* outI,
                       std::vector<std::string>* outNames) {
    OrtValue* outs[64] = {nullptr};
    std::vector<const char*> onames;
    for (auto& n : s.outNames) onames.push_back(n.c_str());
    OrtStatus* st = g_ort->Run(s.s, nullptr, names.data(), vals.data(), vals.size(),
                               onames.data(), onames.size(), outs);
    if (st) { LOGE("Run failed: %s", g_ort->GetErrorMessage(st)); g_ort->ReleaseStatus(st); return false; }
    if (outF) {
        outF->resize(s.outNames.size());
        for (size_t i = 0; i < s.outNames.size(); i++) {
            OrtTensorTypeAndShapeInfo* info = nullptr;
            g_ort->GetTensorTypeAndShape(outs[i], &info);
            size_t cnt = 0;
            g_ort->GetTensorShapeElementCount(info, &cnt);
            g_ort->ReleaseTensorTypeAndShapeInfo(info);
            const float* data = nullptr;
            g_ort->GetTensorMutableData(outs[i], (void**)&data);
            (*outF)[i].assign(data, data + cnt);
            g_ort->ReleaseValue(outs[i]);
        }
    }
    (void)outI; (void)outNames;
    return true;
}

// ---- KV / 线性注意力 cache ----
struct Cache {
    std::string present;                 // present_xxx
    std::string past;                    // past_xxx
    std::vector<float> data;
    std::vector<int64_t> shape;
};

static std::vector<Cache> g_cache;       // 由 probe 初始化（一次性）
static bool g_cacheInit = false;

static void initCacheShapes() {
    // 顺序与 decoder 输出一致：logits + present_*
    g_cache.clear();
    for (size_t i = 1; i < g_dec.outNames.size(); i++) {
        const std::string& n = g_dec.outNames[i];
        Cache c;
        c.present = n;
        c.past = "past_" + n.substr(8);  // present_ -> past_
        if (n.rfind("present_conv.", 0) == 0) { c.shape = {1, 6144, 4}; c.past = "past_conv." + n.substr(13); }
        else if (n.rfind("present_recurrent.", 0) == 0) { c.shape = {1, 16, 128, 128}; c.past = "past_recurrent." + n.substr(18); }
        else {
            // present.3.key -> past_key_values.3.key
            c.past = "past_key_values." + n.substr(8);
            c.shape = {1, 2, 0, 256};
        }
        size_t cnt = 1;
        for (auto d : c.shape) cnt *= (size_t)d;
        c.data.assign(cnt, 0.0f);
        g_cache.push_back(std::move(c));
    }
    g_cacheInit = true;
}


// ---------------------------------------------------------------------------
// 实验：decoder 的所有动态维固定成常量后交给 NNAPI，测 session 能否创建 + 每步耗时。
// 只做测速，不校验数值（权重全零输入也能暴露编译/回退行为）。
// ---------------------------------------------------------------------------
static std::string probeNnapiDecode(const std::string& dir) {
    std::lock_guard<std::mutex> lk(g_mu);
    const int MAXP = 32;
    char msg[2048];

    // 先释放已加载的会话，避免同时驻留两份 1.2 GB 权重
    if (g_vis.s) { g_ort->ReleaseSession(g_vis.s); g_vis.s = nullptr; }
    if (g_emb.s) { g_ort->ReleaseSession(g_emb.s); g_emb.s = nullptr; }
    if (g_dec.s) { g_ort->ReleaseSession(g_dec.s); g_dec.s = nullptr; }
    g_loaded = false;

    OrtSessionOptions* opts = nullptr;
    g_ort->CreateSessionOptions(&opts);
    g_ort->SetIntraOpNumThreads(opts, 4);
    OrtStatus* st = OrtSessionOptionsAppendExecutionProvider_Nnapi(opts, 0x001 /*FP16*/);
    if (st) {
        snprintf(msg, sizeof(msg), "NNAPI 注册失败: %s", g_ort->GetErrorMessage(st));
        g_ort->ReleaseStatus(st); g_ort->ReleaseSessionOptions(opts);
        return msg;
    }
    g_ort->AddFreeDimensionOverrideByName(opts, "batch_size", 1);
    g_ort->AddFreeDimensionOverrideByName(opts, "sequence_length", 1);
    g_ort->AddFreeDimensionOverrideByName(opts, "past_sequence_length", MAXP);
    g_ort->AddFreeDimensionOverrideByName(opts, "total_sequence_length", MAXP + 1);

    OrtSession* s = nullptr;
    auto t0 = std::chrono::steady_clock::now();
    st = g_ort->CreateSession(g_env, (dir + "/onnx/decoder_model_merged_q4.onnx").c_str(), opts, &s);
    double tc = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    if (st) {
        snprintf(msg, sizeof(msg), "NNAPI 建会话失败 (%.1fs): %s", tc, g_ort->GetErrorMessage(st));
        g_ort->ReleaseStatus(st); g_ort->ReleaseSessionOptions(opts);
        return msg;
    }
    g_ort->ReleaseSessionOptions(opts);

    size_t nIn = 0, nOut = 0;
    g_ort->SessionGetInputCount(s, &nIn);
    g_ort->SessionGetOutputCount(s, &nOut);

    std::vector<std::string> namesOwn;
    std::vector<const char*> inNames;
    std::vector<OrtValue*> vals;
    std::vector<std::vector<float>> fbuf;
    std::vector<std::vector<int64_t>> ibuf;
    fbuf.reserve(nIn); ibuf.reserve(nIn);

    for (size_t i = 0; i < nIn; i++) {
        OrtAllocator* alloc = nullptr;
        g_ort->GetAllocatorWithDefaultOptions(&alloc);
        char* nm = nullptr;
        g_ort->SessionGetInputName(s, i, alloc, &nm);
        namesOwn.push_back(nm ? nm : "");
        inNames.push_back(namesOwn.back().c_str());

        OrtTypeInfo* ti = nullptr;
        g_ort->SessionGetInputTypeInfo(s, i, &ti);
        const OrtTensorTypeAndShapeInfo* tsi = nullptr;
        g_ort->CastTypeInfoToTensorInfo(ti, &tsi);
        size_t nd = 0;
        g_ort->GetDimensionsCount(tsi, &nd);
        std::vector<int64_t> dims(nd);
        g_ort->GetDimensions(tsi, dims.data(), nd);
        ONNXTensorElementDataType et;
        g_ort->GetTensorElementType(tsi, &et);
        size_t cnt = 1;
        for (auto d : dims) cnt *= (size_t)(d > 0 ? d : 1);
        if (et == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
            fbuf.emplace_back(cnt, 0.01f);
            std::vector<int64_t> d2 = dims;
            vals.push_back(tensorF(fbuf.back(), d2));
        } else {
            ibuf.emplace_back(cnt, 0);
            std::vector<int64_t> d2 = dims;
            vals.push_back(tensorI(ibuf.back(), d2));
        }
        g_ort->ReleaseTypeInfo(ti);
        if (nm) alloc->Free(alloc, nm);
    }

    std::vector<std::string> outOwn(nOut);
    std::vector<const char*> outNames;
    for (size_t i = 0; i < nOut; i++) {
        OrtAllocator* alloc = nullptr;
        g_ort->GetAllocatorWithDefaultOptions(&alloc);
        char* nm = nullptr;
        g_ort->SessionGetOutputName(s, i, alloc, &nm);
        outOwn[i] = nm ? nm : "";
        outNames.push_back(outOwn[i].c_str());
        if (nm) alloc->Free(alloc, nm);
    }

    std::vector<OrtValue*> outs(nOut, nullptr);
    std::string timing;
    int steps = 3, ok = 0;
    for (int k = 0; k < steps; k++) {
        auto t1 = std::chrono::steady_clock::now();
        OrtStatus* rs = g_ort->Run(s, nullptr, inNames.data(), vals.data(), nIn,
                                   outNames.data(), nOut, outs.data());
        double dt = std::chrono::duration<double>(std::chrono::steady_clock::now() - t1).count();
        if (rs) {
            timing += std::string("step") + std::to_string(k) + "=ERR:" + g_ort->GetErrorMessage(rs) + " ";
            g_ort->ReleaseStatus(rs);
            break;
        }
        char b[64]; snprintf(b, sizeof(b), "%.2fs ", dt); timing += b;
        for (auto* v : outs) if (v) { g_ort->ReleaseValue(v); }
        ok++;
    }

    for (auto* v : vals) if (v) g_ort->ReleaseValue(v);
    g_ort->ReleaseSession(s);
    snprintf(msg, sizeof(msg), "NNAPI decoder: create=%.1fs inputs=%zu steps=%d/%d %s",
             tc, nIn, ok, steps, timing.c_str());
    LOGI("%s", msg);
    return msg;
}

std::string runOcr(const uint8_t* rgbPtr, int wid, int hei, int kindIdx) {
    std::vector<uint8_t> rgb(rgbPtr, rgbPtr + (size_t)wid * hei * 3);

    if (!g_loaded) return "ERR:model";
    if (!g_cacheInit) initCacheShapes();

    int W = 0, H = 0;
    smartResize(hei, wid, &H, &W);
    std::vector<float> chw((size_t)3 * W * H);
    resizeToCHW(rgb.data(), wid, hei, W, H, chw.data());

    std::vector<float> pv;
    patchify(chw.data(), W, H, pv);
    const int gh = H / PATCH, gw = W / PATCH;
    LOGI("resize in=%dx%d grid=%dx%d patches=%d tokens=%d", wid, hei, gh, gw, gh * gw, (gh * gw) / 4);
    const int nPatches = gh * gw;
    const int nImg = nPatches / (MERGE * MERGE);

    if (nImg > MAX_IMG_TOKENS) {
        char e[96];
        snprintf(e, sizeof(e), "ERR:TOOBIG:%d:%d", nImg, MAX_IMG_TOKENS);
        LOGI("%s", e);
        return std::string(e);
    }

    // 1) vision encoder
    std::vector<int64_t> grid = {1, gh, gw};
    std::vector<const char*> vin = {"pixel_values", "image_grid_thw"};
    OrtValue* vpv = tensorF(pv, {(int64_t)nPatches, PATCH_DIM});
    OrtValue* vgrid = tensorI(grid, {1, 3});
    std::vector<OrtValue*> vvals = {vpv, vgrid};
    std::vector<std::vector<float>> vout;
    auto _t0 = std::chrono::steady_clock::now();
    bool ok = runSession(g_vis, vin, vvals, &vout, nullptr, nullptr);
    LOGI("vision %.2fs ep=%s", std::chrono::duration<double>(std::chrono::steady_clock::now()-_t0).count(),
         g_epInfo.c_str());
    g_ort->ReleaseValue(vpv);
    g_ort->ReleaseValue(vgrid);

    if (!ok && g_epInfo == "nnapi") {
        // 手机上 NNAPI 常常在 CreateSession 阶段过、Run 阶段炸：退回 CPU 重跑
        LOGI("vision 在 NNAPI 上失败，改用 CPU 重试");
        g_ort->ReleaseSession(g_vis.s);
        g_vis.s = nullptr;
        g_vis.inNames.clear(); g_vis.outNames.clear();
        openSession(g_vis, g_modelDir + "/onnx/vision_encoder_fp16.onnx", 4, false);
        g_epInfo = "cpu";
        if (!g_vis.s) return "ERR:vision(cpu reload)";
        OrtValue* vpv2 = tensorF(pv, {(int64_t)nPatches, PATCH_DIM});
        OrtValue* vgrid2 = tensorI(grid, {1, 3});
        std::vector<OrtValue*> vvals2 = {vpv2, vgrid2};
        vout.clear();
        ok = runSession(g_vis, vin, vvals2, &vout, nullptr, nullptr);
        g_ort->ReleaseValue(vpv2);
        g_ort->ReleaseValue(vgrid2);
        LOGI("vision(cpu) %s", ok ? "ok" : "failed");
    }
    if (!ok) return "ERR:vision";
    std::vector<float>& feats = vout[0];

    // 2) prompt ids
    const int* pre = PREFIX_num;
    const int* suf = SUFFIX_num;
    size_t nPre = sizeof(PREFIX_num) / sizeof(int);
    size_t nSuf = sizeof(SUFFIX_num) / sizeof(int);
    if (kindIdx == 1) { pre = PREFIX_cn; suf = SUFFIX_cn; nPre = sizeof(PREFIX_cn)/sizeof(int); nSuf = sizeof(SUFFIX_cn)/sizeof(int); }
    else if (kindIdx == 2) { pre = PREFIX_en; suf = SUFFIX_en; nPre = sizeof(PREFIX_en)/sizeof(int); nSuf = sizeof(SUFFIX_en)/sizeof(int); }
    else if (kindIdx == 3) { pre = PREFIX_mix; suf = SUFFIX_mix; nPre = sizeof(PREFIX_mix)/sizeof(int); nSuf = sizeof(SUFFIX_mix)/sizeof(int); }

    static const int VISION_END = 248054;
    static const int ASSISTANT_TAIL[] = {248046, 198, 248045, 74455, 198, 248068, 271, 248069, 271};

    std::vector<int64_t> ids;
    ids.insert(ids.end(), pre, pre + nPre);
    size_t imgStart = ids.size();
    for (int i = 0; i < nImg; i++) ids.push_back(IMG_TOK);
    size_t imgEnd = ids.size();
    if (kindIdx < 0) {
        // 自定义指令：<vision_end> + 指令 token + <im_end>…<assistant>
        ids.push_back(VISION_END);
        for (int v : g_customIds) ids.push_back(v);
        ids.insert(ids.end(), ASSISTANT_TAIL, ASSISTANT_TAIL + sizeof(ASSISTANT_TAIL)/sizeof(int));
        LOGI("custom prompt: S=%zu imgStart=%zu imgEnd=%zu nCustom=%zu first=%d last=%d",
             ids.size(), imgStart, imgEnd, g_customIds.size(),
             g_customIds.empty() ? -1 : g_customIds[0], ids.back());
    } else {
        ids.insert(ids.end(), suf, suf + nSuf);
    }
    const int64_t S = (int64_t)ids.size();

    // 3) embed_tokens
    std::vector<const char*> ein = {"input_ids"};
    std::vector<int64_t> idShape = {1, S};
    OrtValue* vid = tensorI(ids, idShape);
    std::vector<OrtValue*> evals = {vid};
    std::vector<std::vector<float>> eout;
    auto _t1 = std::chrono::steady_clock::now();
    ok = runSession(g_emb, ein, evals, &eout, nullptr, nullptr);
    LOGI("embed %.2fs", std::chrono::duration<double>(std::chrono::steady_clock::now()-_t1).count());
    g_ort->ReleaseValue(vid);
    if (!ok) return "ERR:embed";
    std::vector<float> embeds = eout[0];

    // 4) 把图像特征贴进 image token 位置
    for (int i = 0; i < nImg; i++) {
        std::copy(feats.begin() + (size_t)i * HIDDEN, feats.begin() + (size_t)(i + 1) * HIDDEN,
                  embeds.begin() + (size_t)(imgStart + i) * HIDDEN);
    }


    // 5) M-RoPE position_ids（Qwen2-VL 规则：图像占 max(llmH,llmW) 个位置）
    std::vector<int64_t> pos((size_t)3 * S);
    std::vector<int64_t> prefillPos(3);
    {
        int64_t p = 0;
        const int llmH = gh / MERGE, llmW = gw / MERGE;
        for (int64_t i = 0; i < S; i++) {
            bool inImg = (i >= (int64_t)imgStart && i < (int64_t)imgEnd);
            if (inImg) {
                int k = (int)(i - imgStart);
                int r = k / llmW, c = k % llmW;
                pos[0 * S + i] = p;
                pos[1 * S + i] = p + r;
                pos[2 * S + i] = p + c;
            } else {
                if (i > 0 && i == (int64_t)imgEnd) p += std::max(llmH, llmW);
                pos[0 * S + i] = p;
                pos[1 * S + i] = p;
                pos[2 * S + i] = p;
                p++;
            }
        }
        prefillPos = {p, p, p};
    }

    std::vector<int64_t> mask(S, 1);
    std::vector<std::string> pieces;
    std::vector<int> genIds;
    std::vector<float> curEmb = embeds;
    int64_t total = S;
    int64_t nextPos = prefillPos[0];

    for (int step = 0; step < 16; step++) {
        std::vector<const char*> names;
        std::vector<OrtValue*> vals, trash;
        std::vector<std::vector<float>> ownF;
        std::vector<std::vector<int64_t>> ownI;
        names.reserve(64);
        vals.reserve(64);

        for (const std::string& nm : g_dec.inNames) {
            names.push_back(nm.c_str());
            if (nm == "inputs_embeds") {
                std::vector<int64_t> sh = {1, step == 0 ? S : 1, HIDDEN};
                ownF.push_back(curEmb);
                vals.push_back(tensorF(ownF.back(), sh));
                trash.push_back(vals.back());
            } else if (nm == "attention_mask") {
                ownI.push_back(mask);
                vals.push_back(tensorI(ownI.back(), {1, (int64_t)mask.size()}));
                trash.push_back(vals.back());
            } else if (nm == "position_ids") {
                std::vector<int64_t> slice;
                if (step == 0) slice = pos;
                else slice = {nextPos, nextPos, nextPos};
                ownI.push_back(slice);
                vals.push_back(tensorI(ownI.back(), {3, 1, step == 0 ? S : 1}));
                trash.push_back(vals.back());
            } else {
                OrtValue* v = nullptr;
                for (auto& c : g_cache) {
                    if (c.past == nm) {
                        ownF.push_back(c.data);
                        v = tensorF(ownF.back(), c.shape);
                        break;
                    }
                }
                vals.push_back(v);
                if (v) trash.push_back(v);
            }
        }

        std::vector<std::vector<float>> douts;
        auto _td = std::chrono::steady_clock::now();
        bool ok2 = runSession(g_dec, names, vals, &douts, nullptr, nullptr);
        LOGI("dec step%d %.2fs", step, std::chrono::duration<double>(std::chrono::steady_clock::now()-_td).count());
        for (auto* v : trash) if (v) g_ort->ReleaseValue(v);
        if (!ok2) return "ERR:decode";

        const int64_t V = 248320;
        std::vector<float>& logits = douts[0];
        size_t base = (logits.size() / (size_t)V - 1) * (size_t)V;
        int best = 0;
        float bv = -1e30f;
        for (int64_t i = 0; i < V; i++) {
            float v = logits[base + i];
            if (v > bv) { bv = v; best = (int)i; }
        }
        LOGI("tok step%d = %d", step, best);
        genIds.push_back(best);
        if (best == EOS || (int)pieces.size() >= 12) break;
        if (best >= 15 && best <= 24) pieces.push_back(std::string(1, (char)('0' + (best - 15))));
        else if (best == 13) pieces.push_back(".");
        else if (best == 43910) break;   // NONE

        for (size_t i = 1; i < g_dec.outNames.size(); i++)
            for (auto& c : g_cache)
                if (c.present == g_dec.outNames[i]) {
                    c.data = douts[i];
                    // 关键：past_length 从 0 长起来，形状要跟着更新
                    if (c.shape.size() == 4) {
                        size_t denom = (size_t)c.shape[0] * c.shape[1] * c.shape[3];
                        c.shape[2] = (int64_t)(c.data.size() / denom);
                    }
                    break;
                }

        std::vector<int64_t> nid = {best};
        ownI.push_back(nid);
        OrtValue* nv = tensorI(ownI.back(), {1, 1});
        std::vector<const char*> ein2 = {"input_ids"};
        std::vector<OrtValue*> nvals = {nv};
        std::vector<std::vector<float>> nout;
        bool ok3 = runSession(g_emb, ein2, nvals, &nout, nullptr, nullptr);
        g_ort->ReleaseValue(nv);
        if (!ok3) break;
        curEmb = nout[0];
        total++;
        mask.push_back(1);
        nextPos++;
    }

    std::string res;
    for (auto& s : pieces) res += s;
    {
        std::string ids;
        for (int id : genIds) { if (!ids.empty()) ids += ","; ids += std::to_string(id); }
        LOGI("ocr result: \"%s\" ids=[%s]", res.c_str(), ids.c_str());
    }
    if (g_returnIds) {
        std::string ids;
        for (int id : genIds) { if (!ids.empty()) ids += ","; ids += std::to_string(id); }
        return ids;
    }
    return res;
}

#ifdef __ANDROID__
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_patchnotify_NativeOcr_ocr(JNIEnv* env, jclass, jbyteArray jrgb, jint w, jint h,
                                           jint kindIdx) {
    jsize len = env->GetArrayLength(jrgb);
    jbyte* jb = env->GetByteArrayElements(jrgb, nullptr);
    std::string r = runOcr((const uint8_t*)jb, w, h, kindIdx);
    env->ReleaseByteArrayElements(jrgb, jb, JNI_ABORT);
    return env->NewStringUTF(r.c_str());
}
#endif

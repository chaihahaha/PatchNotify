// tenz 的 JNI 薄封装：把 assets 里的 .tenz 解回 .gguf。
// tenz.c 是官方单文件实现（Apache-2.0，见 cpp/tenz/NOTICE），编译时把它的 main 改名成 tenz_main。
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

extern "C" int tenz_main(int argc, char** argv);

#define TAG "PatchNotify"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_example_patchnotify_Tenz_unpack(JNIEnv* env, jclass, jstring jIn, jstring jOut, jint threads) {
    const char* in = env->GetStringUTFChars(jIn, nullptr);
    const char* out = env->GetStringUTFChars(jOut, nullptr);
    if (!in || !out) return -1;
    std::string tin(in), tout(out);
    env->ReleaseStringUTFChars(jIn, in);
    env->ReleaseStringUTFChars(jOut, out);

    std::vector<std::string> args = {"tenz", "d", tin, tout, "-f",
                                     "-t", std::to_string(threads > 0 ? threads : 4)};
    std::vector<char*> argv;
    argv.reserve(args.size() + 1);
    for (auto& a : args) argv.push_back(const_cast<char*>(a.c_str()));
    argv.push_back(nullptr);

    LOGI("tenz: 解压 %s -> %s (threads=%d)", tin.c_str(), tout.c_str(), threads);
    int rc = tenz_main((int)argv.size() - 1, argv.data());
    LOGI("tenz: 返回 %d", rc);
    return rc;
}

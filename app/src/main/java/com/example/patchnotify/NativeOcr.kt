package com.example.patchnotify

/** Qwen OCR 的本地 C++ 推理入口（ONNX Runtime C API） */
object NativeOcr {
    init {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("pnocr")
    }

    /** 检查模型能否加载，返回 ORT 版本/输入名等信息 */
    external fun probe(modelDir: String): String

    /**
     * 本地识别一张 RGB888 图。kind: 0=num 1=cn 2=en 3=mix
     * 返回识别到的字符串；ERR:xxx 表示失败
     */
    /** true = 强制 CPU（关掉 NNAPI 加速） */
    external fun setCpuOnly(cpuOnly: Boolean)

    external fun ocr(rgb: ByteArray, w: Int, h: Int, kind: Int): String
}

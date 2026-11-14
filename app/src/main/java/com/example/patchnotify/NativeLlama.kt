package com.example.patchnotify

import java.io.File

/** llama.cpp 后端（GGUF + mmproj）的 JNI 声明 */
object NativeLlama {
    init {
        // 顺序：ggml 基础库 -> CPU 后端 -> llama -> mtmd -> JNI 封装层
        System.loadLibrary("ggml-base")
        System.loadLibrary("ggml-cpu")
        System.loadLibrary("llama")
        System.loadLibrary("mtmd")
        System.loadLibrary("pnllama")
    }

    external fun setLogPath(path: String)
    external fun init(modelPath: String, mmprojPath: String, nThreads: Int): String
    external fun infer(rgb: ByteArray, w: Int, h: Int, prompt: String, maxTok: Int,
                     allowThink: Boolean): String
    external fun release()
}

object Vlm {

    private var ready = false
    private var modelDirCached = ""
    var lastModelName = ""

    // 实测 0.8B 无论怎么量化都会读错首位，只认 2B
    private val MODEL_CANDIDATES = listOf(
        "model.gguf",              // 想测哪个量化，就把那个文件改名成 model.gguf，优先级最高
        "Qwen3.5-2B-Q4_0.gguf",
        "Qwen3.5-2B-UD-Q3_K_XL.gguf",
        "Qwen3.5-2B-UD-Q4_K_XL.gguf",
        "Qwen3.5-2B-UD-Q2_K_XL.gguf"
    )
    private val MMPROJ_CANDIDATES = listOf(
        "mmproj-F16.gguf",
        "mmproj-BF16.gguf",
        "mmproj.gguf"
    )

    private fun firstExisting(dir: String, names: List<String>): File? =
        names.map { File(dir, it) }.firstOrNull { it.exists() }

    /** 把一句话条件交给模型，返回它的原话（应是/否） */
    fun ask(rgb: ByteArray, w: Int, h: Int, ask: String, allowThink: Boolean = false): String {
        if (!ready) return "ERR:模型未加载"
        // 不思考只给 6 个 token；思考给 800（实测这条问句的思考链能到 500+ token，
        // 给 400 会在句子中间被截断，于是 "</think>" 永远不出现，整段思考被当成答案）
        val budget = if (allowThink) 800 else 6
        val out = NativeLlama.infer(rgb, w, h, Condition.prompt(ask), budget, allowThink).trim()
        // 思考没闭合 = 被 maxTok 截断，不能拿去判断
        if (out.contains("<think>") && !out.contains("</think>")) return "ERR:THINK_TRUNC"
        return out
    }

    fun probe(dir: String): String {
        val model = firstExisting(dir, MODEL_CANDIDATES)
        val mmproj = firstExisting(dir, MMPROJ_CANDIDATES)
        if (model == null) return "ERR:缺少模型 " + MODEL_CANDIDATES[0]
        if (mmproj == null) return "ERR:缺少 mmproj " + MMPROJ_CANDIDATES[0]

        val logFile = File(File(dir).parentFile ?: File(dir), "native.log")
        NativeLlama.setLogPath(logFile.absolutePath)
        // 大核只有 4 个（X4×4 + A720×4），线程数超过大核数会让每个算子都被小核拖住
        var threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        try {
            val tf = File(dir, "threads.txt")
            if (tf.exists()) threads = tf.readText().trim().toInt().coerceIn(1, 8)
        } catch (_: Throwable) { }
        val r = NativeLlama.init(model.absolutePath, mmproj.absolutePath, threads)
        lastModelName = model.name
        ready = r == "ok"
        modelDirCached = dir
        return if (ready) "ok threads=$threads model=$lastModelName" else r
    }
}

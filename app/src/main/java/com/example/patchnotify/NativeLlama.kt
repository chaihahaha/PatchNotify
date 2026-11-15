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
                       allowThink: Boolean, grammar: String?): String
    external fun release()
}

object Vlm {
    /** 只允许输出「是」或「否」 */
    private const val YES_NO_GBNF = "root ::= \"是\" | \"否\""

    /**
     * 允许思考时的结构化语法：think 块最多 THINK_MAX_CHARS 个字符，之后必须闭合标签并输出是/否。
     *
     * 注意 GBNF 数的是字符不是 token，中文大约 1 字 ≈ 1 token，
     * 400 字对应 300~400 token 左右。调上限改 THINK_MAX_CHARS 即可。
     *
     * THINK_MIN_CHARS 只是防止模型直接跳过思考（实测设为 0 时它会吐一个空 think 块
     * 然后立刻下结论，等于没思考），不是要求它必须写满这么多。
     */
    private const val THINK_MAX_CHARS = 20
    private const val THINK_MIN_CHARS = 1
    private val THINK_YES_NO_GBNF: String
        get() = "root ::= \"<think>\" body \"</think>\" (\"是\" | \"否\")\n" +
                "body ::= [^<]{$THINK_MIN_CHARS,${THINK_MAX_CHARS}}"


    private var ready = false
    private var modelDirCached = ""
    var lastModelName = ""

    // 实测 0.8B 无论怎么量化都会读错首位，只认 2B
    private val MODEL_CANDIDATES = listOf(
        "model.gguf",              // 想测哪个量化，就把那个文件改名成 model.gguf，优先级最高
        "Qwen3.5-2B-Q4_0.gguf",
        "Qwen3.5-2B-UD-Q3_K_XL.gguf",
        "Qwen3.5-2B-UD-Q4_K_XL.gguf",
        "Qwen3.5-2B-UD-Q2_K_XL.gguf",
        "Qwen3.5-2B-UD-IQ2_M.gguf"          // 安装包自带的那个
    )
    private val MMPROJ_CANDIDATES = listOf(
        "mmproj-F16.gguf",
        "mmproj-BF16.gguf",
        "mmproj-Q8_0.gguf",                 // 安装包自带的那个
        "mmproj.gguf"
    )

    private fun firstExisting(dir: String, names: List<String>): File? =
        names.map { File(dir, it) }.firstOrNull { it.exists() }

    /** 把一句话条件交给模型，返回它的原话（应是/否） */
    fun ask(
        rgb: ByteArray, w: Int, h: Int, ask: String,
        allowThink: Boolean = false, openAnswer: Boolean = false
    ): String {
        if (!ready) return "ERR:模型未加载"
        // 不再按回答类型卡 token 数：生成到 EOG / <|im_end|> 自然就停，
        // 上限只用来兜住跑飞的输出（受 n_ctx=4096 约束）
        val budget = 2048
        val promptText = if (openAnswer) Condition.questionPrompt(ask) else Condition.prompt(ask)
        // 是/否判断用 GBNF 把输出钉死：
        // 不思考 -> 只能是「是」或「否」；思考 -> 必须走完整的 think 块再给结论，
        // 思考过程也一并结构化，永远不会出现「标签没闭合、思维链被当答案」。
        val grammar = when {
            openAnswer -> null
            allowThink -> THINK_YES_NO_GBNF
            else -> YES_NO_GBNF
        }
        val out = NativeLlama.infer(rgb, w, h, promptText, budget, allowThink, grammar).trim()
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

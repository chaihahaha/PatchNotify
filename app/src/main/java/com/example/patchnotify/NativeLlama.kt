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
    external fun marker(): String
    external fun infer(images: Array<ByteArray>, widths: IntArray, heights: IntArray,
                       prompt: String, maxTok: Int, allowThink: Boolean, grammar: String?): String
    external fun release()
}

/** 一张已经转成 RGB 的图，供多图推理使用 */
data class VlmImageInput(val rgb: ByteArray, val width: Int, val height: Int)

/** 参考图 + 用户起的名字/说明；提示词里会写成「参考图1（名称）：说明」 */
data class VlmReferenceInput(
    val image: VlmImageInput,
    val name: String,
    val description: String
)

object Vlm {
    /** 只允许输出「是」或「否」 */
    private const val YES_NO_GBNF = "root ::= \"是\" | \"否\""

    /**
     * 允许思考时的结构化语法：
     *   think 段总长 5~40 字，且**第一个字符不能是空格/制表/换行**。
     *
     * 后一条是必须的：只写 {5,40} 的话，模型会先用「空行 + 空格」填满长度再直接下结论，
     * 时间花了但根本没思考。强制首字符非空白后，它只能老老实实写字。
     *
     * GBNF 数的是字符不是 token（中文约 1 字 ≈ 1 token）。
     */
    private const val THINK_MIN_CHARS = 5
    private const val THINK_MAX_CHARS = 120
    private val THINK_YES_NO_GBNF: String
        get() = "root ::= \"<think>\" body \"</think>\" (\"是\" | \"否\")\n" +
                "body ::= [^< \\t\\n\\r] [^<]{${THINK_MIN_CHARS - 1},${THINK_MAX_CHARS - 1}}"

    @Volatile private var ready = false
    @Volatile var lastModelName = ""

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

    /** 判断条件 + 命中后的追问，返回模型原话（判断时是/否，追问时是答案） */
    fun ask(
        rgb: ByteArray, w: Int, h: Int, condition: String, question: String,
        references: List<VlmReferenceInput> = emptyList(),
        allowThink: Boolean = false, openAnswer: Boolean = false
    ): String {
        if (!ready) return "ERR:模型未加载"
        // 不再按回答类型卡 token 数：生成到 EOG / <|im_end|> 自然就停，
        // 上限只用来兜住跑飞的输出（受 n_ctx=4096 约束）
        val budget = 2048
        val marker = NativeLlama.marker()
        if (marker.isEmpty()) return "ERR:no_marker"
        // 用户文字里如果碰巧带了图像占位符，会把图片数量算错，直接删掉
        fun clean(s: String) = s.replace(marker, "").trim()
        val safeCondition = clean(condition)
        val safeQuestion = clean(question)
        val images = ArrayList<ByteArray>()
        val widths = ArrayList<Int>()
        val heights = ArrayList<Int>()

        val promptText: String
        if (references.isEmpty()) {
            images.add(rgb); widths.add(w); heights.add(h)
            val tail = when {
                openAnswer -> Condition.questionPrompt(safeCondition, safeQuestion)
                allowThink -> Condition.thinkPrompt(safeCondition)
                else -> Condition.prompt(safeCondition)
            }
            // 旧行为保持不变：单图直接放在 prompt 开头
            promptText = marker + tail
        } else {
            val sb = StringBuilder()
            references.forEachIndexed { i, ref ->
                sb.append("参考图").append(i + 1)
                val refName = clean(ref.name)
                val refDesc = clean(ref.description)
                if (refName.isNotEmpty()) sb.append("（").append(refName).append("）")
                if (refDesc.isNotEmpty()) sb.append("：").append(refDesc)
                sb.append("\n").append(marker).append("\n")
                images.add(ref.image.rgb)
                widths.add(ref.image.width)
                heights.add(ref.image.height)
            }
            // 最后一张固定是实时画面，并在文字里明确命名，避免模型把参考图当成当前画面
            sb.append("实时监控画面（手机摄像头当前帧，最后一张图）\n")
                .append(marker).append("\n")
            images.add(rgb); widths.add(w); heights.add(h)
            sb.append("请比较前面的参考图与最后的实时监控画面。")
            sb.append(when {
                openAnswer -> "判断条件：$safeCondition。问题：$safeQuestion。" +
                        "参考图只用于比较，回答以最后的实时监控画面为准；直接给出答案，不要解释。"
                allowThink -> "判断条件：$safeCondition。思考时只描述最后的实时监控画面，不要描述或复述前面的参考图，也不要把参考图当成当前画面；然后给出「是」或「否」的结论。"
                else -> "判断条件：$safeCondition。只回答「是」或「否」，不要解释。"
            })
            promptText = sb.toString()
        }
        // 是/否判断用 GBNF 把输出钉死：
        // 不思考 -> 只能是「是」或「否」；思考 -> 必须走完整的 think 块再给结论，
        // 思考过程也一并结构化，永远不会出现「标签没闭合、思维链被当答案」。
        val grammar = when {
            openAnswer -> null
            allowThink -> THINK_YES_NO_GBNF
            else -> YES_NO_GBNF
        }
        val out = NativeLlama.infer(
            images.toTypedArray(), widths.toIntArray(), heights.toIntArray(),
            promptText, budget, allowThink, grammar
        ).trim()
        // 思考没闭合 = 被 maxTok 截断，不能拿去判断
        if (out.contains("<think>") && !out.contains("</think>")) return "ERR:THINK_TRUNC"
        return out
    }

    @Synchronized
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
        return if (ready) "ok threads=$threads model=$lastModelName" else r
    }
}

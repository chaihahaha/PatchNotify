package com.example.patchnotify

/**
 * 一条自然语言规则，形如：
 *   "数码管读数是否已接近 001680。快充好了"
 * 句号前是判断条件，句号后（可选）是命中时播报的那句话。
 * 没有句号 → 整句都是条件，命中只响提示音。
 */
object AskRule {

    private val SPLIT = charArrayOf('。', '.', '！', '!', '；', ';')

    /** 拆成 (条件, 播报文本)；小数点的 "." 不会被当成句号 */
    fun split(ask: String): Pair<String, String> {
        val t = ask.trim()
        if (t.isEmpty()) return "" to ""
        for (i in t.indices) {
            if (t[i] !in SPLIT) continue
            // "3.5" 这种小数点：后面紧跟数字就跳过
            val next = t.getOrNull(i + 1)
            if (t[i] == '.' && next != null && next.isDigit()) continue
            val cond = t.substring(0, i).trim()
            val rest = t.substring(i + 1).trim()
            if (cond.isEmpty()) return t to ""       // 句子以句号开头，整句当条件
            return cond to rest
        }
        return t to ""
    }

    fun conditionOf(ask: String) = split(ask).first

    fun speechOf(ask: String) = split(ask).second

    /** 交给模型的完整提示词：封闭问句，只允许回答是/否 */
    fun prompt(ask: String): String {
        val cond = conditionOf(ask)
        return "看画面回答问题：$cond。只回答「是」或「否」，不要解释。"
    }

    /**
     * 解析模型输出。返回 null = 没读懂（既没说是否）。
     * 注意「不是 / 没有」里也含「是」，所以要先判否。
     */
    fun parse(raw: String): Boolean? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val no = t.contains("否") || t.contains("不是") || t.contains("没") ||
                t.contains("no", true) || t == "0"
        val yes = t.contains("是") || t.contains("对") || t.contains("yes", true) || t == "1"
        return when {
            no -> false
            yes -> true
            else -> null
        }
    }
}

/**
 * 数值型条件在本地用代码比较，不交给模型判断。
 *
 * 实测（Qwen3.5-2B Q4_0，同一张读数 001679 的图）：
 *   "读数是否已超过5000"  → 是（错）
 *   "读数接近001680吗"    → 否（错）
 *   "画面中是否有一只猫"  → 是（错，换个问法又答否）
 * 模型对封闭问句有明显 yes-bias 且不稳定；但它把数字读出来是可靠的（001679 稳定正确）。
 * 所以：能解析成数值比较的，就读数字 + 代码比较；解析不了的才交给模型。
 */
data class NumCond(val op: String, val value: Double, val tol: Double)

fun numericCond(ask: String): NumCond? {
    val c = AskRule.conditionOf(ask)
    val n = "(-?\\d+(?:\\.\\d+)?)"
    Regex("(?:超过|大于|高于|>)\\s*$n").find(c)?.let {
        return NumCond("gt", it.groupValues[1].toDouble(), 0.0)
    }
    Regex("(?:小于|低于|少于|<)\\s*$n").find(c)?.let {
        return NumCond("lt", it.groupValues[1].toDouble(), 0.0)
    }
    Regex("(?:等于|=)\\s*$n").find(c)?.let {
        return NumCond("eq", it.groupValues[1].toDouble(), 0.0)
    }
    Regex("(?:接近|约为|差不多|≈)\\s*$n").find(c)?.let {
        val v = it.groupValues[1].toDouble()
        // 容差取 1% 与 10 的较大者：001680 → ±16.8，001679 算「接近」
        return NumCond("near", v, maxOf(v * 0.01, 10.0))
    }
    return null
}

fun test(cond: NumCond, x: Double): Boolean = when (cond.op) {
    "gt" -> x > cond.value
    "lt" -> x < cond.value
    "eq" -> kotlin.math.abs(x - cond.value) < 1e-9
    "near" -> kotlin.math.abs(x - cond.value) <= cond.tol
    else -> false
}

/** 从模型输出里抠出第一个数字（001679 / 1,679 / 16.79 都能处理） */
fun numberFrom(raw: String): Double? {
    val t = raw.replace(",", "").replace(" ", "")
    val m = Regex("-?\\d+(?:\\.\\d+)?").find(t) ?: return null
    return m.value.toDoubleOrNull()
}

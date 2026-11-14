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

    /** 句号后面那句：条件成立时让模型回答的问题 */
    fun questionOf(ask: String) = split(ask).second

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


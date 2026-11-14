package com.example.patchnotify

/**
 * 一句话条件，形如：
 *   "读数是否已接近001680。还需要充多久？"
 * 「。」前是判断条件，后面（可选）是条件成立时要问模型的问题。
 * 只有中文句号能分隔，逗号、分号、问号、感叹号都算条件本身的一部分。
 * 没有「。」→ 整句都是条件，成立时只响提示音。
 */
object Condition {

    /** 只有「。」能分隔条件和问题，其它标点一律待在原句里 */
    fun split(ask: String): Pair<String, String> {
        val t = ask.trim()
        if (t.isEmpty()) return "" to ""
        val i = t.indexOf('。')
        if (i < 0) return t to ""
        val cond = t.substring(0, i).trim()
        if (cond.isEmpty()) return t to ""           // 以句号开头，整句当条件
        return cond to t.substring(i + 1).trim()
    }

    fun conditionOf(ask: String) = split(ask).first

    /** 句号后面那句：条件成立时让模型回答的问题 */
    fun questionOf(ask: String) = split(ask).second

    /** 第一段：封闭问句，只允许回答是/否 */
    fun prompt(ask: String): String {
        val cond = conditionOf(ask)
        return "看画面回答问题：$cond。只回答「是」或「否」，不要解释。"
    }

    /** 第二段：条件成立后追问的那句，要的是内容而不是是/否 */
    fun questionPrompt(question: String): String {
        // 用户写的时候通常自带问号，别再拼一个「。」变成「？。」
        val q = question.trim().trimEnd('。', '？', '?', '！', '!', '.')
        return "看画面回答问题：$q。直接给出答案，不要解释。"
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


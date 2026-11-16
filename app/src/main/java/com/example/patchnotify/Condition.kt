package com.example.patchnotify

/**
 * 条件/问题在 UI 上已经是两个独立字段；split() 只保留给旧配置迁移用。
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

    /**
     * 允许思考时的第一段：要求它先把看到的东西写出来再下结论。
     * 不写死具体领域（数字/文字/形状都适用），只要求「描述」。
     */
    fun thinkPrompt(condition: String): String {
        val cond = condition.trim()
        return "看画面回答问题：$cond。先在思考里描述你看到的内容，再给出「是」或「否」的结论。"
    }

    /** 第一段：封闭问句，只允许回答是/否 */
    fun prompt(condition: String): String {
        val cond = condition.trim()
        return "看画面回答问题：$cond。只回答「是」或「否」，不要解释。"
    }

    /**
     * 第二段：条件成立后追问的那句，要的是内容而不是是/否。
     * 把这一行的判断条件一起带上 —— 用户常常在那里写清"看哪个区域、什么格式、几位数"，
     * 第二次推理时模型看不到上下文，准确率会掉。
     */
    fun questionPrompt(condition: String, question: String): String {
        val cond = condition.trim()
        val q = question.trim().trimEnd('。', '？', '?', '！', '!', '.')
        return if (cond.isBlank()) {
            "看画面回答问题：$q。直接给出答案，不要解释。"
        } else {
            "画面描述：$cond。问题：$q。直接给出答案，不要解释。"
        }
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


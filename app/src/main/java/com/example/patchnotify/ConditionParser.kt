package com.example.patchnotify

/**
 * 条件表达式解析器（纯 Kotlin，无 Android 依赖，可单元测试）
 *
 * 支持的书写形式（有无空格都行，中文数字会转成阿拉伯数字）：
 *   温度 > 80          温度大于八十        温度 >= 80
 *   温度 < 30          温度低于三十
 *   温度 = 25          温度等于25
 *   状态 != 正常
 *   温度 在 20 到 30 之间     温度在20和30之间    温度 20..30
 *   状态 包含 报警
 */
enum class Op { GT, GE, LT, LE, EQ, NE, BETWEEN, CONTAINS, NOT_CONTAINS }

data class Condition(
    val field: String,
    val op: Op,
    val num: Double? = null,
    val num2: Double? = null,
    val text: String? = null
) {
    fun expr(): String = when (op) {
        Op.GT -> "$field > ${fmt(num)}"
        Op.GE -> "$field >= ${fmt(num)}"
        Op.LT -> "$field < ${fmt(num)}"
        Op.LE -> "$field <= ${fmt(num)}"
        Op.EQ -> if (num != null) "$field = ${fmt(num)}" else "$field = ${text}"
        Op.NE -> if (num != null) "$field != ${fmt(num)}" else "$field != ${text}"
        Op.BETWEEN -> "$field 在 ${fmt(num)} 到 ${fmt(num2)} 之间"
        Op.CONTAINS -> "$field 包含 ${text}"
        Op.NOT_CONTAINS -> "$field 不含 ${text}"
    }

    fun describe(): String = expr()

    /** 用 OCR 得到的原始文本判断是否满足条件。数字会从文本中抽取（允许带单位）。 */
    fun test(rawText: String): Boolean {
        val raw = rawText.trim()
        val number = extractNumber(raw)
        return when (op) {
            Op.GT -> number != null && num != null && number > num
            Op.GE -> number != null && num != null && number >= num
            Op.LT -> number != null && num != null && number < num
            Op.LE -> number != null && num != null && number <= num
            Op.EQ -> if (num != null) number != null && kotlin.math.abs(number - num) < 1e-9
                    else raw.equals(text, ignoreCase = true)
            Op.NE -> if (num != null) !(number != null && kotlin.math.abs(number - num) < 1e-9)
                    else !raw.equals(text, ignoreCase = true)
            Op.BETWEEN -> number != null && num != null && num2 != null && number in num..num2
            Op.CONTAINS -> raw.contains(text ?: return false, ignoreCase = true)
            Op.NOT_CONTAINS -> !raw.contains(text ?: return true, ignoreCase = true)
        }
    }

    /**
     * 用已经从文本里取好的数字判断（数字型运算符）。
     * 区域里有多组数字时由 pickNumber 决定取哪个。
     */
    fun testNumber(n: Double?): Boolean = when (op) {
        Op.GT -> n != null && num != null && n > num
        Op.GE -> n != null && num != null && n >= num
        Op.LT -> n != null && num != null && n < num
        Op.LE -> n != null && num != null && n <= num
        Op.EQ -> if (num != null) n != null && kotlin.math.abs(n - num) < 1e-9
                else false
        Op.NE -> if (num != null) n != null && kotlin.math.abs(n - num) >= 1e-9 else false
        Op.BETWEEN -> n != null && num != null && num2 != null && n in num..num2
        Op.CONTAINS, Op.NOT_CONTAINS -> false
    }

    private fun fmt(d: Double?): String =
        if (d == null) "" else if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    companion object {
        private val NUMBER = Regex("[-+]?\\d+(?:\\.\\d+)?")

        fun extractNumber(s: String): Double? = numbersIn(s).firstOrNull()

        /** 取出文本里所有数字（按出现顺序） */
        fun numbersIn(s: String): List<Double> {
            val t = s.replace("．", ".")
            // 只在"含数字的词"里做易混字母纠正，避免把 TIME / OMRON 这类词当成数字
            val cleaned = Regex("[A-Za-z0-9]+").replace(t) { m ->
                val tok = m.value
                if (tok.any { it.isDigit() }) {
                    tok.replace("O", "0").replace("o", "0")
                        .replace("l", "1").replace("I", "1").replace("|", "1")
                } else tok
            }
            return NUMBER.findAll(cleaned).mapNotNull { it.value.toDoubleOrNull() }.toList()
        }

        /**
         * 按取数模式挑一个数字。
         * max：多组数字时取最大（计数器 "00 1679" 这类显示屏的正确解法）
         */
        fun pickNumber(raw: String, mode: String): Double? {
            val list = numbersIn(raw)
            if (list.isEmpty()) return null
            return when (mode) {
                "first" -> list.first()
                "last" -> list.last()
                else -> list.maxOrNull()
            }
        }
    }
}

object ConditionParser {

    private data class OpWord(val word: String, val op: Op)

    // 长的放前面，保证"大于等于"不会被"大于"抢先匹配
    private val OP_WORDS = listOf(
        OpWord("大于等于", Op.GE), OpWord("不小于", Op.GE), OpWord(">=", Op.GE), OpWord("≥", Op.GE),
        OpWord("小于等于", Op.LE), OpWord("不大于", Op.LE), OpWord("<=", Op.LE), OpWord("≤", Op.LE),
        OpWord("不等于", Op.NE), OpWord("!=", Op.NE), OpWord("≠", Op.NE),
        OpWord("大于", Op.GT), OpWord("高于", Op.GT), OpWord("超过", Op.GT), OpWord(">", Op.GT),
        OpWord("小于", Op.LT), OpWord("低于", Op.LT), OpWord("<", Op.LT),
        OpWord("等于", Op.EQ), OpWord("=", Op.EQ), OpWord("是", Op.EQ), OpWord("为", Op.EQ),
        OpWord("包含", Op.CONTAINS), OpWord("含有", Op.CONTAINS), OpWord("含", Op.CONTAINS),
        OpWord("不含", Op.NOT_CONTAINS), OpWord("不包含", Op.NOT_CONTAINS),
        OpWord("在", Op.BETWEEN), OpWord("介于", Op.BETWEEN)
    ).sortedByDescending { it.word.length }

    /** 全角转半角、去空白。保留中文。 */
    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s.trim()) {
            val code = c.code
            when {
                code == 0x3000 -> sb.append(' ')
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** 把中文数字（含"点"）转成阿拉伯数字，用于条件里的数值部分。 */
    fun chineseToArabic(input: String): String {
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        val units = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
        if (input.none { it in digits.keys || it in units.keys }) return input

        var result = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] !in digits.keys && input[i] !in units.keys) {
                result.append(input[i]); i++; continue
            }
            // 抓一段连续的中文数字串
            val start = i
            while (i < input.length && (input[i] in digits.keys || input[i] in units.keys)) i++
            val chunk = input.substring(start, i)
            result.append(convertChunk(chunk, digits, units))
        }
        return result.toString()
    }

    private fun convertChunk(
        chunk: String,
        digits: Map<Char, Int>,
        units: Map<Char, Int>
    ): String {
        // 先处理"点"（以 点 分隔的读法这里不拆分，交给上层按位处理）
        var total = 0L
        var section = 0L
        var number = 0L
        var hasAny = false
        for (c in chunk) {
            when {
                digits.containsKey(c) -> { number = digits[c]!!.toLong(); hasAny = true }
                units.containsKey(c) -> {
                    val u = units[c]!!
                    if (u == 10000) {
                        section = (section + if (number == 0L) 1 else number) * u
                        total += section
                        section = 0
                    } else {
                        section += (if (number == 0L) 1 else number) * u
                    }
                    number = 0
                    hasAny = true
                }
                else -> return chunk
            }
        }
        if (!hasAny) return chunk
        total += section + number
        return total.toString()
    }

    /** 把条件表达式（不含字段名）解析成 Condition。解析失败返回 null。 */
    fun parse(field: String, exprRaw: String): Condition? {
        val expr = chineseToArabic(normalize(exprRaw))
        if (expr.isEmpty()) return null

        // 范围写法：20..30 或 20~30
        Regex("([-+]?\\d+(?:\\.\\d+)?)\\s*(?:\\.\\.|~|至|-)\\s*([-+]?\\d+(?:\\.\\d+)?)").find(expr)?.let { m ->
            val a = m.groupValues[1].toDoubleOrNull() ?: return null
            val b = m.groupValues[2].toDoubleOrNull() ?: return null
            return Condition(field, Op.BETWEEN, num = minOf(a, b), num2 = maxOf(a, b))
        }

        val hit = OP_WORDS.firstOrNull { expr.contains(it.word) } ?: return null
        val valuePart = expr.substringAfter(hit.word).trim()
        if (valuePart.isEmpty()) return null

        return when (hit.op) {
            Op.BETWEEN -> {
                // "20 到 30 之间" / "20和30之间" / "20 30"
                val nums = Regex("[-+]?\\d+(?:\\.\\d+)?").findAll(valuePart).mapNotNull { it.value.toDoubleOrNull() }.toList()
                if (nums.size < 2) return null
                Condition(field, Op.BETWEEN, num = minOf(nums[0], nums[1]), num2 = maxOf(nums[0], nums[1]))
            }
            Op.CONTAINS, Op.NOT_CONTAINS -> Condition(field, hit.op, text = valuePart)
            Op.EQ, Op.NE -> {
                val n = valuePart.toDoubleOrNull()
                if (n != null) Condition(field, hit.op, num = n) else Condition(field, hit.op, text = valuePart)
            }
            else -> {
                val n = Condition.extractNumber(valuePart) ?: return null
                Condition(field, hit.op, num = n)
            }
        }
    }

    /**
     * 解析整句条件（字段 + 运算符 + 值）。
     * 支持 "温度 > 80"、"温度20..30"、"温度 在 20 到 30 之间"。
     * 解析失败返回 null。
     */
    fun parseWhole(exprRaw: String): Condition? {
        val e = chineseToArabic(normalize(exprRaw))
        if (e.isEmpty()) return null

        // 1) field a..b / field a~b
        Regex("^(.*?)\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*(?:\\.\\.|~)\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*$")
            .find(e)?.let { m ->
                val f = m.groupValues[1].trim()
                val a = m.groupValues[2].toDoubleOrNull()
                val b = m.groupValues[3].toDoubleOrNull()
                if (f.isNotEmpty() && a != null && b != null) {
                    return Condition(f, Op.BETWEEN, num = minOf(a, b), num2 = maxOf(a, b))
                }
            }

        // 2) field <op> value —— 取最靠前的运算符，长的优先
        var bestPos = -1
        var bestWord = ""
        for (ow in OP_WORDS) {
            val p = e.indexOf(ow.word)
            if (p > 0 && (bestPos == -1 || p < bestPos || (p == bestPos && ow.word.length > bestWord.length))) {
                bestPos = p; bestWord = ow.word
            }
        }
        if (bestPos <= 0) return null
        val field = e.substring(0, bestPos).trim()
        if (field.isEmpty()) return null
        return parse(field, e.substring(bestPos))
    }

    /**
     * 规则文本 → Rule 列表。一行一条，格式：
     *   条件 => 播报内容 => 秒数(可省略，默认 5)
     * 例如：
     *   len > 1600 => 快到期了 => 5
     *   len > 1670 => 快到极限了 => 5
     *   len > 1700 => 严重超限，请停机 => 10
     * 返回 (规则列表, 出错的行号或 null)
     */
    fun parseRulesText(text: String): Pair<List<Rule>, String?> {
        val rules = mutableListOf<Rule>()
        val errs = mutableListOf<String>()
        var lineNo = 0
        for (line in text.split("\n")) {
            lineNo++
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue

            // 两种写法都认：
            //   新（推荐，手机上少打字）：len>6600 好了 5
            //   旧：len > 6600 => 好了 => 5
            val condText: String
            var speech: String
            var secs = 5

            if (t.contains("=>") || t.contains("＝>") || t.contains("->")) {
                val parts = t.split("=>", "＝>", "->").map { it.trim() }
                if (parts.size < 2) { errs.add("第${lineNo}行格式不对：$t"); continue }
                condText = parts[0]
                speech = parts[1]
                if (parts.size >= 3) {
                    val s = parts[2].toIntOrNull() ?: parts[2].filter { it.isDigit() }.toIntOrNull()
                    if (s == null) { errs.add("第${lineNo}行秒数不对：${parts[2]}"); continue }
                    secs = s
                }
            } else {
                // 空格分隔。条件用正则精确吃掉「名字 运算符 数字」，运算符两侧有没有空格都认，
                // 剩下的才是播报内容，避免把「好了」当成条件的一部分。
                val cm = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)").find(t)
                if (cm != null) {
                    condText = cm.value
                    val restLine = t.substring(cm.range.last + 1).trim()
                    var rest = if (restLine.isEmpty()) emptyList() else restLine.split(Regex("\\s+"))
                    if (rest.isNotEmpty()) {
                        val s2 = rest.last().toIntOrNull()
                        if (s2 != null) { secs = s2; rest = rest.dropLast(1) }
                    }
                    speech = rest.joinToString(" ")
                } else {
                    // 非数值条件（contains 之类）：第一个词当条件
                    val parts = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    condText = parts[0]
                    var rest = parts.drop(1)
                    if (rest.isNotEmpty()) {
                        val s2 = rest.last().toIntOrNull()
                        if (s2 != null) { secs = s2; rest = rest.dropLast(1) }
                    }
                    speech = rest.joinToString(" ")
                }
            }

            val cond = parseWhole(condText)
            if (cond == null) { errs.add("第${lineNo}行条件看不懂：$condText"); continue }
            rules.add(Rule(cond.expr(), speech, secs.coerceIn(1, 120)))
        }
        return rules to if (errs.isEmpty()) null else errs.joinToString("；")
    }

    /** Rule 列表 → 编辑框文本 */
    fun rulesToText(rules: List<Rule>): String =
        rules.joinToString("\n") { "${it.expr} => ${it.speech} => ${it.seconds}" }
}

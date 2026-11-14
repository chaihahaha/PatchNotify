package com.example.patchnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionParserTest {

    private fun cond(expr: String): Condition {
        val c = parseCond(expr)
        assertNotNull("should parse: $expr", c)
        return c!!
    }

    private fun parseCond(expr: String): Condition? = ConditionParser.parseWhole(expr)

    @Test
    fun numericOperators() {
        val gt = cond("温度 > 80")
        assertEquals("温度", gt.field)
        assertTrue(gt.test("85"))
        assertTrue(gt.test("85 ℃"))
        assertFalse(gt.test("80"))
        assertFalse(gt.test("abc"))

        val le = cond("温度 <= 80")
        assertTrue(le.test("80"))
        assertFalse(le.test("81"))

        val eq = cond("温度 = 25")
        assertTrue(eq.test("25"))
        assertTrue(eq.test("25.0"))
        assertFalse(eq.test("26"))

        val ne = cond("状态 != 正常")
        assertFalse(ne.test("正常"))
        assertTrue(ne.test("异常"))
    }

    @Test
    fun chineseNumerals() {
        val c = cond("温度大于八十")
        assertTrue(c.test("85"))
        assertFalse(c.test("75"))

        val c2 = cond("温度小于三十")
        assertTrue(c2.test("25"))
        assertFalse(c2.test("35"))

        val c3 = cond("温度等于一百零五")
        assertTrue(c3.test("105"))
    }

    @Test
    fun range() {
        val c = cond("温度 在 20 到 30 之间")
        assertEquals(Op.BETWEEN, c.op)
        assertTrue(c.test("25"))
        assertTrue(c.test("20"))
        assertTrue(c.test("30"))
        assertFalse(c.test("31"))

        val c2 = cond("温度20..30")
        assertTrue(c2.test("22"))
    }

    @Test
    fun textContains() {
        val c = cond("状态 包含 报警")
        assertTrue(c.test("系统报警中"))
        assertFalse(c.test("运行正常"))
    }

    @Test
    fun exprRoundTrip() {
        val c = cond("温度 > 80")
        assertEquals("温度 > 80", c.expr())
        val c2 = cond("温度 在 20 到 30 之间")
        assertEquals("温度 在 20 到 30 之间", c2.expr())
    }

    // ---------------- 数字取值（欧姆龙 00 1679 这一类的关键） ----------------

    @Test
    fun nullValueNeverTriggersNumericRules() {
        // 读不到数字时，任何数字规则都不应触发（否则会误报）
        for (expr in listOf("len > 1670", "len >= 0", "len < 9999", "len = 0", "len != 999", "len 在 1 到 5 之间")) {
            val c = cond(expr)
            assertFalse("$expr 不应对 null 成立", c.testNumber(null))
        }
    }

    @Test
    fun pickNumberOnCounterDisplay() {
        // 显示屏 "00 1679"：取最大值才是真实计数
        assertEquals(1679.0, Condition.pickNumber("00 1679", "max")!!, 1e-9)
        assertEquals(0.0, Condition.pickNumber("00 1679", "first")!!, 1e-9)
        assertEquals(1679.0, Condition.pickNumber("00 1679", "last")!!, 1e-9)
        // 识别成一串也照样对
        assertEquals(1679.0, Condition.pickNumber("001679", "max")!!, 1e-9)
        // 数码管 O 被识别成字母
        assertEquals(1679.0, Condition.pickNumber("O0 1679", "max")!!, 1e-9)
        // 什么都没识别到
        assertNull(Condition.pickNumber("", "max"))
        assertNull(Condition.pickNumber("TIME", "max"))
    }

    @Test
    fun ruleFiresAtFivePercent() {
        // len > 1670 在读到 1679 时应该成立
        val c = cond("len > 1670")
        assertTrue(c.testNumber(Condition.pickNumber("00 1679", "max")))
        assertFalse(c.testNumber(Condition.pickNumber("00 1600", "max")))
        assertFalse(c.testNumber(null))
    }

    // ---------------- 多规则 ----------------

    @Test
    fun parseRulesTextMultiLine() {
        val text = """
            len > 1600 => 快到期了 => 5
            len > 1670 => 快到极限了 => 5
            len > 1700 => 严重超限请停机 => 10
        """.trimIndent()
        val (rules, err) = ConditionParser.parseRulesText(text)
        assertNull(err)
        assertEquals(3, rules.size)
        assertEquals("len > 1600", rules[0].expr)
        assertEquals("快到期了", rules[0].speech)
        assertEquals(5, rules[0].seconds)
        assertEquals(10, rules[2].seconds)
    }

    @Test
    fun parseRulesTextSecondsOptional() {
        val (rules, err) = ConditionParser.parseRulesText("len > 1600 => 提醒一下")
        assertNull(err)
        assertEquals(1, rules.size)
        assertEquals(5, rules[0].seconds)
    }

    @Test
    fun parseRulesTextAcceptsSpacesAroundOperator() {
        // 运算符两侧的空格是允许的，剩下的才是播报内容
        val (rules, err) = ConditionParser.parseRulesText("len > 1600 快到期了")
        assertNull(err)
        assertEquals(1, rules.size)
        assertEquals("快到期了", rules[0].speech)
        assertEquals(5, rules[0].seconds)
    }

    @Test
    fun parseRulesTextReportsBadLine() {
        val (_, err2) = ConditionParser.parseRulesText("随便写 => 内容")
        assertNotNull(err2)
    }

    @Test
    fun rulesTextRoundTrip() {
        val rules = listOf(
            Rule("len > 1600", "快到期了", 5),
            Rule("len > 1670", "快到极限了", 8)
        )
        val (back, err) = ConditionParser.parseRulesText(ConditionParser.rulesToText(rules))
        assertNull(err)
        assertEquals(2, back.size)
        assertEquals(rules[0].expr, back[0].expr)
        assertEquals(rules[1].seconds, back[1].seconds)
    }

}

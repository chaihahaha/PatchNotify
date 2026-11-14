package com.example.patchnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleTextFormatTest {

    @Test
    fun spaceSeparatedFormatWorks() {
        val (rules, err) = ConditionParser.parseRulesText("len>6600 好了 5")
        assertNull(err)
        assertEquals(1, rules.size)
        assertEquals("好了", rules[0].speech)
        assertEquals(5, rules[0].seconds)
        assertEquals(6600.0, rules[0].let { ConditionParser.parseWhole(it.expr)!!.num!! }, 1e-9)
    }

    @Test
    fun secondsOptionalAndDefaultsToFive() {
        val (rules, err) = ConditionParser.parseRulesText("len>6600 好了")
        assertNull(err)
        assertEquals(5, rules[0].seconds)
        assertEquals("好了", rules[0].speech)
    }

    @Test
    fun speechOptionalMeansBeepOnly() {
        val (rules, err) = ConditionParser.parseRulesText("len>6600")
        assertNull(err)
        assertEquals("", rules[0].speech)
        assertEquals(5, rules[0].seconds)
    }

    @Test
    fun multipleLinesEachBecomeARule() {
        val (rules, err) = ConditionParser.parseRulesText(
            "len>6600 第一个 5\nlen>6700 第二个 5\nlen>6800 第三个 10"
        )
        assertNull(err)
        assertEquals(3, rules.size)
        assertEquals("第一个", rules[0].speech)
        assertEquals(10, rules[2].seconds)
    }

    @Test
    fun oldArrowFormatStillWorks() {
        val (rules, err) = ConditionParser.parseRulesText("len > 6600 => 好了 => 5")
        assertNull(err)
        assertEquals("好了", rules[0].speech)
        assertEquals(5, rules[0].seconds)
    }

    @Test
    fun badConditionReportsLineNumber() {
        val (rules, err) = ConditionParser.parseRulesText("乱写 好了 5")
        assertTrue(err != null && err!!.contains("第1行"))
        assertEquals(0, rules.size)
    }
}

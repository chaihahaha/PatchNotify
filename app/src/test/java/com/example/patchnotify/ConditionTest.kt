package com.example.patchnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionTest {

    @Test
    fun splitConditionAndQuestion() {
        val (condition, question) = Condition.split("读数是否已接近001680。还需要充多久？")
        assertEquals("读数是否已接近001680", condition)
        assertEquals("还需要充多久？", question)
    }

    @Test
    fun noPeriodMeansNoQuestion() {
        val (condition, question) = Condition.split("画面里有没有人")
        assertEquals("画面里有没有人", condition)
        assertEquals("", question)
    }

    @Test
    fun leadingPeriodKeepsWholeTextAsCondition() {
        val (condition, question) = Condition.split("。先看这里")
        assertEquals("。先看这里", condition)
        assertEquals("", question)
    }

    @Test
    fun parseYesAndNo() {
        assertEquals(true, Condition.parse("是"))
        assertEquals(true, Condition.parse("yes"))
        assertEquals(false, Condition.parse("否"))
        assertEquals(false, Condition.parse("不是"))
        assertEquals(false, Condition.parse("没有"))
        assertNull(Condition.parse("看不清"))
    }

    @Test
    fun promptCarriesCondition() {
        val prompt = Condition.prompt("读数是否已接近001680")
        assertTrue(prompt.contains("读数是否已接近001680"))
        assertTrue(prompt.contains("只回答「是」或「否」"))
    }

    @Test
    fun questionPromptCarriesConditionAndQuestion() {
        val prompt = Condition.questionPrompt("读数是否已接近001680", "还需要充多久？")
        assertTrue(prompt.contains("读数是否已接近001680"))
        assertTrue(prompt.contains("还需要充多久"))
        assertFalse(prompt.contains("只回答「是」或「否」"))
    }
}

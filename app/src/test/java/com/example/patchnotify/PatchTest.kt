package com.example.patchnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchTest {

    @Test
    fun titleUsesFirstNonBlankRule() {
        val p = Patch(rules = mutableListOf(
            TriggerRule(condition = "  "),
            TriggerRule(condition = "读数是否已接近 001680"),
            TriggerRule(condition = "第二行")
        ))
        assertEquals("读数是否已接近 00", p.title())
    }

    @Test
    fun titleFallsBackWhenNoCondition() {
        val p = Patch(rules = mutableListOf(TriggerRule(condition = "")))
        assertEquals("未设条件", p.title())
    }

    @Test
    fun hasPythonLooksAtAllRules() {
        val p = Patch(rules = mutableListOf(
            TriggerRule(condition = "a"),
            TriggerRule(condition = "b", pyPath = "/sdcard/x.py")
        ))
        assertTrue(p.hasPython())
        assertFalse(Patch(rules = mutableListOf(TriggerRule(condition = "a"))).hasPython())
    }
}

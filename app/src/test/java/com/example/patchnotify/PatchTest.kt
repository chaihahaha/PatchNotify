package com.example.patchnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
    fun deepCopyKeepsReferencesIndependent() {
        val original = TriggerRule(
            condition = "和参考图1对比",
            references = mutableListOf(ReferenceImage("content://x", "原图", "说明"))
        )
        val copy = original.deepCopy()
        assertNotSame(original.references, copy.references)
        assertNotSame(original.references[0], copy.references[0])
        copy.references[0].name = "改过的名字"
        assertEquals("原图", original.references[0].name)
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

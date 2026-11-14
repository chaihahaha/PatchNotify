package com.example.patchnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数码管读数器的合成测试：程序自己画 7 段数字，再让读数器读回来。
 * 覆盖不同字宽、粗细、泛光（bloom）和两段式显示（00 1679）。
 */
class SegmentReaderTest {

    private val SEG_OF = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
        '5' to "afgcd", '6' to "afgecd", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg"
    )

    /** 在 ARGB 缓冲里画一个 7 段数字 */
    private fun drawDigit(
        text: Char, x0: Int, y0: Int, cw: Int, ch: Int, t: Int,
        px: IntArray, w: Int, h: Int
    ) {
        val segs = SEG_OF[text] ?: return
        fun rect(x: Int, y: Int, ww: Int, hh: Int) {
            for (yy in y until y + hh) {
                if (yy < 0 || yy >= h) continue
                for (xx in x until x + ww) {
                    if (xx < 0 || xx >= w) continue
                    px[yy * w + xx] = 0xFFFF2020.toInt()   // 亮红
                }
            }
        }
        if (segs.contains('a')) rect(x0 + t, y0, cw - 2 * t, t)
        if (segs.contains('g')) rect(x0 + t, y0 + ch / 2 - t / 2, cw - 2 * t, t)
        if (segs.contains('d')) rect(x0 + t, y0 + ch - t, cw - 2 * t, t)
        if (segs.contains('f')) rect(x0, y0 + t, t, ch / 2 - t)
        if (segs.contains('b')) rect(x0 + cw - t, y0 + t, t, ch / 2 - t)
        if (segs.contains('e')) rect(x0, y0 + ch / 2, t, ch / 2 - t)
        if (segs.contains('c')) rect(x0 + cw - t, y0 + ch / 2, t, ch / 2 - t)
    }

    /**
     * @param bloom 泛光强度 0..1（模拟 LED 光晕，真实照片里很常见）
     */
    private fun render(
        text: String, cw: Int, ch: Int, t: Int, gapAfter: Int = -1,
        gapPx: Int = 0, bloom: Double = 0.0, pad: Int = 6
    ): Triple<IntArray, Int, Int> {
        val pitch = cw + 4
        val groups = if (gapAfter in 0 until text.length) 1 else 0
        val w = pad * 2 + text.length * pitch + groups * gapPx
        val h = pad * 2 + ch
        val px = IntArray(w * h) { 0xFF101010.toInt() }   // 深色底

        var x = pad
        for ((i, c) in text.withIndex()) {
            drawDigit(c, x, pad, cw, ch, t, px, w, h)
            x += pitch
            if (i == gapAfter) x += gapPx
        }

        if (bloom > 0) {
            // 简单的盒式光晕：把亮像素往四周扩散
            val copy = px.copyOf()
            val r = 2
            for (y in 0 until h) for (xx in 0 until w) {
                var best = 0
                for (dy in -r..r) for (dx in -r..r) {
                    val ny = y + dy; val nx = xx + dx
                    if (ny in 0 until h && nx in 0 until w) {
                        val v = (copy[ny * w + nx] shr 16) and 0xFF
                        if (v > best) best = v
                    }
                }
                if (best > 100) {
                    val cur = (copy[y * w + xx] shr 16) and 0xFF
                    val target = (best * bloom).toInt().coerceAtMost(255)
                    if (target > cur) {
                        px[y * w + xx] = (0xFF shl 24) or (target shl 16) or ((target / 4) shl 8) or (target / 6)
                    }
                }
            }
        }
        return Triple(px, w, h)
    }

    @Test
    fun readsTwoGroupCounter() {
        // "00 1679"：中间有一段物理间隔
        val (px, w, h) = render("001679", cw = 26, ch = 46, t = 7, gapAfter = 1, gapPx = 12)
        val r = SegmentReader.decode(px, w, h)
        assertNotNull("应该能读出数码管", r)
        assertEquals("001679", r!!.digits)
        // 分组只是显示用，拼起来必须等于整体读数
        assertEquals("001679", r.groups.joinToString(""))
        assertEquals(1.0, r.confidence, 1e-9)
    }

    @Test
    fun readsAtSeveralScales() {
        for (ch in listOf(22, 34, 50, 72)) {
            val cw = (ch * 0.58).toInt()
            val t = maxOf(3, (ch * 0.15).toInt())
            val (px, w, h) = render("1679", cw = cw, ch = ch, t = t)
            val r = SegmentReader.decode(px, w, h)
            assertNotNull("ch=$ch 应该能读", r)
            assertEquals("ch=$ch", "1679", r!!.digits)
        }
    }

    @Test
    fun readsWithBloom() {
        val (px, w, h) = render("001679", cw = 26, ch = 46, t = 7, gapAfter = 1, gapPx = 12, bloom = 0.55)
        val r = SegmentReader.decode(px, w, h)
        assertNotNull("有泛光也要能读", r)
        assertEquals("001679", r!!.digits)
    }

    @Test
    fun readsAllDigits() {
        val (px, w, h) = render("0123456789", cw = 22, ch = 40, t = 6)
        val r = SegmentReader.decode(px, w, h)
        assertNotNull(r)
        assertEquals("0123456789", r!!.digits)
    }

    @Test
    fun rejectsNonLedText() {
        // 白色印刷字：不是红色 LED，应该拒绝（交给 ML Kit）
        val w = 200; val h = 60
        val px = IntArray(w * h) { 0xFF101010.toInt() }
        for (y in 20 until 40) for (x in 20 until 180) px[y * w + x] = 0xFFFFFFFF.toInt()
        assertNull(SegmentReader.decode(px, w, h))

        // 纯深色空画面
        val blank = IntArray(w * h) { 0xFF101010.toInt() }
        assertNull(SegmentReader.decode(blank, w, h))
    }

    @Test
    fun confidenceDropsWhenDigitsClipped() {
        // 把右边一半裁掉，应该读不全（要么 null，要么置信度低），绝不该瞎猜
        val (px, w, h) = render("001679", cw = 26, ch = 46, t = 7, gapAfter = 1, gapPx = 12)
        val cut = (w * 0.75).toInt()
        val cutPx = IntArray(cut * h)
        for (y in 0 until h) for (x in 0 until cut) cutPx[y * cut + x] = px[y * w + x]
        val r = SegmentReader.decode(cutPx, cut, h)
        if (r != null) {
            assertTrue("裁切后置信度应偏低", r.confidence < 1.0)
        }
    }
}

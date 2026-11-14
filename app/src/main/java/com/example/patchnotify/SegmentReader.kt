package com.example.patchnotify

/**
 * 7 段数码管读数器（纯 Kotlin，无 Android / 无模型依赖，可 JVM 单测）
 *
 * 思路：LED 数码管的每一段要么亮要么灭，直接把 7 个区域的平均亮度
 * 二值化成段码，再查表得到数字。不依赖任何 OCR 模型，
 * 因此不受"字形不在训练集里"的影响，也不会把笔画猜成汉字。
 *
 * 适用：红色/橙色 LED 段码屏（计数器、温度表、电子秤、变频器面板…）
 * 不适用：LCD 点阵、印刷字体、手写体（这些交给 ML Kit）。
 */
object SegmentReader {

    data class Result(
        /** 从左到右拼起来的数字，例如 "001679" */
        val digits: String,
        /** 被物理间隔分开的组，例如 ["00", "1679"] */
        val groups: List<String>,
        /** 有效数字所占比例 0..1 */
        val confidence: Double
    )

    /** 上一次 decode 的诊断信息，调试用 */
    var lastDebug: String = ""
        private set

    // ---- 段码表：a=顶, b=右上, c=右下, d=底, e=左下, f=左上, g=中 ----
    private const val A = 1
    private const val B = 2
    private const val C = 4
    private const val D = 8
    private const val E = 16
    private const val F = 32
    private const val G = 64

    private val PATTERN = mapOf(
        (A or B or C or D or E or F) to '0',
        (B or C) to '1',
        (A or B or G or E or D) to '2',
        (A or B or G or C or D) to '3',
        (F or G or B or C) to '4',
        (A or F or G or C or D) to '5',
        (A or F or G or E or C or D) to '6',
        (A or B or C) to '7',
        (A or B or C or D or E or F or G) to '8',
        (A or B or C or D or F or G) to '9'
    )

    /**
     * @param pixels ARGB 像素，长度必须 >= w*h
     * @return null 表示这块区域不像数码管 / 读不出来
     */
    fun decode(pixels: IntArray, w: Int, h: Int): Result? {
        lastDebug = "start"
        if (w < 12 || h < 12) { lastDebug = "太小 ${w}x$h"; return null }
        val n = w * h
        if (pixels.size < n) return null

        // ---------- 1. 红色 LED 掩码（Otsu 自适应阈值，抗泛光） ----------
        val hist = IntArray(256)
        var reddish = 0
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r - g >= 25 && r - b >= 25 && r > 60) {
                hist[r]++
                reddish++
            }
        }
        if (reddish < 30) return null
        val otsu = otsuThreshold(hist, reddish)
        val bodyThr = maxOf(100, otsu)

        val mask = BooleanArray(n)
        var lit = 0
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // 亮：足够红，且明显比绿蓝高（排除白色/灰色文字和背景）
            if (r >= bodyThr && r - g >= 25 && r - b >= 25) {
                mask[i] = true
                lit++
            }
        }
        if (lit < 30) return null

        // ---------- 2. 数字带（行投影里最厚的一段） ----------
        val rowSum = IntArray(h)
        for (y in 0 until h) {
            var s = 0
            val base = y * w
            for (x in 0 until w) if (mask[base + x]) s++
            rowSum[y] = s
        }
        val rowMax = rowSum.maxOrNull() ?: 0
        if (rowMax < 3) return null
        val rowThr = maxOf(2, (rowMax * 0.30).toInt())

        var bestY0 = -1; var bestY1 = -1; var bestScore = -1
        var y = 0
        while (y < h) {
            if (rowSum[y] > rowThr) {
                val s = y
                while (y < h && rowSum[y] > rowThr) y++
                val e = y - 1
                val score = e - s
                if (score > bestScore) { bestScore = score; bestY0 = s; bestY1 = e }
            } else y++
        }
        if (bestY0 < 0 || bestY1 - bestY0 < 8) return null
        val bandH = bestY1 - bestY0 + 1

        // ---------- 3. 列投影 -> 若干组（物理间隔会分开） ----------
        val colSum = IntArray(w)
        for (x in 0 until w) {
            var s = 0
            for (yy in bestY0..bestY1) if (mask[yy * w + x]) s++
            colSum[x] = s
        }
        val colThr = maxOf(2, (bandH * 0.12).toInt())
        val runs = ArrayList<IntArray>()   // [x0, x1]
        var x = 0
        while (x < w) {
            if (colSum[x] > colThr) {
                val s = x
                while (x < w && colSum[x] > colThr) x++
                runs.add(intArrayOf(s, x - 1))
            } else x++
        }
        if (runs.isEmpty()) return null

        // ---------- 4. 自校准字宽：找让"有效数字最多"的步距 ----------
        var bestPitch = 0.0
        var bestValid = -1
        var bestCells = 0
        var p = 0.42
        while (p <= 0.95) {
            val pitch = p * bandH
            var valid = 0
            var cells = 0
            for (r in runs) {
                for (c in cellsFor(r, pitch)) {
                    cells++
                    if (readCell(mask, w, c[0], c[1], bestY0, bestY1) != null) valid++
                }
            }
            // 平手时优先选「格子更少 = 字更宽」的步距：泛光会把字缝糊上，
            // 小步距会把一个数字切成两半，读数就全是问号
            if (cells > 0 && (valid > bestValid || (valid == bestValid && cells < bestCells))) {
                bestValid = valid
                bestCells = cells
                bestPitch = pitch
            }
            p += 0.03
        }
        if (bestPitch <= 0.0 || bestValid <= 0) return null

        // ---------- 5. 把相邻亮块并成组（间隔明显变大的地方才是物理分组） ----------
        val groupsRuns = ArrayList<MutableList<IntArray>>()
        for (r in runs) {
            val c = groupsRuns.lastOrNull()
            if (c == null || (r[0] - c.last()[1]) > 0.35 * bestPitch) {
                groupsRuns.add(mutableListOf(r))
            } else {
                c.add(r)
            }
        }

        val allDigits = StringBuilder()
        val groupStrs = ArrayList<String>()
        var total = 0
        var good = 0
        for (grp in groupsRuns) {
            val g = StringBuilder()
            for (r in grp) {
                for (c in cellsFor(r, bestPitch)) {
                    total++
                    val d = readCell(mask, w, c[0], c[1], bestY0, bestY1)
                    if (d != null) { good++; g.append(d) } else g.append('?')
                }
            }
            allDigits.append(g)
            if (g.isNotEmpty()) groupStrs.add(g.toString())
        }
        if (total == 0) return null
        val conf = good.toDouble() / total
        lastDebug = "cells=$total good=$good conf=${"%.2f".format(conf)} digits=$allDigits"
        // 至少 2 位、且 60% 以上认得出，才敢用
        if (good < 2 || conf < 0.6) return null
        return Result(allDigits.toString(), groupStrs, conf)
    }

    /**
     * 把一个亮块切成若干数字格。
     * 数码管里的 "1" 只点亮右边两段，所以它的亮块很窄——
     * 这种情况按"右对齐的一个完整字宽"来取，否则会把 1 漏掉。
     */
    private fun cellsFor(run: IntArray, pitch: Double): List<IntArray> {
        val rw = run[1] - run[0] + 1
        if (rw <= 0 || pitch <= 0) return emptyList()
        if (rw < pitch * 0.55) {
            val x1 = run[1]
            val x0 = Math.max(0, (x1 - pitch + 1).toInt())
            return listOf(intArrayOf(x0, x1))
        }
        val k = Math.max(1, Math.round(rw / pitch).toInt())
        if (k > 16) return emptyList()
        val cw = rw.toDouble() / k
        val out = ArrayList<IntArray>(k)
        for (i in 0 until k) {
            out.add(intArrayOf(run[0] + (i * cw).toInt(), run[0] + ((i + 1) * cw).toInt() - 1))
        }
        return out
    }

    /** 大津法：把红色通道的直方图分成"芯"和"光晕/背景"两类 */
    private fun otsuThreshold(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thr = t + 1
            }
        }
        return thr
    }

    /** 读一个数字格；认不出返回 null */
    private fun readCell(
        mask: BooleanArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int
    ): Char? {
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        if (cw < 4 || ch < 8) return null

        fun ratio(ya: Double, yb: Double, xa: Double, xb: Double): Double {
            val yy0 = y0 + (ya * ch).toInt()
            val yy1 = y0 + Math.max((yb * ch).toInt(), (ya * ch).toInt() + 1)
            val xx0 = x0 + (xa * cw).toInt()
            val xx1 = x0 + Math.max((xb * cw).toInt(), (xa * cw).toInt() + 1)
            var s = 0
            var c = 0
            for (yy in yy0 until Math.min(yy1, y0 + ch)) {
                val base = yy * w
                for (xx in xx0 until Math.min(xx1, x0 + cw)) {
                    if (mask[base + xx]) s++
                    c++
                }
            }
            return if (c == 0) 0.0 else s.toDouble() / c
        }

        val ra = ratio(0.02, 0.18, 0.18, 0.82)
        val rg = ratio(0.41, 0.59, 0.18, 0.82)
        val rd = ratio(0.82, 0.98, 0.18, 0.82)
        val rf = ratio(0.14, 0.44, 0.00, 0.32)
        val rb = ratio(0.14, 0.44, 0.68, 1.00)
        val re = ratio(0.56, 0.86, 0.00, 0.32)
        val rc = ratio(0.56, 0.86, 0.68, 1.00)

        val mx = maxOf(ra, rg, rd, rf, rb, re, rc)
        if (mx < 0.12) return null

        // 光晕糊死检查：真数字的亮面积只占格子的 30~55%；
        // 整格 70% 以上都亮，说明失焦/过曝把段间缝隙糊死了，读数不可信
        val cellRatio = ratio(0.0, 1.0, 0.0, 1.0)
        if (cellRatio > 0.70) return null

        val t = maxOf(0.22, mx * 0.5)

        var code = 0
        if (ra >= t) code = code or A
        if (rb >= t) code = code or B
        if (rc >= t) code = code or C
        if (rd >= t) code = code or D
        if (re >= t) code = code or E
        if (rf >= t) code = code or F
        if (rg >= t) code = code or G
        return PATTERN[code]
    }
}

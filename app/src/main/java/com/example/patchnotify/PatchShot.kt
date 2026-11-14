package com.example.patchnotify

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 画面存档：
 *   - 每 60 秒存一张 patch 的裁剪图，条件触发时额外补一张（文件名带 _hit）
 *   - 每个 patch 目录里有一份 meta.json：矩形位置、像素尺寸、自然语言条件、思考开关、设备信息
 *   - 命中时往 events.log 追加一行「时间 / 判定 / 问题 / 回答」
 *   - 每天存一张整帧（未裁剪）图到 _daily/，方便日后看清当时拍的是什么
 *
 * 每个区域各自封顶 [PER_PATCH_BUDGET]（2 GB），按时间从旧到新删。
 */
object PatchShot {
    private const val INTERVAL_MS = 60_000L
    private const val DAILY_MS = 24 * 3600_000L
    private const val QUALITY = 70
    private const val PER_PATCH_BUDGET = 2L * 1024 * 1024 * 1024
    private const val TRIM_TO = 0.9

    private val lastAt = ConcurrentHashMap<String, Long>()
    private val dirOf = ConcurrentHashMap<String, File>()
    private val used = ConcurrentHashMap<String, Long>()
    private val metaSig = ConcurrentHashMap<String, String>()
    private var root: File? = null
    private var dailyDir: File? = null
    private var lastDailyAt = 0L
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    private var appVersion = ""
    private var device = ""

    fun init(base: File, version: String, deviceInfo: String) {
        root = File(base, "logs")
        dailyDir = File(root, "_daily")
        dailyDir?.mkdirs()
        appVersion = version
        device = deviceInfo
        // 上次整帧时间取目录里最新一张的日期，避免重启后又立刻存一张
        lastDailyAt = dailyDir?.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
    }

    /** 记录当前分析帧的尺寸，meta.json 里就能写出框的像素坐标 */
    fun setFrameSize(w: Int, h: Int) { frameW = w; frameH = h }

    private fun dirFor(p: Patch): File? {
        val base = root ?: return null
        return dirOf.getOrPut(p.id) {
            val safe = p.title().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(20)
                .ifBlank { "patch" } + "_" + p.id.take(6)
            File(base, safe).apply {
                mkdirs()
                used[path] = listFiles()?.sumOf { it.length() } ?: 0L
            }
        }
    }

    /** 条件或矩形变了就重写 meta.json，保证这个目录存的是什么永远能查回来 */
    private fun ensureMeta(p: Patch) {
        val dir = dirFor(p) ?: return
        val sig = "${p.ask}|${p.x},${p.y},${p.w},${p.h}|${p.think}"
        if (metaSig[p.id] == sig) return
        metaSig[p.id] = sig
        val o = JSONObject().apply {
            put("patch_id", p.id)
            put("title", p.title())
            put("ask", p.ask)
            put("think", p.think)
            put("rect_normalized", JSONObject().apply {
                put("x", p.x.toDouble()); put("y", p.y.toDouble())
                put("w", p.w.toDouble()); put("h", p.h.toDouble())
            })
            if (frameW > 0 && frameH > 0) put("frame_px", JSONObject().apply {
                put("w", frameW); put("h", frameH)
            })
            if (frameW > 0 && frameH > 0) put("rect_px", JSONObject().apply {
                put("left", (p.x * frameW).toInt())
                put("top", (p.y * frameH).toInt())
                put("width", (p.w * frameW).toInt())
                put("height", (p.h * frameH).toInt())
            })
            put("written_at", isoNow())
            put("app", appVersion)
            put("device", device)
            put("note", "文件名里的时间是截图时刻；_hit 结尾的是条件成立时补拍的；" +
                    "events.log 记录每次命中的判定与问答")
        }
        try {
            File(dir, "meta.json").writeText(o.toString(2))
        } catch (_: Throwable) { }
    }

    /** 调用方必须在后台线程。force = 条件触发 */
    fun maybe(p: Patch, bmp: Bitmap, force: Boolean) {
        val now = System.currentTimeMillis()
        val prev = lastAt[p.id] ?: 0L
        if (!force && now - prev < INTERVAL_MS) return
        lastAt[p.id] = now
        ensureMeta(p)
        val dir = dirFor(p) ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) +
            if (force) "_hit.jpg" else ".jpg"
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            tmp.renameTo(File(dir, name))
        } catch (_: Throwable) {
            tmp.delete(); return
        }
        val size = File(dir, name).length()
        val total = used.merge(dir.path, size, Long::plus) ?: size
        if (total > PER_PATCH_BUDGET) trim(dir)
    }

    /** 命中记录：时间 / 判定 / 问题 / 回答 */
    fun event(p: Patch, verdict: String, question: String, answer: String) {
        val dir = dirFor(p) ?: return
        ensureMeta(p)
        val line = "${isoNow()}  verdict=${verdict.replace("\n", " ").take(20)}" +
            (if (question.isBlank()) "" else "  q=${question}") +
            (if (answer.isBlank()) "" else "  a=$answer") + "\n"
        try { File(dir, "events.log").appendText(line) } catch (_: Throwable) { }
    }

    fun dailyDue(): Boolean {
        if (System.currentTimeMillis() - lastDailyAt <= DAILY_MS) return false
        lastDailyAt = System.currentTimeMillis()      // 先占位，避免连续几帧都各起一个线程
        return true
    }

    /** 整帧留档，一天一张；压缩在调用线程做，所以请从后台线程调用 */
    fun saveDaily(frame: Bitmap) {
        val dir = dailyDir ?: return
        lastDailyAt = System.currentTimeMillis()
        val name = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".jpg"
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            tmp.renameTo(File(dir, name))
        } catch (_: Throwable) {
            tmp.delete()
        } finally {
            frame.recycle()
        }
        // 整帧不设上限，但只留最近 180 天
        dir.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 180L * DAILY_MS }
            ?.forEach { it.delete() }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: return
        var total = files.sumOf { it.length() }
        val target = (PER_PATCH_BUDGET * TRIM_TO).toLong()
        for (f in files) {
            if (total <= target) break
            total -= f.length()
            f.delete()
        }
        used[dir.path] = total
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
}

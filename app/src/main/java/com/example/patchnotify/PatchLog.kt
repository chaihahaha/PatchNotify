package com.example.patchnotify

import android.content.Context
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * 每个 patch 一个独立日志文件，写盘走单独线程 + 有界队列。
 *
 * 目录：<logDir()>/logs/
 *   文件：patch_<字段名>_<矩形哈希>.log
 *   格式：ISO8601 时间 + 识别内容（+ 可选备注）
 *
 * 为什么不在识别线程里直接写：
 *   VLM 推理那 4~5 秒里 UI 线程要做预览渲染和裁图，任何磁盘 fsync 抖动都会让预览掉帧。
 *   这里只做 enqueue（O(1)，满队列直接丢弃，绝不阻塞调用方），落盘由 writer 线程负责。
 *
 * 容量控制：
 *   单文件 2 MB 轮转、每 patch 保留 3 个、整个目录上限 128 MB、超过 14 天的文件启动时清理。
 */
object PatchLog {

    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val KEEP_PER_PATCH = 3
    private const val TOTAL_BUDGET = 128L * 1024 * 1024
    private const val KEEP_DAYS = 14L
    private const val HEARTBEAT_MS = 60_000L     // 读数不变也每 60s 记一条，证明还活着

    private class Item(val file: File, val text: String)

    private val queue = LinkedBlockingQueue<Item>(4096)
    private val writers = HashMap<String, BufferedWriter>()
    private val sizes = HashMap<String, Long>()
    private val lastText = HashMap<String, String>()
    private val lastAt = HashMap<String, Long>()

    @Volatile private var dir: File? = null
    private var writerThread: Thread? = null

    private val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    /** 在 Activity onCreate 里调一次；baseDir 传 logDir()（即 patchnotify/） */
    @Synchronized
    fun init(ctx: Context, baseDir: File) {
        if (writerThread != null) return
        val d = File(baseDir, "logs")
        d.mkdirs()
        dir = d
        housekeeping(d)
        writerThread = Thread({ writerLoop() }, "patchlog").apply {
            isDaemon = true
            start()
        }
        deviceHeader = buildHeader(ctx)
    }

    private var deviceHeader: String = ""

    fun dir(): File? = dir

    /**
     * 判定这条读数要不要落盘：内容变了就记；没变则每 60 秒记一条心跳。
     * 计数器一直在跳的时候最多也就是每秒 4 行，静止时近乎为零。
     */
    @Synchronized
    fun shouldLog(patchId: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        val changed = lastText[patchId] != text
        val stale = now - (lastAt[patchId] ?: 0L) >= HEARTBEAT_MS
        if (!changed && !stale) return false
        lastText[patchId] = text
        lastAt[patchId] = now
        return true
    }

    /** 非阻塞：只入队。队列满（写入严重落后）就丢弃，不拖慢推理和预览。 */
    fun log(p: Patch, text: String, extra: String = "") {
        val d = dir ?: return
        val f = fileFor(d, p) ?: return
        if (!f.exists()) {
            enqueue(Item(f, deviceHeader + patchHeader(p)))
            sizes[f.absolutePath] = 0L
        }
        val line = ts.format(Date()) + " " + text.replace('\n', ' ') +
                (if (extra.isNotEmpty()) "  " + extra else "")
        enqueue(Item(f, line))
    }

    private fun enqueue(it: Item) {
        // offer：满了就丢，绝不阻塞调用方
        queue.offer(it)
    }

    private fun writerLoop() {
        while (true) {
            val first = try { queue.take() } catch (e: InterruptedException) { return }
            var batch = ArrayList<Item>(64)
            batch.add(first)
            queue.drainTo(batch, 63)
            for (it in batch) {
                try {
                    write(it)
                } catch (_: Throwable) {
                }
            }
            try { flushAll() } catch (_: Throwable) { }
        }
    }

    private fun write(it: Item) {
        val key = it.file.absolutePath
        var w = writers[key]
        if (w == null) {
            it.file.parentFile?.mkdirs()
            w = BufferedWriter(OutputStreamWriter(FileOutputStream(it.file, true), "UTF-8"))
            writers[key] = w
        }
        w.write(it.text)
        w.newLine()
        val n = (sizes[key] ?: it.file.length()) + it.text.length + 1
        sizes[key] = n
        if (n > MAX_BYTES) rotate(it.file, key)
    }

    private fun flushAll() {
        for ((k, w) in writers) {
            try { w.flush() } catch (_: Throwable) { }
            // 大小接近上限时顺手关掉，让它能轮转
            if ((sizes[k] ?: 0L) > MAX_BYTES) rotate(File(k), k)
        }
    }

    /** x.log → x.1.log，旧的往后顺延，超过 KEEP_PER_PATCH 的删掉 */
    private fun rotate(f: File, key: String) {
        try { writers.remove(key)?.close() } catch (_: Throwable) { }
        sizes.remove(key)
        val base = f.name.removeSuffix(".log")
        val parent = f.parentFile
        // 删除最老的
        val old = File(parent, "$base.${KEEP_PER_PATCH - 1}.log")
        if (old.exists()) old.delete()
        for (i in KEEP_PER_PATCH - 2 downTo 1) {
            val from = File(parent, "$base.$i.log")
            if (from.exists()) from.renameTo(File(parent, "$base.${i + 1}.log"))
        }
        f.renameTo(File(parent, "$base.1.log"))
    }

    private fun fileFor(d: File, p: Patch): File? {
        val safe = (p.field.ifBlank { p.name }.ifBlank { "patch" })
            .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
            .take(24)
        val key = Integer.toHexString(("${p.x},${p.y},${p.w},${p.h}").hashCode())
        return File(d, "patch_${safe}_$key.log")
    }

    private fun patchHeader(p: Patch): String = buildString {
        append("# patch=").append(p.field.ifBlank { p.name })
        append(" id=").append(p.id)
        append(" rect=").append(String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", p.x, p.y, p.w, p.h))
        append(" mode=").append(p.mode).append(" kind=").append(p.kind).append(" pick=").append(p.pick)
        append(" rules=").append(p.rules.joinToString(" | ") { it.expr })
        append('\n')
    }

    private fun buildHeader(ctx: Context): String = buildString {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        val dm = ctx.resources.displayMetrics
        append("# PatchNotify ").append(MainActivity.BUILD_TAG)
        append("  创建于 ").append(ts.format(Date())).append('\n')
        append("# 设备 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append("  soc=").append(soc)
        append("  android=").append(Build.VERSION.RELEASE).append("(API ").append(Build.VERSION.SDK_INT).append(')')
        append("  abi=").append(Build.SUPPORTED_ABIS.firstOrNull())
        append("  屏幕=").append(dm.widthPixels).append('x').append(dm.heightPixels)
        append(" @").append(dm.densityDpi).append("dpi\n")
    }

    /** 启动时清理过期文件；总量超预算就按 mtime 从旧到新删 */
    private fun housekeeping(d: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.toMutableList() ?: return
        files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        var total = files.filter { it.exists() }.sumOf { it.length() }
        if (total <= TOTAL_BUDGET) return
        for (f in files.filter { it.exists() }.sortedBy { it.lastModified() }) {
            if (total <= TOTAL_BUDGET) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}

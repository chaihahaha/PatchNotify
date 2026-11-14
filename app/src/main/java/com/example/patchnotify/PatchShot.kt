package com.example.patchnotify

import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 定时把 patch 的裁剪图存成 JPEG：
 *   - 正常情况每 60 秒一张
 *   - 条件触发时立刻补一张（不受 60 秒限制）
 *
 * 每个区域各自封顶 [PER_PATCH_BUDGET]（2 GB），不设张数上限；超了就按时间从旧到新删。
 * 裁剪图压成 JPEG 通常 10~30 KB，2 GB 约能存 8~15 万张（连续拍 2~3 个月）。
 */
object PatchShot {
    private const val INTERVAL_MS = 60_000L
    private const val QUALITY = 70
    /** 每个区域各自最多占 2 GB，不发张数上限 */
    private const val PER_PATCH_BUDGET = 2L * 1024 * 1024 * 1024
    /** 超限时一次删到 90%，避免每次写入都全目录排序 */
    private const val TRIM_TO = 0.9

    private val lastAt = ConcurrentHashMap<String, Long>()
    private val dirOf = ConcurrentHashMap<String, File>()
    private val used = ConcurrentHashMap<String, Long>()
    private var root: File? = null

    fun init(base: File) {
        root = File(base, "logs")
        if (!root!!.exists()) root!!.mkdirs()
    }

    /** 每 patch 一个子目录，名字用条件前 10 字（去掉文件名非法字符） */
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

    /**
     * 到达采样点就落盘。调用方必须在后台线程。
     * @param force 条件触发时为 true，跳过 60 秒间隔限制
     */
    fun maybe(p: Patch, bmp: Bitmap, force: Boolean) {
        val now = System.currentTimeMillis()
        val prev = lastAt[p.id] ?: 0L
        if (!force && now - prev < INTERVAL_MS) return
        lastAt[p.id] = now
        val dir = dirFor(p) ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) +
            if (force) "_hit.jpg" else ".jpg"
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            tmp.renameTo(File(dir, name))
        } catch (_: Throwable) {
            tmp.delete()
            return
        }
        val size = File(dir, name).length()
        val total = used.merge(dir.path, size, Long::plus) ?: size
        if (total > PER_PATCH_BUDGET) trim(dir)
    }

    /** 超 2 GB 就按文件名（时间戳）从旧到新删到 90% */
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
}

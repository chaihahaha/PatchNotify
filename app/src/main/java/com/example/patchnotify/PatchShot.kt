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
 * 只保留每 patch 最近 [KEEP_PER_PATCH] 张，目录总量超过 [TOTAL_BUDGET] 或
 * 超过 [KEEP_DAYS] 天的也一并清掉。裁剪图很小（几百 KB 级位图压成 JPEG 通常 10~30 KB）。
 */
object PatchShot {
    private const val INTERVAL_MS = 60_000L
    private const val QUALITY = 70
    private const val KEEP_PER_PATCH = 240                 // 1 分钟一张 → 4 小时
    private const val TOTAL_BUDGET = 10L * 1024 * 1024 * 1024   // 10 GB
    private const val KEEP_DAYS = 3

    private val lastAt = ConcurrentHashMap<String, Long>()
    private val dirOf = ConcurrentHashMap<String, File>()
    private var root: File? = null
    private var writes = 0

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
            File(base, safe).apply { mkdirs() }
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
        writes++
        prune(dir)
        if (writes % 30 == 0) pruneAll()
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: return
        if (files.size <= KEEP_PER_PATCH) return
        files.take(files.size - KEEP_PER_PATCH).forEach { it.delete() }
    }

    private fun pruneAll() {
        val base = root ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600_000L
        val all = mutableListOf<File>()
        base.listFiles()?.forEach { d ->
            d.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                if (f.lastModified() < cutoff) f.delete() else all.add(f)
            }
        }
        var total = all.sumOf { it.length() }
        if (total <= TOTAL_BUDGET) return
        for (f in all.sortedBy { it.lastModified() }) {
            if (total <= TOTAL_BUDGET) break
            total -= f.length()
            f.delete()
        }
    }
}

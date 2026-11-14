package com.example.patchnotify

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 把 APK 里自带的 .tenz 模型解到 sdcard。
 *
 * assets/model/model.gguf.tenz    → <sdcard>/patchnotify/qwen_2b/model.gguf     (Qwen3.5-2B Q4_0)
 * assets/mmproj/mmproj.gguf.tenz  → <sdcard>/patchnotify/qwen_2b/mmproj-F16.gguf (视觉塔 Q8_0)
 *
 * 只有打「免配置安装包」时才会把这些资产放进去；普通构建 assets 里没有，
 * [bundled] 返回 false，整个流程跳过，照旧用「导入模型」。
 */
object ModelBundle {

    private data class Item(val asset: String, val name: String, val bytes: Long)

    private val ITEMS = listOf(
        Item("model/model.gguf.tenz", "model.gguf", 1_214_873_856L),
        Item("mmproj/mmproj.gguf.tenz", "mmproj-F16.gguf", 361_518_784L)
    )

    @Volatile var busy = false
        private set

    fun targetDir(): File = File(
        Environment.getExternalStorageDirectory(), "patchnotify/qwen_2b"
    )

    /** APK 里是否带了模型 */
    fun bundled(ctx: Context): Boolean = try {
        val list = ctx.assets.list("model") ?: emptyArray()
        list.any { it.endsWith(".tenz") }
    } catch (_: Throwable) { false }

    /**
     * 确保目标目录里两个文件都在且大小正确；缺哪个补哪个。
     * 会阻塞，必须从后台线程调用。say 用来回报进度。
     */
    fun ensure(ctx: Context, say: (String) -> Unit): Boolean {
        if (!bundled(ctx)) return false
        busy = true
        try {
            val dir = targetDir()
            if (!dir.isDirectory && !dir.mkdirs()) {
                say("无法创建目录 ${dir.absolutePath}")
                return false
            }
            for (item in ITEMS) {
                val out = File(dir, item.name)
                if (out.isFile && out.length() == item.bytes) continue

                // 临时文件放在同一张卡上，别把 /data 撑爆
                val tmp = File(dir, item.name + ".tenz")
                val need = item.bytes + tmp.length().coerceAtLeast(0L)
                val free = dir.usableSpace
                if (free in 1 until need) {
                    say("存储空间不足：需要 ${need / 1048576} MB，剩 ${free / 1048576} MB")
                    return false
                }
                say("首次启动：正在解压 ${item.name}（${item.bytes / 1048576} MB）…")
                copyAsset(ctx, item.asset, tmp) { done, total ->
                    if (total > 0 && done % (64L * 1024 * 1024) < (1024 * 1024)) {
                        say("释放 ${item.name} ${done * 100 / total}%")
                    }
                }
                out.delete()
                val rc = try {
                    Tenz.unpack(tmp.absolutePath, out.absolutePath, 4)
                } catch (t: Throwable) {
                    say("解压失败：${t.message}")
                    -1
                }
                tmp.delete()
                if (rc != 0 || !out.isFile || out.length() != item.bytes) {
                    say("解压 ${item.name} 失败（rc=$rc，得到 ${out.length()} 字节）")
                    out.delete()
                    return false
                }
            }
            say("模型已就绪：${dir.absolutePath}")
            return true
        } finally {
            busy = false
        }
    }

    private fun copyAsset(
        ctx: Context, asset: String, dst: File, progress: (Long, Long) -> Unit
    ) {
        val total = try {
            ctx.assets.openFd(asset).use { it.length }
        } catch (_: Throwable) { -1L }
        dst.parentFile?.mkdirs()
        ctx.assets.open(asset).use { ins ->
            dst.outputStream().use { outs ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
    }
}

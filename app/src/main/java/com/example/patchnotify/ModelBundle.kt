package com.example.patchnotify

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 「免配置安装包」：APK 的 assets 里直接带 GGUF，首次启动释放到**应用私有目录**：
 *
 *   assets/model/...gguf   → /data/data/com.example.patchnotify/files/models/
 *   assets/mmproj/...gguf  → 同上
 *
 * 放私有目录而不是 sdcard 的原因：不需要存储权限、不会被清理软件或用户误删、
 * 卸载时跟着一起走。想换模型的人仍然可以把文件放到 sdcard 的 patchnotify/qwen_2b/，
 * 那条路径优先。
 *
 * assets 是 APK 压缩包里的条目，不是文件系统里的文件，llama.cpp 只接受路径，
 * 所以第一次启动必须把它写出来一次；之后就一直复用。
 */
object ModelBundle {

    private data class Item(val asset: String, val name: String, val bytes: Long)

    private val ITEMS = listOf(
        Item("model/Qwen3.5-2B-UD-IQ2_M.gguf", "Qwen3.5-2B-UD-IQ2_M.gguf", 859_857_152L),
        Item("mmproj/mmproj-Q8_0.gguf", "mmproj-Q8_0.gguf", 361_518_784L)
    )

    fun targetDir(ctx: Context): File = File(ctx.filesDir, "models")

    /** 用户自己放在 sdcard 上的模型目录（优先于自带模型） */
    fun userDir(): File = File(
        Environment.getExternalStorageDirectory(), "patchnotify/qwen_2b"
    )

    /** APK 里是否带了模型 */
    fun bundled(ctx: Context): Boolean = try {
        (ctx.assets.list("model") ?: emptyArray()).any { it.endsWith(".gguf") }
    } catch (_: Throwable) { false }

    /**
     * 确保目录里有可用的模型。会阻塞，必须从后台线程调用。
     * @return true 表示目录现在可用（自带解压成功，或本来就有用户自己的文件）
     */
    fun ensure(ctx: Context, say: (String) -> Unit): Boolean {
        if (!bundled(ctx)) return false

        // 用户已经准备好一份了 → 一律不动
        val user = userDir()
        if (hasModel(user) && hasMmproj(user)) {
            say("检测到 sdcard 上已有模型，优先使用它")
            return true
        }

        val dir = targetDir(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) {
            say("无法创建目录 ${dir.absolutePath}")
            return false
        }

        for (item in ITEMS) {
            val out = File(dir, item.name)
            if (out.isFile && out.length() == item.bytes) continue
            // 目录里已经有同类文件（用户放的）就不覆盖
            val hasSameKind = if (item.name.startsWith("mmproj")) hasMmproj(dir) else hasModel(dir)
            if (hasSameKind) continue

            val free = dir.usableSpace
            if (free in 1 until item.bytes) {
                say("存储空间不足：需要 ${item.bytes / 1048576} MB，剩 ${free / 1048576} MB")
                return false
            }
            say("首次启动：释放 ${item.name}（${item.bytes / 1048576} MB）…")
            val tmp = File(dir, item.name + ".part")
            try {
                ctx.assets.open(item.asset).use { ins ->
                    tmp.outputStream().use { outs ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var mark = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            outs.write(buf, 0, n)
                            done += n
                            if (done - mark > 64L * 1024 * 1024) {
                                mark = done
                                say("释放 ${item.name} ${done * 100 / item.bytes}%")
                            }
                        }
                    }
                }
                if (tmp.length() != item.bytes) {
                    say("${item.name} 拷贝不完整（${tmp.length()} 字节）")
                    tmp.delete()
                    return false
                }
                if (!tmp.renameTo(out)) {
                    tmp.delete()
                    say("无法写入 ${out.absolutePath}")
                    return false
                }
            } catch (t: Throwable) {
                tmp.delete()
                say("释放 ${item.name} 失败：${t.message}")
                return false
            }
        }
        say("模型已就绪：${dir.absolutePath}")
        return hasModel(dir) && hasMmproj(dir)
    }

    /** 非 mmproj 的 .gguf 视为模型本体 */
    private fun hasModel(dir: File): Boolean = dir.listFiles()?.any {
        it.isFile && it.name.endsWith(".gguf", true) && !it.name.startsWith("mmproj", true) &&
            it.length() > 100L * 1024 * 1024
    } == true

    private fun hasMmproj(dir: File): Boolean = dir.listFiles()?.any {
        it.isFile && it.name.startsWith("mmproj", true) && it.name.endsWith(".gguf", true) &&
            it.length() > 10L * 1024 * 1024
    } == true
}

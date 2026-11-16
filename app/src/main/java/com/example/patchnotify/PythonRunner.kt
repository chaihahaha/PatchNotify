package com.example.patchnotify

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 条件命中后执行用户脚本。
 *
 * 解释器是 Chaquopy 内置的 CPython（随 APK 一起打成 libpython），不联网、不依赖系统
 * 里的 Termux。脚本路径来自 TriggerRule.pyPath，通常是 /sdcard/... 下的 .py；
 * 真正的执行放在独立线程，绝不阻塞相机/推理/TTS。
 */
object PythonRunner {
    private const val TAG = "PatchNotify"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "python-user-script").apply { isDaemon = true }
    }

    // 多行条件可能几乎同时命中：脚本排队串行执行，最多排 8 个，避免死循环无限堆积
    private val pending = AtomicInteger(0)
    private const val MAX_PENDING = 8

    @Volatile private var startError = ""

    /** 提前拉起解释器，避免第一次命中时多等 1~2 秒 */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        try {
            executor.execute { ensureStarted(app) }
        } catch (t: Throwable) {
            Log.w(TAG, "Python 预热排队失败: ${t.message}")
        }
    }

    private fun ensureStarted(ctx: Context) {
        if (Python.isStarted()) return
        synchronized(this) {
            if (Python.isStarted()) return
            try {
                Python.start(AndroidPlatform(ctx))
                startError = ""
                Log.i(TAG, "Python 解释器已启动")
            } catch (t: Throwable) {
                startError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Python 启动失败: $startError", t)
            }
        }
    }

    /**
     * 异步执行 [rule] 的脚本；[onResult] 在 Python 线程被调用（调用方自己切 UI 线程）。
     * 返回值以 "ok" 开头表示成功，以 "ERR" 开头表示失败，内容都截断到可读长度。
     */
    fun run(
        ctx: Context, patch: Patch, rule: TriggerRule, answer: String,
        onResult: ((String) -> Unit)? = null
    ) {
        val path = rule.pyPath.trim()
        if (path.isEmpty()) return

        // 规则可能在排队期间被用户改掉，先拍快照
        val id = patch.id
        val title = patch.title()
        val condition = rule.condition
        val question = rule.question
        val app = ctx.applicationContext

        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            onResult?.invoke("ERR Python 脚本队列已满（${MAX_PENDING} 个），本次跳过")
            return
        }
        try {
            executor.execute {
                val result = try {
                    ensureStarted(app)
                    if (startError.isNotEmpty()) {
                        "ERR Python 解释器启动失败：$startError"
                    } else {
                        val script = File(path)
                        if (!script.isFile || !script.canRead()) {
                            "ERR 脚本不存在或不可读：$path"
                        } else {
                            val module = Python.getInstance().getModule("runner")
                            module.callAttr(
                                "run",
                                script.absolutePath,
                                id,
                                title,
                                condition,
                                question,
                                answer,
                                app.filesDir.absolutePath
                            ).toString()
                        }
                    }
                } catch (t: Throwable) {
                    "ERR ${t.javaClass.simpleName}: ${t.message ?: ""}".take(1000)
                }
                pending.decrementAndGet()
                Log.i(TAG, "python: ${result.take(600)}")
                onResult?.invoke(result)
            }
        } catch (t: Throwable) {
            pending.decrementAndGet()
            onResult?.invoke("ERR 无法排队执行：${t.message}")
        }
    }
}

package com.example.patchnotify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一条触发条件及其对应的动作。
 *
 * 同一个区域可以有多条 TriggerRule，任意一条从「否」变成「是」时，
 * 就执行这一条自己的动作（追问问题 + 语音播报 + 可选的 Python 脚本）。
 *
 * condition 和 question 在 UI 上是两个独立输入框，不再用「。」拼在一起。
 */
data class TriggerRule(
    val id: String = UUID.randomUUID().toString(),
    /** 判断条件；模型只回答是/否 */
    var condition: String = "",
    /** 条件成立后要模型回答并用 TTS 播报的问题；空 = 只响提示音 */
    var question: String = "",
    /** 允许模型先思考再给是/否（慢，开放条件才需要） */
    var think: Boolean = false,
    /** 条件成立时额外执行的 Python 脚本绝对路径；空 = 不执行 */
    var pyPath: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("condition", condition)
        put("question", question)
        put("think", think)
        put("py_path", pyPath)
    }

    companion object {
        fun fromJson(o: JSONObject) = TriggerRule(
            id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
            condition = o.optString("condition", ""),
            question = o.optString("question", ""),
            think = o.optBoolean("think", false),
            pyPath = o.optString("py_path", "")
        )
    }
}

/**
 * 一个监控区域（patch）。
 * rect 为归一化坐标（0..1），相对于预览画面。
 */
data class Patch(
    val id: String = UUID.randomUUID().toString(),
    var x: Float = 0f,
    var y: Float = 0f,
    var w: Float = 0f,
    var h: Float = 0f,
    var monitor: Boolean = true,
    /** 多行触发条件；任意一条满足即可触发对应动作 */
    var rules: MutableList<TriggerRule> = mutableListOf(TriggerRule()),
    /** 用户最后一次保存这个区域/条件的时间（毫秒） */
    var updatedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x.toDouble()); put("y", y.toDouble())
        put("w", w.toDouble()); put("h", h.toDouble())
        put("monitor", monitor)
        put("rules", JSONArray().apply {
            rules.forEach { put(it.toJson()) }
        })
        // 兼容旧版本读取：旧 APK 只会看到第一条规则
        val first = rules.firstOrNull { it.condition.isNotBlank() } ?: rules.firstOrNull()
        put("ask", if (first == null) "" else if (first.question.isBlank()) first.condition
                   else "${first.condition}。${first.question}")
        put("think", first?.think ?: false)
        put("py_path", first?.pyPath ?: "")
        put("updated_at", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Patch {
            val rulesArr = o.optJSONArray("rules")
            val rules = if (rulesArr != null) {
                mutableListOf<TriggerRule>().apply {
                    for (i in 0 until rulesArr.length()) {
                        val r = rulesArr.optJSONObject(i) ?: continue
                        try { add(TriggerRule.fromJson(r)) } catch (_: Exception) { }
                    }
                }
            } else {
                // 旧配置只有 ask/think/py_path：按行迁移成多条规则，
                // 每行再按「。」拆成条件/问题两个字段。
                val ask = o.optString("ask", "")
                val lines = ask.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
                val legacyThink = o.optBoolean("think", false)
                val legacyPy = o.optString("py_path", "")
                if (lines.size > 1) {
                    lines.mapIndexed { i, line ->
                        val (c, q) = Condition.split(line)
                        TriggerRule(
                            condition = c, question = q,
                            think = if (i == 0) legacyThink else false,
                            pyPath = if (i == 0) legacyPy else ""
                        )
                    }.toMutableList()
                } else {
                    val (condition, question) = Condition.split(ask)
                    mutableListOf(TriggerRule(
                        condition = condition,
                        question = question,
                        think = legacyThink,
                        pyPath = legacyPy
                    ))
                }
            }
            return Patch(
                id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() },
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                w = o.optDouble("w", 0.0).toFloat(),
                h = o.optDouble("h", 0.0).toFloat(),
                monitor = o.optBoolean("monitor", true),
                rules = rules,
                updatedAt = o.optLong("updated_at", 0L)
            )
        }
    }
}

object ConfigStore {
    private const val PREFS = "patchnotify"
    private const val KEY_PATCHES = "patches"

    fun load(ctx: Context): MutableList<Patch> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PATCHES, null)
            ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Patch>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                try { out.add(Patch.fromJson(o)) } catch (_: Exception) { }
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<Patch>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PATCHES, arr.toString()).apply()
    }
}

/** 每个区域最近一次的判定结果 */
data class PatchRead(val patchId: String, val raw: String, val at: Long)

/** 界面上显示的名字：第一条非空判断条件的前 10 个字 */
fun Patch.title(): String =
    rules.firstOrNull { it.condition.isNotBlank() }?.condition?.take(10) ?: "未设条件"

/** 这个区域是否配置了 Python 脚本（任意一条规则） */
fun Patch.hasPython(): Boolean = rules.any { it.pyPath.isNotBlank() }

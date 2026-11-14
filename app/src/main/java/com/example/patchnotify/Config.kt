package com.example.patchnotify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    /** 一句话规则：「条件。条件成立后要问的问题（可选）」。VLM 只回答是/否 */
    var ask: String = "",
    /** 允许模型先思考再回答（慢，开放条件才需要） */
    var think: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x.toDouble()); put("y", y.toDouble())
        put("w", w.toDouble()); put("h", h.toDouble())
        put("monitor", monitor)
        put("ask", ask)
        put("think", think)
    }

    companion object {
        fun fromJson(o: JSONObject): Patch {
            var ask = o.optString("ask", "")
            // 兼容旧配置：`>6600 好了 5` 这种 DSL 或 rules 数组，转成一句话
            if (ask.isBlank()) {
                val rules = o.optJSONArray("rules")
                val last = if (rules != null && rules.length() > 0) rules.getJSONObject(rules.length() - 1) else null
                val expr = (last?.optString("expr") ?: o.optString("expr", "")).trim()
                val speech = last?.optString("speech") ?: o.optString("speech", "")
                if (expr.isNotBlank()) {
                    val cond = when {
                        expr.startsWith("~") -> expr.removePrefix("~").trim()
                        Regex("^.+?>\\s*(\\d+)$").find(expr) != null ->
                            "读数是否已经超过 " + Regex("^.+?>\\s*(\\d+)$").find(expr)!!.groupValues[1]
                        Regex("^.+?<\\s*(\\d+)$").find(expr) != null ->
                            "读数是否已经低于 " + Regex("^.+?<\\s*(\\d+)$").find(expr)!!.groupValues[1]
                        else -> "画面是否满足 $expr"
                    }
                    ask = if (speech.isBlank()) cond else "$cond。$speech"
                }
            }
            return Patch(
                id = o.optString("id", UUID.randomUUID().toString()),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                w = o.optDouble("w", 0.0).toFloat(),
                h = o.optDouble("h", 0.0).toFloat(),
                monitor = o.optBoolean("monitor", true),
                ask = ask,
                think = o.optBoolean("think", false)
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
            MutableList(arr.length()) { Patch.fromJson(arr.getJSONObject(it)) }
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

/** 界面上显示的名字：判断条件的前 10 个字 */
fun Patch.title(): String = AskRule.conditionOf(ask).take(10).ifBlank { "未设条件" }

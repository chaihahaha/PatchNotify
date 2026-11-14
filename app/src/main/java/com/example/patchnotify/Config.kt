package com.example.patchnotify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一条报警规则：条件 + 播报内容 + 播报时长（秒） */
data class Rule(
    var expr: String = "",
    var speech: String = "",
    var seconds: Int = 5
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("expr", expr)
        put("speech", speech)
        put("seconds", seconds)
    }

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            expr = o.optString("expr", ""),
            speech = o.optString("speech", ""),
            seconds = o.optInt("seconds", 5).coerceIn(1, 120)
        )
    }
}

/**
 * 一个监控区域（patch）。
 * rect 为归一化坐标（0..1），相对于预览画面。
 */
data class Patch(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var field: String = "",
    var x: Float = 0f,
    var y: Float = 0f,
    var w: Float = 0f,
    var h: Float = 0f,
    var monitor: Boolean = true,
    /** 多条规则，按"越靠后越严重"理解：同时命中多条时用最后一条 */
    var rules: MutableList<Rule> = mutableListOf(),
    /** 区域里有多个数字时取哪个：max / first / last */
    var pick: String = "max",
    /** 识别方式：local（手机本地 Qwen，默认）/ auto（先数码管后退回 ML Kit）/ seg7 / text */
    var mode: String = "local",
    /** 识别内容：num（数字）/ cn（中文）/ en（英文）/ mix（混合） */
    var kind: String = "num"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("field", field)
        put("x", x.toDouble()); put("y", y.toDouble())
        put("w", w.toDouble()); put("h", h.toDouble())
        put("monitor", monitor)
        put("pick", pick)
        put("mode", mode)
        put("kind", kind)
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        put("rules", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Patch {
            val rules = mutableListOf<Rule>()
            o.optJSONArray("rules")?.let { arr ->
                for (i in 0 until arr.length()) rules.add(Rule.fromJson(arr.getJSONObject(i)))
            }
            // 兼容旧配置：单个 expr/speech 迁移成一条规则
            val legacyExpr = o.optString("expr", "")
            if (rules.isEmpty() && legacyExpr.isNotBlank()) {
                rules.add(Rule(legacyExpr, o.optString("speech", ""), 5))
            }
            return Patch(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                field = o.optString("field", ""),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                w = o.optDouble("w", 0.0).toFloat(),
                h = o.optDouble("h", 0.0).toFloat(),
                monitor = o.optBoolean("monitor", true),
                rules = rules,
                pick = o.optString("pick", "max"),
                mode = o.optString("mode", "local"),
                kind = o.optString("kind", "num")
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

/** 每帧的识别结果：raw 是 OCR 原文，value 是从中取出的那个数 */
data class PatchRead(val patchId: String, val raw: String, val value: Double?, val at: Long)

/**
 * 把所有 patch 的当前值拼成一个 JSON 对象：每个 patch 对应一个域。
 */
object ResultJson {
    fun build(patches: List<Patch>, reads: Map<String, PatchRead>, hits: Set<String>): JSONObject {
        val o = JSONObject()
        for (p in patches) {
            val key = p.field.ifBlank { p.name.ifBlank { p.id.take(6) } }
            val v = reads[p.id]?.value
            if (v != null) o.put(key, v) else o.put(key, reads[p.id]?.raw ?: "")
        }
        o.put("_triggered", JSONArray(hits.toList()))
        return o
    }
}

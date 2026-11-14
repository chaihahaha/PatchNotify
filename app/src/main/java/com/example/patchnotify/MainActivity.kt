package com.example.patchnotify

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.patchnotify.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val patches = mutableListOf<Patch>()
    private val reads = mutableMapOf<String, PatchRead>()
    private val hits = mutableSetOf<String>()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var alertJob: Job? = null
    private var alerting = false


    private val tone: ToneGenerator by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 95) }

    private var running = false
    private var lastUiAt = 0L
    private var lastHintAt = 0L

    private var lastServerAt = 0L
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private val afHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var frameSeq = 0
    private var lastNullLogAt = 0L
    private var lastFrameAt = 0L
    private var lastFrameLogAt = 0L
    private var lastTickLogAt = 0L
    private var rrIndex = 0
    private val nlState = HashMap<String, Boolean>()     // patch -> 上次是否命中
    private val nlRearmAt = HashMap<String, Long>()      // patch -> 允许再次触发的时间
    private var busy = false
    private var busyAt = 0L
    private var lastBusyLogAt = 0L
    private var lastCropDumpAt = 0L
    private var lastFrameSize = ""
    // 帧间缓存按 patch 分开存：多个 patch 共用一份会把 A 的读数当成 B 的
    private val sigByPatch = HashMap<String, IntArray>()
    private val resultByPatch = HashMap<String, String>()
    private val resultAtByPatch = HashMap<String, Long>()
    private var localBusy = false
    private var localBusyAt = 0L
    private var localReady = false
    private var localModelDir = java.io.File(
        android.os.Environment.getExternalStorageDirectory(), "patchnotify/qwen_2b"
    ).absolutePath

    /** 选一张照片，跑一遍同样的预处理 + OCR，用来判断相机读不出来到底是哪一环的问题 */
    private val pickImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) testWithImage(uri) }

    companion object {
        /** 每次发版改这里，屏幕上会显示，便于确认装的是哪个包 */
        const val BUILD_TAG = "v71-next-catch"
        private const val REQ_PERMS = 1001
        private const val FRAME_INTERVAL_MS = 250L      // 4 fps
        private const val OCR_BUDGET_MS = 200L
        private const val REARM_MS = 8000L              // 条件恢复后至少等这么久才允许再报
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        patches.addAll(ConfigStore.load(this))

        // 老版本用的是「>6600 好了 5」这种 DSL，升级后转换成一句自然语言条件
        run {
            var migrated = false
            for (pt in patches) {
                pt.mode = "local"                      // 旧版本可能存了 seg7/auto/text
                if (pt.ask.isNotBlank() || pt.rules.isEmpty()) continue
                val r = pt.rules.last()
                val expr = r.expr.trim()
                val cond = when {
                    expr.startsWith("~") -> expr.removePrefix("~").trim()
                    Regex("^.+?>\\s*(\\d+)$").find(expr) != null ->
                        "读数是否已经超过 " + Regex("^.+?>\\s*(\\d+)$").find(expr)!!.groupValues[1]
                    Regex("^.+?<\\s*(\\d+)$").find(expr) != null ->
                        "读数是否已经低于 " + Regex("^.+?<\\s*(\\d+)$").find(expr)!!.groupValues[1]
                    else -> "画面是否满足 ${expr}"
                }
                pt.ask = if (r.speech.isBlank()) cond else "$cond。${r.speech}"
                pt.rules = mutableListOf()
                migrated = true
            }
            if (migrated) ConfigStore.save(this, patches)
        }
        localModelDir = getSharedPreferences("patchnotify", MODE_PRIVATE)
            .getString("modelDir", localModelDir) ?: localModelDir

        // 一次性迁移：把旧的默认阈值（211 的倍数）整体下调 20，提前提醒
        val pref0 = getSharedPreferences("patchnotify", MODE_PRIVATE)
        if (!pref0.getBoolean("shift20", false)) {
            var changed = false
            for (pt in patches) {
                for (r in pt.rules) {
                    val m = Regex("^(.+?)>\\s*(\\d+)$").find(r.expr) ?: continue
                    val n = m.groupValues[2].toIntOrNull() ?: continue
                    if (n % 211 == 0 && n in 211..2110) {
                        r.expr = m.groupValues[1] + ">" + (n - 20)
                        changed = true
                    }
                }
            }
            if (changed) ConfigStore.save(this, patches)
            pref0.edit().putBoolean("shift20", true).apply()
        }

        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"
        pnLog("=== 启动 $BUILD_TAG 权限=${hasAllFilesAccess()} ===")
        pnLog("device: model=${android.os.Build.MODEL} soc=$soc hw=${android.os.Build.HARDWARE} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        try { PatchLog.init(this, java.io.File(logDir())) } catch (e: Exception) { pnLog("patchlog init 失败: ${e.message}") }
        initTts()
        setupUi()

        binding.buildText.text = "PatchNotify $BUILD_TAG · 点这里看日志"
        binding.buildText.setOnClickListener { showLog() }
        ensurePermissions()

        // 调试入口：adb shell am start -n com.example.patchnotify/.MainActivity --es testImage /sdcard/x.jpg
        intent?.getStringExtra("testImage")?.let { path ->
            binding.root.postDelayed({
                testWithImage(android.net.Uri.fromFile(java.io.File(path)))
            }, 1500)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun setupUi() {
        binding.btnAddPatch.setOnClickListener { startRectEdit() }
        binding.btnAddPatch.setOnLongClickListener {
            Toast.makeText(this, "选一张照片测试识别效果", Toast.LENGTH_SHORT).show()
            pickImage.launch("image/*")
            true
        }
        binding.btnCondition.setOnClickListener { pickPatchForRules() }
        binding.btnCondition.setOnLongClickListener {
            showLog()
            true
        }
        binding.btnStartStop.setOnClickListener { toggleRunning() }
        binding.btnStopAlert.setOnClickListener { onAlertStoppedByUser() }

        binding.overlayView.onRectDrawn = { x, y, w, h ->
            binding.overlayView.editMode = false
            binding.overlayView.invalidate()
            askFieldName(x, y, w, h)
        }

        refreshPatchList()
        setStatus("就绪。先框选区域，再设规则。")
    }

    private fun setStatus(s: String) {
        runOnUiThread { binding.statusText.text = s }
    }

    private fun refreshPatchList() {
        runOnUiThread {
            binding.patchList.removeAllViews()
            if (patches.isEmpty()) {
                val tv = TextView(this)
                tv.text = "还没有监控区域。点「框选区域」，在画面上拖一个框。"
                tv.setTextColor(0xFFAAAAAA.toInt())
                tv.textSize = 12f
                binding.patchList.addView(tv)
            }
            for (p in patches) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }
                val cb = CheckBox(this).apply {
                    isChecked = p.monitor
                    setOnCheckedChangeListener { _, v ->
                        p.monitor = v
                        ConfigStore.save(this@MainActivity, patches)
                        binding.overlayView.patches = patches
                    }
                }
                val r = reads[p.id]
                val shownRaw = r?.raw?.replace("\n", " ")?.take(18)
                val shownVal = r?.value?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
                val name = TextView(this).apply {
                    text = "${p.field.ifBlank { p.name.ifBlank { "?" } }}  识别:[" +
                        (shownRaw ?: "-") + "] 取值:" + (shownVal ?: "无") +
                        "  规则${p.rules.size}"
                    textSize = 12f
                    setTextColor(if (hits.contains(p.id)) 0xFFFF5252.toInt() else 0xFFDDDDDD.toInt())
                    setOnClickListener { editRulesDialog(p) }
                }
                row.addView(cb)
                row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val del = Button(this).apply {
                    text = "删"
                    textSize = 12f
                    setOnClickListener {
                        patches.remove(p)
                        reads.remove(p.id)
                        ConfigStore.save(this@MainActivity, patches)
                        binding.overlayView.patches = patches
                        refreshPatchList()
                    }
                }
                row.addView(del)
                binding.patchList.addView(row)
            }
            binding.overlayView.patches = patches
        }
    }

    private fun startRectEdit() {
        binding.overlayView.editMode = true
        setStatus("框选模式：在画面上拖动出一个矩形")
        Toast.makeText(this, "在画面上拖出一个矩形", Toast.LENGTH_SHORT).show()
    }

    private fun askFieldName(x: Float, y: Float, w: Float, h: Float) {
        val input = EditText(this).apply { hint = "字段名，例如 x"; setText("") }
        AlertDialog.Builder(this)
            .setTitle("新监控区域")
            .setMessage("起个字段名（作为 JSON 的域）")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val f = input.text.toString().trim().ifBlank { "x" }
                val np = Patch(name = f, field = f, x = x, y = y, w = w, h = h)
                patches.add(np)
                ConfigStore.save(this, patches)
                refreshPatchList()
                setStatus("已添加区域：$f")
                editRulesDialog(patches.last())
            }
            .setNegativeButton("取消", null)
            .show()
    }



    private fun pickPatchForRules() {
        if (patches.isEmpty()) {
            Toast.makeText(this, "先框选一个区域", Toast.LENGTH_SHORT).show(); return
        }
        val names = patches.map { it.field.ifBlank { it.name } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择区域")
            .setItems(names) { _, i -> editRulesDialog(patches[i]) }
            .show()
    }

    /** 规则编辑：一行一条  条件 => 播报内容 => 秒数 */
    private fun editRulesDialog(p: Patch) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        layout.addView(TextView(this).apply {
            text = "写一句话，模型只回答「是」或「否」。\n" +
                   "例：数码管读数是否已接近 001680\n" +
                   "想播报就把要播的话写在句号后面：\n" +
                   "数码管读数是否已接近 001680。快充好了\n" +
                   "没有句号 = 命中只响提示音"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        })

        val input = EditText(this).apply {
            setText(p.ask)
            hint = "数码管读数是否已接近 001680。快充好了"
            textSize = 14f
            gravity = Gravity.TOP
            minLines = 3
            maxLines = 6
        }
        layout.addView(input)

        val thinkBox = android.widget.CheckBox(this).apply {
            text = "允许模型先思考（开放式判断更准，每次多花约 10 秒）"
            textSize = 12f
            isChecked = p.think
        }
        layout.addView(thinkBox)

        layout.addView(TextView(this).apply {
            text = "本地模型目录"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        })
        val dirInput = EditText(this).apply {
            setText(localModelDir)
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        layout.addView(dirInput)
        layout.addView(Button(this).apply {
            text = "检测模型目录"
            setOnClickListener { checkModelDir(dirInput.text.toString().trim()) }
        })
        layout.addView(Button(this).apply {
            text = "选择文件夹…"
            setOnClickListener { pickModelDir(dirInput) }
        })
        layout.addView(Button(this).apply {
            text = "导入模型到 App 内（不需要权限）"
            setOnClickListener { importModelDir(dirInput) }
        })

        // 内容比屏幕高，必须包一层 ScrollView，否则下半截（模型目录/导入按钮）够不到
        val scroll = android.widget.ScrollView(this).apply {
            addView(layout, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("规则：" + p.field.ifBlank { p.name })
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNeutralButton("试听第一条", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ask = input.text.toString().replace("\n", " ").trim()
                if (ask.isEmpty()) {
                    Toast.makeText(this, "请写一句话条件", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (AskRule.conditionOf(ask).isEmpty()) {
                    Toast.makeText(this, "句号前没有条件", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                p.ask = ask
                p.think = thinkBox.isChecked
                p.rules = mutableListOf()
                p.mode = "local"
                p.kind = "num"
                localModelDir = dirInput.text.toString().trim().trimEnd('/')
                localReady = false
                getSharedPreferences("patchnotify", MODE_PRIVATE).edit()
                    .putString("modelDir", localModelDir).apply()
                p.monitor = true
                ConfigStore.save(this, patches)
                refreshPatchList()
                dialog.dismiss()
                val text = "已保存：" + AskRule.conditionOf(ask)
                setStatus(text)
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val sp = AskRule.speechOf(input.text.toString())
                speak(sp.ifBlank { "测试播报" })
                Toast.makeText(this, if (sp.isBlank()) "没有播报词，命中只响提示音" else "试听：$sp",
                    Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }


    // ---------------------------------------------------------------- camera

    private fun ensurePermissions() {
        val need = arrayOf(Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startCamera()
        else ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == REQ_PERMS) {
            if (res.all { it == PackageManager.PERMISSION_GRANTED }) startCamera()
            else setStatus("缺少相机权限，无法工作")
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                // SurfaceView 模式下 PreviewView.getBitmap() 不可用，会回落到分析流的帧
                binding.previewView.implementationMode =
                    androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                // 默认 640x480 时，框小黑板只能裁出 50x26，模型读不准；提到 1280x720
                val resSel = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(1920, 1080),
                            androidx.camera.core.resolutionselector.ResolutionStrategy
                                .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val ia = ImageAnalysis.Builder()
                    .setResolutionSelector(resSel)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                ia.setAnalyzer(cameraExecutor) { proxy -> onFrame(proxy) }
                analysis = ia

                provider.unbindAll()
                val backSel = androidx.camera.core.CameraSelector.Builder()
                    .requireLensFacing(androidx.camera.core.CameraSelector.LENS_FACING_BACK)
                    .build()
                val cam = provider.bindToLifecycle(this, backSel, preview, ia)
                pnLog("camera bound lensFacing=${cam.cameraInfo.lensFacing} " +
                      "implMode=${binding.previewView.implementationMode}")
                cameraControl = cam.cameraControl
                // 连续对焦不一定一直锁得住；定期主动触发一次 AF
                afHandler.removeCallbacksAndMessages(null)
                val af = object : Runnable {
                    override fun run() {
                        try {
                            val factory = binding.previewView.meteringPointFactory
                            val pt = factory.createPoint(
                                binding.previewView.width / 2f, binding.previewView.height / 2f)
                            cam.cameraControl.startFocusAndMetering(
                                androidx.camera.core.FocusMeteringAction.Builder(
                                    pt, androidx.camera.core.FocusMeteringAction.FLAG_AF
                                ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build())
                        } catch (e: Throwable) { }
                        afHandler.postDelayed(this, 2000)
                    }
                }
                afHandler.post(af)
                setStatus("相机已启动")
            } catch (e: Exception) {
                setStatus("相机启动失败：${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------- OCR

    /** 包一层：任何未捕获异常都要记下来并把 busy 解开，否则管线会永久卡死 */
    private fun onFrame(proxy: ImageProxy) {
        try {
            onFrameInner(proxy)
        } catch (t: Throwable) {
            pnLog("onFrame 异常: " + android.util.Log.getStackTraceString(t).take(1200))
            busy = false
            localBusy = false
            runOnUiThread { setStatus("帧处理异常：" + (t.message ?: t.javaClass.simpleName).take(40)) }
        }
    }

    private fun onFrameInner(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        // 每 5 秒记一行，确认分析流到底有没有在出帧
        if (now - lastFrameLogAt > 5000) {
            lastFrameLogAt = now
            pnLog("frame 到达 ${proxy.width}x${proxy.height} running=$running " +
                  "targets=${patches.count { it.monitor }}/${patches.size}")
        }
        if (!running) { proxy.close(); return }   // 没点「开始监控」就不做任何推理
        if (busy && now - busyAt > 90000) {
            busy = false
            pnLog("看门狗：busy 卡住 90s，已复位")
        }
        if (now - lastFrameAt < FRAME_INTERVAL_MS || busy) {
            proxy.close(); return
        }
        lastFrameAt = now

        // 像素来源：分析流。实测它能裁出清晰可读的 233x94（读出过 901679），
        // 而 PreviewView.getBitmap() 在这台机器上恒为 null，不能用。
        var bitmap: Bitmap
        var rot = 0
        try {
            bitmap = proxy.toBitmap()
            rot = proxy.imageInfo.rotationDegrees
        } catch (e: Exception) {
            pnLog("toBitmap 失败: " + (e.message ?: e.javaClass.simpleName))
            proxy.close(); return
        }

        proxy.close()

        // 关键：先把整帧转正，再按归一化坐标裁剪。
        // 否则竖屏预览 + 横置传感器帧会裁出画面外的细条（实测 57×256）。
        if (rot != 0) {
            try {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val rb = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                bitmap.recycle()
                bitmap = rb
            } catch (e: Exception) { /* 保持原样 */ }
        }

        val targets = patches.filter { it.monitor }
        if (targets.isEmpty()) {
            pnLog("没有处于监控状态的区域（共 ${patches.size} 个），请点「设条件」保存一次")
            bitmap.recycle(); busy = false
            setStatus("没有在监控的区域：点「设条件」选中区域并保存")
            return
        }

        if (now - lastTickLogAt > 3000) {
            lastTickLogAt = now
            pnLog("开始轮询 targets=${targets.size} 整帧=${bitmap.width}x${bitmap.height} rot=$rot")
        }
        busy = true
        busyAt = SystemClock.elapsedRealtime()
        val t0 = SystemClock.elapsedRealtime()
        var processed = 0
        var idx = rrIndex % targets.size

        fun next() {
            try {

            if (processed >= targets.size ||
                SystemClock.elapsedRealtime() - t0 > OCR_BUDGET_MS) {
                if (processed == 0) pnLog("next() 直接返回：budget=${SystemClock.elapsedRealtime() - t0}ms")
                bitmap.recycle()
                busy = false
                rrIndex = (rrIndex + processed + 1) % targets.size
                return
            }
            val p = targets[idx % targets.size]
            idx++
            processed++

            val crop = try {
                cropPatch(bitmap, p, 0)
            } catch (e: Exception) {
                null
            }
            if (crop == null) {
                pnLog("crop 失败 patch=${p.field} rect=${p.x},${p.y},${p.w},${p.h} 整帧=${bitmap.width}x${bitmap.height}")
                next(); return
            }
            if (crop.width < 40 || crop.height < 16) {
                pnLog("crop 太小 ${crop.width}x${crop.height}")
                // 裁出来太小基本等于画面外，直接判失败，别把垃圾发给模型
                val cw = crop.width; val ch = crop.height
                crop.recycle()
                setStatus("${p.field} 框太小:${cw}x${ch}，请重画")
                next(); return
            }

            pnLog("next: crop=${crop.width}x${crop.height} patch=${p.field}")
            // 唯一路径：本地 VLM 判断（mode 字段已无意义，旧配置里可能是 seg7/auto/text）
            run {
                if (!hasAllFilesAccess()) {
                    val api = android.os.Build.VERSION.SDK_INT
                    val mgr = if (api >= 30) android.os.Environment.isExternalStorageManager().toString() else "-"
                    pnLog("local: 没有文件访问权限 api=$api isExternalStorageManager=$mgr")
                    crop.recycle()
                    runOnUiThread {
                        setStatus("没有文件访问权限(API$api 管理所有文件=$mgr)")
                        askAllFilesAccess()
                    }
                    bitmap.recycle()
                    busy = false          // ★ 不释放的话整条帧循环会永久卡住
                    return
                }
                if (localBusy) {
                    val stuck = SystemClock.elapsedRealtime() - localBusyAt
                    if (stuck > 60_000) {
                        // 推理线程异常死掉时会把整条管线冻住，这里强制解开
                        pnLog("local: 推理卡了 ${stuck / 1000}s，强制复位")
                        localBusy = false
                    } else {
                        if (SystemClock.elapsedRealtime() - lastBusyLogAt > 2000) {
                            lastBusyLogAt = SystemClock.elapsedRealtime()
                            pnLog("local: 上一帧还在推理，丢帧")
                        }
                        crop.recycle(); next(); return
                    }
                }
                localBusy = true
                localBusyAt = SystemClock.elapsedRealtime()
                val bmp = crop
                if (p.ask.isBlank()) {
                    pnLog("local: ${p.field} 还没有判断条件，跳过并提示")
                    crop.recycle()
                    bitmap.recycle()
                    localBusy = false
                    busy = false
                    setStatus("「${p.field}」还没写判断条件 —— 点「设条件」写一句话，例如：读数是否已接近001680")
                    return
                }
                val fs = "${bmp.width}x${bmp.height}"
                if (fs != lastFrameSize) { lastFrameSize = fs; pnLog("local: 整帧=${bitmap.width}x${bitmap.height} crop=$fs") }
                pnLog("local: 进入 patch=${p.field} crop=$fs think=${p.think}")
                val found = resolveModelDir()
                if (found != localModelDir) { pnLog("local: 换用 $found"); localModelDir = found }
                Thread {
                    var r = "ERR"
                    try {
                        if (!localReady) {
                            pnLog("local: 开始加载模型…")
                            val st = NativeOcr.probe(localModelDir)
                            pnLog("local: probe → $st")
                            localReady = st == "ok" || st.startsWith("ok ")
                            if (!localReady) {
                                r = if (st.startsWith("ERR:")) st + "（点“设条件”→检测模型目录）"
                                    else "模型加载失败：$st"
                            }
                        }
                        if (localReady) {
                            // 诊断：每 5 秒存一张实际喂给模型的裁剪图
                            val nowDump = SystemClock.elapsedRealtime()
                            if (nowDump - lastCropDumpAt > 5000) {
                                lastCropDumpAt = nowDump
                                try {
                                    java.io.File(logDir(), "last_crop.jpg").outputStream().use {
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)
                                    }
                                    pnLog("local: 已存 last_crop.jpg ${bmp.width}x${bmp.height}")
                                } catch (e: Exception) { pnLog("dump crop 失败: ${e.message}") }
                            }
                            val bw = bmp.width; val bh = bmp.height
                            val px = IntArray(bw * bh)
                            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
                            val rgb = ByteArray(bw * bh * 3)
                            for (i in px.indices) {
                                val c = px[i]
                                rgb[i * 3] = ((c shr 16) and 0xFF).toByte()
                                rgb[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
                                rgb[i * 3 + 2] = (c and 0xFF).toByte()
                            }
                            // 画面几乎没变就直接复用上次结果，避免每帧都白跑一次推理
                            val sig = try {
                                val sw = 48; val sh = 20
                                val sc = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
                                val sp = IntArray(sw * sh)
                                sc.getPixels(sp, 0, sw, 0, 0, sw, sh); sc.recycle()
                                IntArray(sw * sh) { i ->
                                    val c = sp[i]
                                    (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                                }
                            } catch (e: Throwable) { null }
                            val prev = sigByPatch[p.id]
                            var changed = true
                            if (prev != null && sig != null && prev.size == sig.size) {
                                var n = 0
                                for (i in sig.indices) if (kotlin.math.abs(sig[i] - prev[i]) > 28) n++
                                changed = n > sig.size * 15 / 1000
                            }
                            val cached = resultByPatch[p.id]
                            val cachedAt = resultAtByPatch[p.id] ?: 0L
                            if (!changed && cached != null && !cached.startsWith("ERR") &&
                                SystemClock.elapsedRealtime() - cachedAt < 120_000) {
                                r = cached
                                pnLog("local: 画面未变化，复用「$r」")
                            } else {
                                val t0 = SystemClock.elapsedRealtime()
                                r = NativeOcr.ocrPrompt(rgb, bw, bh, p.ask, p.think)
                                if (r.startsWith("ERR:TOOBIG:")) {
                                    val a = r.split(":")
                                    r = "框太大：${a.getOrNull(2)} 个图像token（上限 ${a.getOrNull(3)}），请把框划小一点"
                                }
                                sigByPatch[p.id] = sig ?: IntArray(0)
                                resultByPatch[p.id] = r
                                resultAtByPatch[p.id] = SystemClock.elapsedRealtime()
                                val dt = (SystemClock.elapsedRealtime() - t0) / 1000.0
                                pnLog("local: ocr → 「$r」 用时 %.1fs".format(dt))
                                runOnUiThread { setStatus("本地 ${String.format("%.1f", dt)}s ${bmp.width}x${bmp.height} → 「$r」") }
                            }
                        }
                    } catch (e: Throwable) {
                        r = "ERR:" + (e.message ?: "").take(40)
                        pnLog("local: 异常 " + android.util.Log.getStackTraceString(e).take(1200))
                    }
                    bmp.recycle()
                    localBusy = false
                    runOnUiThread { onVerdict(p, r) }
                }.start()
                bitmap.recycle()
                busy = false              // ★ 推理在后台线程跑，这里不能占着 busy
                return
            }

        next()
    
            } catch (t: Throwable) {
                pnLog("next 异常: " + android.util.Log.getStackTraceString(t).take(800))
                busy = false
                localBusy = false
            }
}
    }

    /** 裁剪 + 摆正 + 数码管增强 */
    private fun cropPatch(src: Bitmap, p: Patch, rotation: Int): Bitmap? {
        val w = src.width
        val h = src.height
        val left = (p.x * w).toInt().coerceIn(0, w - 1)
        val top = (p.y * h).toInt().coerceIn(0, h - 1)
        val rw = (p.w * w).toInt().coerceAtLeast(8).coerceAtMost(w - left)
        val rh = (p.h * h).toInt().coerceAtLeast(8).coerceAtMost(h - top)

        var bmp = Bitmap.createBitmap(src, left, top, rw, rh)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    private fun testWithImage(uri: android.net.Uri) {
        setStatus("正在识别这张照片……")
        lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val bmp0 = (if (uri.scheme == "file") java.io.FileInputStream(java.io.File(uri.path!!))
                                else contentResolver.openInputStream(uri)!!).use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                    val maxSide = 1600f
                    val sc = maxSide / maxOf(bmp0.width, bmp0.height)
                    val img = if (sc < 1f)
                        Bitmap.createScaledBitmap(bmp0, (bmp0.width * sc).toInt(), (bmp0.height * sc).toInt(), true)
                    else bmp0
                    if (!localReady) {
                        val st = NativeOcr.probe(resolveModelDir().also { localModelDir = it })
                        localReady = st == "ok" || st.startsWith("ok ")
                        if (!localReady) return@withContext "ERR:$st"
                    }
                    val w = img.width; val h = img.height
                    val px = IntArray(w * h)
                    img.getPixels(px, 0, w, 0, 0, w, h)
                    val rgb = ByteArray(w * h * 3)
                    for (i in px.indices) {
                        val c = px[i]
                        rgb[i * 3] = ((c shr 16) and 0xFF).toByte()
                        rgb[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
                        rgb[i * 3 + 2] = (c and 0xFF).toByte()
                    }
                    "OK:" + w + "x" + h + "\u0001" + NativeOcr.ocrPrompt(rgb, w, h, "读出图中数字", false)
                } catch (e: Exception) {
                    "ERR:" + e.message
                }
            }
            if (result.startsWith("ERR:")) { setStatus(result); return@launch }
            val parts = result.removePrefix("OK:").split("\u0001")
            setStatus("照片识别完成")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("照片识别结果")
                .setMessage("尺寸：" + parts.getOrNull(0) + "\n\n模型回答：\n" + (parts.getOrNull(1) ?: ""))
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun importModelDir(target: android.widget.EditText) {
        dirEdit = target
        try {
            startActivityForResult(
                android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE), 2002)
        } catch (e: Throwable) {
            Toast.makeText(this, "这台机器没有文件夹选择器", Toast.LENGTH_LONG).show()
        }
    }

    /** 走 SAF 把选中目录整棵复制进 App 私有目录，完全不需要「所有文件访问」 */
    private fun doImport(uri: android.net.Uri, target: android.widget.EditText?) {
        Thread {
            try {
                val root = java.io.File(filesDir, "models/qwen_2b")
                if (root.exists()) root.deleteRecursively()
                root.mkdirs()
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                if (tree == null) { runOnUiThread { setStatus("无法读取所选文件夹") }; return@Thread }
                var n = 0; var bytes = 0L
                fun walk(dir: androidx.documentfile.provider.DocumentFile, out: java.io.File) {
                    out.mkdirs()
                    for (f in dir.listFiles()) {
                        if (f.isDirectory) walk(f, java.io.File(out, f.name ?: ""))
                        else {
                            val name = f.name ?: continue
                            val dst = java.io.File(out, name)
                            contentResolver.openInputStream(f.uri)?.use { ins ->
                                dst.outputStream().use { outs -> bytes += ins.copyTo(outs) }
                            }
                            n++
                            if (n % 2 == 0) runOnUiThread { setStatus("导入中 $n 个文件 ${bytes / 1048576} MB") }
                        }
                    }
                }
                walk(tree, root)
                pnLog("import: $n files, ${bytes / 1048576} MB -> ${root.absolutePath}")
                val okDir = if (java.io.File(root, "onnx").isDirectory) root
                            else if (java.io.File(root, "qwen_2b/onnx").isDirectory) java.io.File(root, "qwen_2b")
                            else root
                runOnUiThread {
                    target?.setText(okDir.absolutePath)
                    localModelDir = okDir.absolutePath
                    localReady = false
                    getSharedPreferences("patchnotify", MODE_PRIVATE).edit()
                        .putString("modelDir", localModelDir).apply()
                    setStatus("导入完成：$n 个文件 ${bytes / 1048576} MB")
                    checkModelDir(okDir.absolutePath)
                }
            } catch (e: Throwable) {
                pnLog("import 失败 " + android.util.Log.getStackTraceString(e).take(800))
                runOnUiThread { setStatus("导入失败：" + (e.message ?: "")) }
            }
        }.start()
    }

    private var dirEdit: android.widget.EditText? = null

    /** 检查模型目录，缺什么报什么 */
    private fun checkModelDir(pathIn: String) {
        val path = pathIn.trim().trimEnd('/')
        if (path.isEmpty()) { setStatus("模型目录为空"); return }
        val dir = java.io.File(path)
        val files = dir.listFiles()?.map { it.name } ?: emptyList()
        val hasModel = files.any { it.endsWith(".gguf") && !it.startsWith("mmproj") }
        val hasMmproj = files.any { it.startsWith("mmproj") }
        val need = listOf("模型 .gguf", "mmproj .gguf")
        val missing = when {
            !hasModel -> listOf(need[0])
            !hasMmproj -> listOf(need[1])
            else -> emptyList()
        }
        val msg = when {
            !dir.isDirectory -> "目录不存在：$path"
            missing.isEmpty() -> "模型齐全（${files.size} 个文件）\n$dir"
            else -> "缺 ${missing.size} 个文件：\n" + missing.joinToString("\n") +
                    "\n当前目录有：\n" + files.take(8).joinToString("\n")
        }
        pnLog("check model dir: $path → " + if (missing.isEmpty()) "OK" else "missing=$missing")
        setStatus(msg)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("模型目录检测")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun pickModelDir(target: android.widget.EditText) {
        dirEdit = target
        try {
            startActivityForResult(
                android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE), 2001)
        } catch (e: Throwable) {
            Toast.makeText(this, "这台机器没有文件夹选择器", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 2002) { doImport(uri, dirEdit); return }
        if (requestCode != 2001) return
        val path = runCatching {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            val root = if (parts[0].equals("primary", true)) {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/" + parts[0]
            }
            (root + "/" + parts.getOrElse(1) { "" }).trimEnd('/')
        }.getOrNull()
        if (path == null) { Toast.makeText(this, "无法转换成路径", Toast.LENGTH_LONG).show(); return }
        pnLog("picked dir: $path")
        dirEdit?.setText(path)
        checkModelDir(path)
    }

    /** 在常见位置里找一个含 onnx/ 的目录，避免用户放对了却因为路径不同读不到 */
    private fun resolveModelDir(): String {
        val root = android.os.Environment.getExternalStorageDirectory()
        val priv = java.io.File(filesDir, "models/qwen_2b")
        if (java.io.File(priv, "onnx").isDirectory) return priv.absolutePath
        val cands = listOf(
            java.io.File(localModelDir),
            java.io.File(root, "patchnotify/qwen_2b"),
            java.io.File(root, "Download/patchnotify/qwen_2b"),
            java.io.File(root, "Download/qwen_2b"),
            java.io.File(root, "qwen_2b")
        )
        for (c in cands) {
            if (java.io.File(c, "onnx").isDirectory) return c.absolutePath
            if (java.io.File(c, "vision_encoder_fp16.onnx").isFile) return c.absolutePath
        }
        return localModelDir
    }


    /** 直接在屏幕上显示日志尾部，避免来回复制文件拿到旧内容 */
    private fun showLog() {
        val sb = StringBuilder()
        for (src in listOf(java.io.File(filesDir, "pnocr.log"),
                           java.io.File(logDir(), "pnocr.log"))) {
            val alive = if (src.isFile) {
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(src.lastModified()))
            } else "不存在"
            sb.append(src.absolutePath).append("  [").append(alive).append("]\n")
            if (src.isFile) {
                val lines = src.readLines()
                lines.takeLast(80).forEach { sb.append(it).append("\n") }
            }
            sb.append("\n----------------\n")
        }
        val tv = android.widget.TextView(this).apply {
            text = sb.toString()
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("日志（点顶部蓝字刷新）")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("刷新") { _, _ -> showLog() }
            .show()
    }

    /** 诊断日志：写 logcat + patchnotify/pnocr.log（模型目录的上一层） */
    private fun logDir(): String = java.io.File(localModelDir).parent ?: localModelDir
    private fun pnLog(msg: String) {
        android.util.Log.i("pnocr", msg)
        try {
            val f = java.io.File(logDir(), "pnocr.log")
            f.parentFile?.mkdirs()
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$ts $msg\n")
        } catch (e: Throwable) { }
        try {
            runOnUiThread {
                binding.buildText.text = "PatchNotify $BUILD_TAG · " + msg.take(48)
            }
        } catch (e: Throwable) { }
        try {
            val g = java.io.File(filesDir, "pnocr.log")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            g.appendText("$ts $msg\n")
        } catch (e: Throwable) { }
    }

    /** 本进程已经确认能写 /sdcard 一次之后就不再重复探测 */
    private var storageOk = false

    private fun hasAllFilesAccess(): Boolean {
        if (storageOk) return true
        if (android.os.Build.VERSION.SDK_INT >= 30 &&
            android.os.Environment.isExternalStorageManager()) {
            storageOk = true
            return true
        }
        if (android.os.Build.VERSION.SDK_INT < 30 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        // 关键：isExternalStorageManager() 在部分 ROM 上会被进程缓存，
        // 授予后仍返回 false，必须重启 App 才生效。这里直接试写一次，拿真实结果。
        return try {
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "patchnotify")
            dir.mkdirs()
            val probe = java.io.File(dir, ".perm_probe")
            probe.writeText("ok")
            probe.delete()
            storageOk = true
            pnLog("权限实测通过：可以写 " + dir.absolutePath)
            true
        } catch (e: Throwable) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时立刻重新判定，不必重启 App
        askingAccess = false
        if (!storageOk && hasAllFilesAccess()) {
            pnLog("onResume: 权限已生效")
            localReady = false
            setStatus("文件权限已就绪")
        }
    }

    private var askingAccess = false
    private fun askAllFilesAccess() {
        if (askingAccess) return
        askingAccess = true
        val msg = "本地识别需要读取 /sdcard/patchnotify 里的模型文件。\n\n" +
            "下一步会打开系统设置，请找到「PatchNotify」并打开「允许访问所有文件管理」或「所有文件访问权限」。"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要文件访问权限")
            .setMessage(msg)
            .setPositiveButton("去设置") { _, _ ->
                askingAccess = false
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")))
                    } else {
                        requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
                    }
                } catch (e: Throwable) {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            .setNegativeButton("取消") { _, _ -> askingAccess = false }
            .show()
    }

    /**
     * 自然语言规则的判定结果。
     * 只在「否 → 是」的跃变时响一次；条件恢复后才重新武装，避免条件持续满足时一直响。
     */
    private fun onVerdict(p: Patch, rawIn: String) {
        val parts = rawIn.split("|")
        val raw = parts[0]
        val digits = parts.getOrNull(1)?.trim().orEmpty()
        val yes = AskRule.parse(raw)
        val shown = when (yes) {
            true -> if (digits.isEmpty()) "是" else "$digits 是"
            false -> if (digits.isEmpty()) "否" else "$digits 否"
            null -> rawIn.take(16).ifBlank { "?" }
        }
        reads[p.id] = PatchRead(p.id, shown, null, SystemClock.elapsedRealtime())

        try {
            if (PatchLog.shouldLog(p.id, shown)) PatchLog.log(p, shown)
        } catch (_: Exception) { }

        val prev = nlState[p.id] ?: false
        nlState[p.id] = (yes == true)

        val now = SystemClock.elapsedRealtime()
        if (yes == false) {                      // 条件恢复 → 重新武装
            if (prev) nlRearmAt[p.id] = now + REARM_MS
        }

        if (SystemClock.elapsedRealtime() - lastUiAt > 400) {
            lastUiAt = SystemClock.elapsedRealtime()
            runOnUiThread { refreshPatchList() }
        }

        if (yes == true && !prev && running && !alerting) {
            val canFire = now >= (nlRearmAt[p.id] ?: 0L)
            if (canFire) {
                nlRearmAt[p.id] = now + REARM_MS
                val speech = AskRule.speechOf(p.ask)
                pnLog("规则命中：${p.field} 「${AskRule.conditionOf(p.ask)}」 → 播报「$speech」")
                triggerAlert(p, speech, 5, AskRule.conditionOf(p.ask))
            }
        }
        setStatus("${p.field}: $shown")
    }

    // ---------------------------------------------------------------- 播报

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                ttsReady = true
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "s")
    }

    /** 触发一条规则：播报/提示音持续 seconds 秒，然后自动停 */
    private fun triggerAlert(p: Patch, r: Rule) =
        triggerAlert(p, r.speech, r.seconds, r.expr)

    private fun triggerAlert(p: Patch, speechIn: String, seconds: Int, label: String) {
        alerting = true
        val secs = seconds.coerceIn(1, 120)
        val text = speechIn.trim()
        val beepOnly = text.isEmpty()          // 没写播报内容 → 只嘟嘟嘟
        runOnUiThread {
            binding.btnStopAlert.visibility = View.VISIBLE
            setStatus("⚠ ${p.field} = ${reads[p.id]?.raw ?: "?"}  规则 $label  " +
                    (if (beepOnly) "提示音 ${secs} 秒" else "播报 ${secs} 秒"))
        }
        alertJob?.cancel()
        alertJob = lifecycleScope.launch {
            val end = SystemClock.elapsedRealtime() + secs * 1000L
            while (isActive && alerting) {
                if (beepOnly) {
                    beepOnce(160)
                    delay(200)
                    beepOnce(160)
                } else {
                    speak(text)
                }
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) break
                delay(minOf(if (beepOnly) 1000L else 3000L, left))
            }
            if (isActive && alerting) {
                // 到时自动结束，直接回到监控
                alerting = false
                tts?.stop()
                runOnUiThread { binding.btnStopAlert.visibility = View.GONE }
                setStatus("播报结束，继续监控中……")
            }
        }
    }

    /** 尖锐提示音：2.8 kHz 方波，走闹钟通道，比系统 beep 响得多 */
    private fun beepOnce(ms: Int) {
        try {
            val sr = 44100
            val n = sr * ms / 1000
            val freq = 2800.0
            val buf = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val env = when {
                    i < n * 0.02 -> i / (n * 0.02)          // 快起音，更"刺"
                    i > n * 0.85 -> (n - i) / (n * 0.15)
                    else -> 1.0
                }
                val s = if ((t * freq).toInt() % 2 == 0) 1.0 else -1.0
                buf[i] = (s * env * Short.MAX_VALUE * 0.95).toInt().toShort()
            }
            val track = android.media.AudioTrack(
                AudioManager.STREAM_ALARM, sr,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                buf.size * 2, android.media.AudioTrack.MODE_STATIC
            )
            track.write(buf, 0, buf.size)
            track.play()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { track.stop(); track.release() } catch (e: Exception) { }
            }, (ms + 400).toLong())
        } catch (e: Exception) {
            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, ms) } catch (e2: Exception) { }
        }
    }

    /** 用户点了「停止播报」→ 进入监听模式 */
    private fun onAlertStoppedByUser() {
        alerting = false
        alertJob?.cancel()
        tts?.stop()
        hits.clear()
        binding.overlayView.hits = hits
        binding.btnStopAlert.visibility = View.GONE
        setStatus("已停止播报")
    }

    private fun beep() {
        try {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
        } catch (e: Exception) { /* ignore */ }
    }

    // ---------------------------------------------------------------- 运行状态

    private fun toggleRunning() {
        running = !running
        pnLog("监控 ${if (running) "开始" else "停止"} patches=${patches.size} " +
              "targets=${patches.count { it.monitor }}")
        binding.btnStartStop.text = if (running) getString(R.string.btn_stop) else getString(R.string.btn_start)
        if (running) {
            setStatus("监控中……（每秒 4 帧 OCR，按 patch 轮询）")
        } else {
            alerting = false
            alertJob?.cancel()
            tts?.stop()
            hits.clear()
            binding.overlayView.hits = hits
            binding.btnStopAlert.visibility = View.GONE
            afHandler.removeCallbacksAndMessages(null)
            setStatus("已停止监控")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        alerting = false
        alertJob?.cancel()
        tts?.stop(); tts?.shutdown()
        analysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}

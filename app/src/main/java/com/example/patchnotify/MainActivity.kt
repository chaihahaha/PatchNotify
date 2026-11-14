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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
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

    /** 已经报过的规则：规则键 -> 解除武装的时刻（0 表示立即可以再报） */
    private val fired = mutableMapOf<String, Long>()
    /** 已经做过「首次读数」的 patch：启动时已满足的阈值静默跳过 */
    private val seeded = mutableSetOf<String>()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val recognizer = TextRecognition.getClient(
        com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
    )
    private var lastFrameAt = 0L
    private var rrIndex = 0
    private var busy = false
    private var busyAt = 0L
    private var lastBusyLogAt = 0L
    private var lastCropDumpAt = 0L
    private var lastFrameSize = ""
    // 帧间缓存必须按 patch 分开存：多个 patch 共用一份会把 A 的读数当成 B 的
    private val sigByPatch = HashMap<String, IntArray>()
    private val resultByPatch = HashMap<String, String>()
    private val resultAtByPatch = HashMap<String, Long>()
    /** 自然语言规则("\~描述")的判定结果，键 = patchId|expr */
    private val nlHits = mutableMapOf<String, Boolean>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var alertJob: Job? = null
    private var alerting = false


    private val tone: ToneGenerator by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 95) }

    private var running = false
    private var lastUiAt = 0L
    private var lastHintAt = 0L
    private val segBuf = mutableMapOf<String, java.util.ArrayDeque<String>>()

    private var lastServerAt = 0L
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private val afHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var frameSeq = 0
    private var lastNullLogAt = 0L
    private var localBusy = false
    private var localReady = false
    private var localModelDir = java.io.File(
        android.os.Environment.getExternalStorageDirectory(), "patchnotify/qwen_2b"
    ).absolutePath

    /** 最近 3 帧里至少 2 帧一致才返回，否则 null */
    private fun stableSegReading(p: Patch, raw: String): String? {
        if (raw.isEmpty()) { segBuf.remove(p.id); return null }
        val buf = segBuf.getOrPut(p.id) { java.util.ArrayDeque() }
        buf.addLast(raw)
        while (buf.size > 3) buf.removeFirst()
        if (buf.size < 2) return null
        val best = buf.groupingBy { it }.eachCount().entries.maxByOrNull { it.value } ?: return null
        return if (best.value >= 2) best.key else null
    }

    /** 选一张照片，跑一遍同样的预处理 + OCR，用来判断相机读不出来到底是哪一环的问题 */
    private val pickImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) testWithImage(uri) }

    companion object {
        /** 每次发版改这里，屏幕上会显示，便于确认装的是哪个包 */
        const val BUILD_TAG = "v63-patchlog"
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
                // 默认规则：每 211 一件，各自只响一次
                if (np.rules.isEmpty()) {
                    var k = 191
                    while (k <= 2090) {
                        np.rules.add(Rule("$f>$k", "", 10))
                        k += 211
                    }
                }
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
            text = "一行一条，用空格分开：条件 播报内容 秒数\n" +
                   "例：\n${p.field}>6600 好了 5\n${p.field}>6700 第二条 5\n" +
                   "条件里可以带空格（${p.field} > 6600 和 ${p.field}>6600 都认）\n" +
                   "播报内容省略 = 只响「嘟嘟嘟」提示音\n" +
                   "秒数省略 = 默认 5 秒；旧写法 ${p.field}>6600=>好了=>5 也还认\n" +
                   "也支持中文描述（以句号结尾），由模型判断是/否：\n瓶子里有白色药片。 提醒 10"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        })

        val input = EditText(this).apply {
            setText(ConditionParser.rulesToText(p.rules))
            hint = "${p.field}>6600 好了 5"
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP
            minLines = 5
            maxLines = 12
        }
        layout.addView(input)

        val pickLabels = arrayOf("取数字：最大值", "取数字：第一个", "取数字：最后一个")
        val pickKeys = arrayOf("max", "first", "last")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, pickLabels)
            setSelection(pickKeys.indexOf(p.pick).coerceAtLeast(0))
        }
        layout.addView(TextView(this).apply {
            text = "显示区里有多组数字时取哪个（欧姆龙 00 1679 → 选最大值）"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        })
        layout.addView(spinner)

        val modeLabels = arrayOf(
            "识别方式：本地 Qwen（默认）",
            "识别方式：自动（先数码管）",
            "识别方式：只读数码管",
            "识别方式：只认印刷文字"
        )
        val modeKeys = arrayOf("local", "auto", "seg7", "text")
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modeLabels)
            setSelection(modeKeys.indexOf(p.mode).coerceAtLeast(0))
        }
        layout.addView(TextView(this).apply {
            text = "本地 Qwen = 手机内推理（默认）；数码管 = LED 计数器/温度表；印刷文字 = 标签"
            textSize = 11f
            setTextColor(0xFF888888.toInt())
        })
        layout.addView(modeSpinner)

        val kindLabels = arrayOf("识别内容：数字", "识别内容：中文", "识别内容：英文", "识别内容：中英数字混合")
        val kindKeys = arrayOf("num", "cn", "en", "mix")
        val kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, kindLabels)
            setSelection(kindKeys.indexOf(p.kind).coerceAtLeast(0))
        }
        layout.addView(kindSpinner)

        layout.addView(TextView(this).apply {
            text = "本地模型目录（识别方式选「本地 Qwen」时才用）"
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
                val (rules, err) = ConditionParser.parseRulesText(input.text.toString())
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                p.rules = rules.toMutableList()
                p.pick = pickKeys[spinner.selectedItemPosition.coerceIn(0, 2)]
                p.mode = modeKeys[modeSpinner.selectedItemPosition.coerceIn(0, 3)]
                p.kind = kindKeys[kindSpinner.selectedItemPosition.coerceIn(0, 3)]
                localModelDir = dirInput.text.toString().trim().trimEnd('/')
                localReady = false
                getSharedPreferences("patchnotify", MODE_PRIVATE).edit()
                    .putString("modelDir", localModelDir).apply()
                p.monitor = true
                fired.clear()
                ConfigStore.save(this, patches)
                refreshPatchList()
                dialog.dismiss()
                val text = if (rules.isEmpty()) "规则已清空" else "已保存 ${rules.size} 条规则"
                setStatus(text)
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val (rules, _) = ConditionParser.parseRulesText(input.text.toString())
                val first = rules.firstOrNull()
                if (first == null) {
                    Toast.makeText(this, "还没有有效规则", Toast.LENGTH_SHORT).show()
                } else {
                    speak(first.speech.ifBlank { "测试播报" })
                    Toast.makeText(this, "试听：${first.speech}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun parseCond(expr: String): Condition? = ConditionParser.parseWhole(expr)

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

    private fun onFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
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
        if (targets.isEmpty()) { bitmap.recycle(); return }

        busy = true
        busyAt = SystemClock.elapsedRealtime()
        val t0 = SystemClock.elapsedRealtime()
        var processed = 0
        var idx = rrIndex % targets.size

        fun next() {
            if (processed >= targets.size ||
                SystemClock.elapsedRealtime() - t0 > OCR_BUDGET_MS) {
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
            if (crop == null) { next(); return }
            if (crop.width < 40 || crop.height < 16) {
                pnLog("crop 太小 ${crop.width}x${crop.height}")
                // 裁出来太小基本等于画面外，直接判失败，别把垃圾发给模型
                val cw = crop.width; val ch = crop.height
                crop.recycle()
                onRead(p, "框太小:${cw}x${ch}")
                next(); return
            }

            // 本地模式：C++ 直接跑 ONNX，不联网
            if (p.mode == "local") {
                if (!hasAllFilesAccess()) {
                    val api = android.os.Build.VERSION.SDK_INT
                    val mgr = if (api >= 30) android.os.Environment.isExternalStorageManager().toString() else "-"
                    pnLog("local: 没有文件访问权限 api=$api isExternalStorageManager=$mgr")
                    crop.recycle()
                    runOnUiThread {
                        onRead(p, "没有文件访问权限(API$api 管理所有文件=$mgr)")
                        askAllFilesAccess()
                    }
                    bitmap.recycle()
                    busy = false          // ★ 不释放的话整条帧循环会永久卡住
                    return
                }
                if (localBusy) {
                    if (SystemClock.elapsedRealtime() - lastBusyLogAt > 2000) {
                        lastBusyLogAt = SystemClock.elapsedRealtime()
                        pnLog("local: 上一帧还在推理，丢帧")
                    }
                    crop.recycle(); next(); return
                }
                localBusy = true
                val bmp = crop
                val kind = when (p.kind) { "cn" -> 1; "en" -> 2; "mix" -> 3; else -> 0 }
                val fs = "${bmp.width}x${bmp.height}"
                if (fs != lastFrameSize) { lastFrameSize = fs; pnLog("local: 整帧=${bitmap.width}x${bitmap.height} crop=$fs") }
                pnLog("local: 进入 patch=${p.field} crop=$fs kind=$kind")
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
                            // 自然语言规则：逐条让模型判断「是/否」
                            val nl = p.rules.filter { it.expr.startsWith("~") }
                            if (nl.isNotEmpty()) {
                                var anyYes = false
                                for (rule in nl) {
                                    val desc = rule.expr.substring(1)
                                    val out = NativeOcr.ocrPrompt(rgb, bw, bh, desc)
                                    val yes = out.trimStart().startsWith("是")
                                    nlHits[p.id + "|" + rule.expr] = yes
                                    if (yes) anyYes = true
                                    pnLog("NL 「$desc」 → $out → ${if (yes) "是" else "否"}")
                                }
                                r = if (anyYes) "是" else "否"
                                bmp.recycle(); localBusy = false
                                runOnUiThread { onRead(p, r) }
                                return@Thread
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
                                r = NativeOcr.ocr(rgb, bw, bh, kind)
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
                    runOnUiThread { onRead(p, r) }
                }.start()
                bitmap.recycle()
                busy = false              // ★ 推理在后台线程跑，这里不能占着 busy
                return
            }

            // 先试数码管（计数器/LED 面板）：只有"数字"类型才用
            if (p.mode != "text" && p.kind == "num") {
                val seg = try {
                    val px = IntArray(crop.width * crop.height)
                    crop.getPixels(px, 0, crop.width, 0, 0, crop.width, crop.height)
                    SegmentReader.decode(px, crop.width, crop.height)
                } catch (e: Exception) {
                    null
                }
                if (seg != null && seg.digits.none { it == '?' }) {
                    runOnUiThread { onRead(p, seg.digits, fromSeg = true) }
                    crop.recycle()
                    next()
                    return
                }
            }

            if (p.mode == "seg7") {
                // 只读数码管模式：读不出来就明确报空，不交给 ML Kit 瞎猜
                runOnUiThread { onRead(p, "") }
                crop.recycle()
                next()
                return
            }

            val ocrInput = prepareForOcr(crop)
            crop.recycle()

            recognizer.process(InputImage.fromBitmap(ocrInput, 0))
                .addOnSuccessListener { text ->
                    val raw = text.text.replace(Regex("\\s+"), " ").trim()
                    runOnUiThread { onRead(p, raw) }
                }
                .addOnFailureListener { onRead(p, "") }
                .addOnCompleteListener {
                    ocrInput.recycle()
                    next()
                }
        }
        next()
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

    /**
     * 小区域放大 + 深色背景（红色 LED 数码管）反相二值化。
     * ML Kit 对 7 段数码管识别率很低，预处理能显著提高命中率。
     */
    private fun prepareForOcr(src: Bitmap): Bitmap {
        var bmp = src

        // 1) 太小就放大（目标高度 >= 160px）
        if (bmp.height < 160) {
            val scale = (160f / bmp.height).toInt().coerceIn(2, 6)
            val big = Bitmap.createScaledBitmap(bmp, bmp.width * scale, bmp.height * scale, true)
            bmp.recycle()
            bmp = big
        }

        // 2) 采样判断是不是"深底亮字"（数码管）
        val w = bmp.width
        val h = bmp.height
        var sum = 0L
        var cnt = 0
        var i = 0
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        while (i < pixels.size) {
            val c = pixels[i]
            val v = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            sum += v
            cnt++
            i += 8
        }
        val mean = if (cnt == 0) 255 else (sum / cnt).toInt()

        if (mean >= 90) return bmp     // 普通亮底黑字，保持原样

        // 3) 深底 → 取最亮通道做阈值，反相成"白底黑字"
        for (k in pixels.indices) {
            val c = pixels[k]
            val v = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            pixels[k] = if (v > 120) Color.BLACK else Color.WHITE
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        return out
    }

    /** 用一张照片跑同样的预处理 + OCR，把识别文本原样显示出来 */
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
                    val s = maxSide / maxOf(bmp0.width, bmp0.height)
                    val img = if (s < 1f)
                        Bitmap.createScaledBitmap(bmp0, (bmp0.width * s).toInt(), (bmp0.height * s).toInt(), true)
                    else bmp0
                    // 同一张图分别跑：数码管读数 + ML Kit 文本
                    val px = IntArray(img.width * img.height)
                    img.getPixels(px, 0, img.width, 0, 0, img.width, img.height)
                    val seg = SegmentReader.decode(px, img.width, img.height)
                    val prepared = prepareForOcr(img)
                    val text = com.google.android.gms.tasks.Tasks.await(
                        recognizer.process(InputImage.fromBitmap(prepared, 0))
                    )
                    prepared.recycle()
                    (seg?.digits ?: "") + "\u0001" + text.text.replace(Regex("\\s+"), " ").trim()
                } catch (e: Exception) {
                    "ERR:" + e.message
                }
            }
            if (result.startsWith("ERR:")) { setStatus(result); return@launch }
            val parts = result.split("\u0001")
            val segDigits = parts.getOrNull(0) ?: ""
            val ocrText = parts.getOrNull(1) ?: ""
            val nums = Condition.numbersIn(if (segDigits.isNotEmpty()) segDigits else ocrText)
            val msg = "数码管读数：" + (if (segDigits.isEmpty()) "没读到" else segDigits) +
                    "\n调试：" + SegmentReader.lastDebug +
                    "\n\nML Kit 文本：\n" + (if (ocrText.isEmpty()) "（啥也没识别到）" else ocrText) +
                    "\n\n找到的数字：" + (if (nums.isEmpty()) "无" else nums.joinToString(", ")) +
                    "\n取最大：" + (nums.maxOrNull()?.toString() ?: "无")
            setStatus("照片识别完成")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("照片识别结果")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show()
        }
    }


    /** 系统文件夹选择器 → 转成真实路径（不依赖 /sdcard 这个别名） */

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

    private fun onRead(p: Patch, rawIn: String, fromSeg: Boolean = false) {
        // 数码管读数做稳定性过滤：最近 3 帧里至少 2 帧一致才采信，
        // 避免失焦/抖动时读出错误数字并触发误报。
        val raw: String
        val value: Double?
        if (fromSeg) {
            val stable = stableSegReading(p, rawIn)
            raw = stable ?: rawIn
            value = stable?.let { Condition.pickNumber(it, p.pick) }
        } else {
            segBuf.remove(p.id)
            raw = rawIn
            value = Condition.pickNumber(raw, p.pick)
        }
        reads[p.id] = PatchRead(p.id, raw, value, SystemClock.elapsedRealtime())
        val now = SystemClock.elapsedRealtime()

        // 每个 patch 独立日志：内容变化即记，静止时每 60s 一条心跳
        try {
            if (PatchLog.shouldLog(p.id, raw)) {
                PatchLog.log(p, raw, if (value != null) "value=$value" else "")
            }
        } catch (e: Exception) { pnLog("patchlog 写失败: ${e.message}") }

        var chosen: Rule? = null
        var anyHit = false

        // 每个 patch 的第一次有效读数只用来「设定基线」：
        // 启动时数字已经很大（比如 500，本来就 >191），不应该立刻响，只有之后递进越过阈值才响。
        val firstEval = seeded.add(p.id)

        // 逐条规则判断，取最后一条命中的（列表越靠后越严重）
        for (r in p.rules) {
            val cond = parseCond(r.expr) ?: continue
            val hit = if (r.expr.startsWith("~")) {
                nlHits[p.id + "|" + r.expr] == true
            } else if (cond.op == Op.CONTAINS || cond.op == Op.NOT_CONTAINS) cond.test(raw)
                      else cond.testNumber(value)
            if (hit) {
                anyHit = true
                if (firstEval) {
                    fired[key(p, r)] = Long.MAX_VALUE
                    pnLog("首读基线 $raw：${r.expr} 已满足，静默")
                } else {
                    chosen = r
                }
            } else {
                // 条件恢复 → 重新武装（延迟 8 秒，避免在阈值附近抖动时反复报）
                val k = key(p, r)
                val t = fired[k]
                if (t != null && t == Long.MAX_VALUE) fired[k] = now + REARM_MS
            }
        }

        if (anyHit) hits.add(p.id) else hits.remove(p.id)

        // 读不到数字时明确提示（否则用户会以为没触发是规则的问题）
        val hasNl = p.rules.any { it.expr.startsWith("~") }
        if (running && p.rules.isNotEmpty() && value == null && !hasNl) {
            val nowTick = SystemClock.elapsedRealtime()
            if (nowTick - lastHintAt > 5000) {
                lastHintAt = nowTick
                setStatus("⚠ ${p.field} 读不到数字（识别到：「${raw.take(12)}」）。" +
                        "请把框缩到数字上、靠近一点、避开反光。")
            }
        }

        if (SystemClock.elapsedRealtime() - lastUiAt > 400) {
            lastUiAt = SystemClock.elapsedRealtime()
            android.util.Log.d("PatchNotify", ResultJson.build(patches, reads, hits).toString())
            refreshPatchList()
        }

        if (chosen != null && running && !alerting) {
            val k = key(p, chosen)
            val t = fired[k]
            val canFire = (t == null) || (t != Long.MAX_VALUE && now >= t)
            if (canFire) {
                fired[k] = Long.MAX_VALUE      // 已报，等条件恢复后才重新武装
                triggerAlert(p, chosen)
            }
        }
    }

    private fun key(p: Patch, r: Rule) = p.id + "|" + r.expr

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
    private fun triggerAlert(p: Patch, r: Rule) {
        alerting = true
        val secs = r.seconds.coerceIn(1, 120)
        val text = r.speech.trim()
        val beepOnly = text.isEmpty()          // 没写播报内容 → 只嘟嘟嘟
        runOnUiThread {
            binding.btnStopAlert.visibility = View.VISIBLE
            setStatus("⚠ ${p.field} = ${reads[p.id]?.value ?: "?"}  规则 ${r.expr}  " +
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
        binding.btnStartStop.text = if (running) getString(R.string.btn_stop) else getString(R.string.btn_start)
        if (running) {
            fired.clear()
            seeded.clear()
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
        recognizer.close()
    }
}

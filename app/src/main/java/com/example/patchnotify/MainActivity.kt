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
    private val lastShown = HashMap<String, String>()    // patch -> 上次显示的结果
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

    /** 选一张照片跑一遍同样的判断，用来定位相机侧的问题 */
    private val imagePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) testWithImage(uri) }

    companion object {
        /** 每次发版改这里，屏幕上会显示，便于确认装的是哪个包 */
        const val BUILD_TAG = "v98-think"
        private const val REQ_PERMS = 1001
        private const val FRAME_INTERVAL_MS = 250L      // 4 fps
        private const val ROUND_BUDGET_MS = 200L
        private const val REARM_MS = 8000L              // 条件恢复后至少等这么久才允许再报
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        patches.addAll(ConfigStore.load(this))

        // 免配置安装包：首次启动把 assets 里的 .tenz 解到 sdcard（普通包会直接跳过）
        if (ModelBundle.bundled(this)) {
            Thread {
                ModelBundle.ensure(this) { msg -> runOnUiThread { setStatus(msg) } }
                // 自带模型释放完就把默认目录指过去（sdcard 上有用户的模型时不会被覆盖）
                val priv = ModelBundle.targetDir(this)
                if (priv.isDirectory && hasGguf(priv) && !hasGguf(java.io.File(localModelDir))) {
                    localModelDir = priv.absolutePath
                }
                runOnUiThread { refreshPatchList() }
            }.start()
        }

        localModelDir = getSharedPreferences("patchnotify", MODE_PRIVATE)
            .getString("modelDir", localModelDir) ?: localModelDir

        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"
        pnLog("=== 启动 $BUILD_TAG 权限=${hasAllFilesAccess()} ===")
        pnLog("device: model=${android.os.Build.MODEL} soc=$soc hw=${android.os.Build.HARDWARE} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        try { if (hasAllFilesAccess()) PatchShot.init(java.io.File(logDir()), BUILD_TAG, "${android.os.Build.MODEL} / ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"} / ${android.os.Build.VERSION.RELEASE}") } catch (e: Exception) { pnLog("截图目录初始化失败: ${e.message}") }
        initTts()
        setupUi()

        binding.buildText.text = "PatchNotify $BUILD_TAG · 点这里看日志"
        binding.buildText.setOnClickListener { showLog() }
        ensurePermissions()
        // 首次使用自动问一次权限（可选，不拦推理）。等相机权限弹窗落定再弹，避免两个对话框打架
        binding.root.postDelayed({
            if (!storageAskedThisRun && !hasAllFilesAccess()) {
                storageAskedThisRun = true
                askAllFilesAccess()
            }
        }, 2500)

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
            Toast.makeText(this, "选一张照片测试判断效果", Toast.LENGTH_SHORT).show()
            imagePicker.launch("image/*")
            true
        }
        binding.btnCondition.setOnClickListener { pickPatchForCondition() }
        binding.btnCondition.setOnLongClickListener {
            val permItem = if (hasAllFilesAccess()) "文件访问权限：已授予" else "授予文件访问权限（可选）"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(arrayOf("查看日志", permItem)) { _, i ->
                    when (i) {
                        0 -> showLog()
                        else -> if (!hasAllFilesAccess()) askAllFilesAccess()
                    }
                }
                .show()
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
        setStatus("先在画面上框一个区域，再点「设条件」")
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
                val shownRaw = r?.raw?.replace("\n", " ")?.take(18) ?: "—"
                val name = TextView(this).apply {
                    text = "${p.title()}  [$shownRaw]"
                    textSize = 12f
                    setTextColor(if (hits.contains(p.id)) 0xFFFF5252.toInt() else 0xFFDDDDDD.toInt())
                    setOnClickListener { editConditionDialog(p) }
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
        // 不再让用户起字段名：显示名直接用判断条件的前 10 个字
        val np = Patch(x = x, y = y, w = w, h = h, monitor = true, updatedAt = System.currentTimeMillis())
        patches.add(np)
        ConfigStore.save(this, patches)
        refreshPatchList()
        editConditionDialog(np)
    }



    private fun pickPatchForCondition() {
        if (patches.isEmpty()) {
            Toast.makeText(this, "先框选一个区域", Toast.LENGTH_SHORT).show(); return
        }
        val names = patches.map { it.title() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择区域")
            .setItems(names) { _, i -> editConditionDialog(patches[i]) }
            .show()
    }

    /** 条件编辑：一句话 + 是否允许思考 */
    private fun editConditionDialog(p: Patch) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        layout.addView(TextView(this).apply {
            text = "前半句是判断条件，模型只回答「是」或「否」。\n" +
                   "例：读数是否已接近001680\n" +
                   "句号后面可以跟一个问题（可选），条件成立时模型会回答它并语音播报：\n" +
                   "读数是否已接近001680。还需要充多久？\n" +
                   "没有句号 = 条件成立只响提示音"
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
            text = "允许模型先思考（先输出 think 块推理，再给是/否；每次多花约 10 秒）"
            textSize = 12f
            isChecked = p.think
        }
        layout.addView(thinkBox)

        // 内容比屏幕高，必须包一层 ScrollView，否则下半截（模型目录/导入按钮）够不到
        val scroll = android.widget.ScrollView(this).apply {
            addView(layout, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(p.title())
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
                if (Condition.conditionOf(ask).isEmpty()) {
                    Toast.makeText(this, "句号前没有条件", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                p.ask = ask
                p.think = thinkBox.isChecked
                p.updatedAt = System.currentTimeMillis()
                p.monitor = true
                nlState.remove(p.id)
                nlRearmAt.remove(p.id)
                sigByPatch.remove(p.id)
                resultByPatch.remove(p.id)
                resultAtByPatch.remove(p.id)
                ConfigStore.save(this, patches)
                refreshPatchList()
                dialog.dismiss()
                val text = "已保存：" + Condition.conditionOf(ask)
                setStatus(text)
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val q = Condition.questionOf(input.text.toString())
                Toast.makeText(this,
                    if (q.isBlank()) "条件成立时只响提示音" else "条件成立后会问模型：$q",
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

    // ----------------------------------------------------------- 判断循环

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

        PatchShot.setFrameSize(bitmap.width, bitmap.height)

        // 每天留一张整帧（未裁剪），方便日后确认这些裁剪图拍的是什么
        if (PatchShot.enabled && PatchShot.dailyDue()) {
            try {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (copy != null) Thread { PatchShot.saveDaily(copy) }.start()
            } catch (_: Throwable) { }
        }

        val targets = patches.filter { it.monitor }
        if (targets.isEmpty()) {
            pnLog("没有处于监控状态的区域（共 ${patches.size} 个），请点「设条件」保存一次")
            bitmap.recycle(); busy = false
            setStatus("没有在监控的区域：点「设条件」选中区域并保存")
            return
        }

        busy = true
        busyAt = SystemClock.elapsedRealtime()
        val t0 = SystemClock.elapsedRealtime()
        var processed = 0
        var idx = rrIndex % targets.size

        fun next() {
            try {

            if (processed >= targets.size ||
                SystemClock.elapsedRealtime() - t0 > ROUND_BUDGET_MS) {
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
            } catch (e: Throwable) {
                pnLog("cropPatch 抛异常: " + android.util.Log.getStackTraceString(e).take(600))
                null
            }
            if (crop == null) {
                pnLog("crop 失败 patch=${p.title()} rect=${p.x},${p.y},${p.w},${p.h} 整帧=${bitmap.width}x${bitmap.height}")
                next(); return
            }
            if (crop.width < 40 || crop.height < 16) {
                pnLog("crop 太小 ${crop.width}x${crop.height}")
                // 裁出来太小基本等于画面外，直接判失败，别把垃圾发给模型
                val cw = crop.width; val ch = crop.height
                crop.recycle()
                setStatus("${p.title()} 框太小:${cw}x${ch}，请重画")
                next(); return
            }

            // 唯一路径：本地 VLM 判断
            run {
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
                    pnLog("local: ${p.title()} 还没有判断条件，跳过并提示")
                    crop.recycle()
                    bitmap.recycle()
                    localBusy = false
                    busy = false
                    setStatus("「${p.title()}」还没写判断条件 —— 点「设条件」写一句话，例如：读数是否已接近001680")
                    return
                }
                val fs = "${bmp.width}x${bmp.height}"
                if (fs != lastFrameSize) { lastFrameSize = fs; pnLog("local: 整帧=${bitmap.width}x${bitmap.height} crop=$fs") }
                pnLog("local: 进入 patch=${p.title()} crop=$fs think=${p.think}")
                val found = resolveModelDir()
                if (found != localModelDir) { pnLog("local: 换用 $found"); localModelDir = found }
                Thread {
                    var r = "ERR"
                    try {
                        if (!localReady) {
                            pnLog("local: 开始加载模型…")
                            val st = Vlm.probe(localModelDir)
                            pnLog("local: probe → $st")
                            localReady = st == "ok" || st.startsWith("ok ")
                            if (!localReady) {
                                r = if (st.startsWith("ERR:")) st
                                    else "模型加载失败：$st"
                            }
                        }
                        if (localReady) {
                            // 诊断：每 5 秒存一张实际喂给模型的裁剪图
                            val nowDump = SystemClock.elapsedRealtime()
                            if (nowDump - lastCropDumpAt > 5000) {
                                lastCropDumpAt = nowDump
                                try {
                                    if (sdOk) java.io.File(logDir(), "last_crop.jpg").outputStream().use {
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
                                r = Vlm.ask(rgb, bw, bh, p.ask, p.think)
                                if (r.startsWith("ERR:THINK_TRUNC")) {
                                    pnLog("local: 思考被截断，改用不思考重跑一次")
                                    r = Vlm.ask(rgb, bw, bh, p.ask, false)
                                }
                                if (r.startsWith("ERR:TOOBIG:")) {
                                    val a = r.split(":")
                                    r = "框太大：${a.getOrNull(2)} 个图像token（上限 ${a.getOrNull(3)}），请把框划小一点"
                                }
                                sigByPatch[p.id] = sig ?: IntArray(0)
                                resultByPatch[p.id] = r
                                resultAtByPatch[p.id] = SystemClock.elapsedRealtime()
                                val dt = (SystemClock.elapsedRealtime() - t0) / 1000.0
                                pnLog("local: 判定 → 「$r」 用时 %.1fs".format(dt))
                                runOnUiThread {
                                    if (running) setStatus("${String.format("%.1f", dt)}s → ${r.take(20)}")
                                }
                            }

                            // 截图存档：平时 60 秒一张，条件成立的那一帧强制补一张
                            val hit = r.trim().startsWith("是") && nlState[p.id] != true
                            try { PatchShot.maybe(p, bmp, hit) } catch (_: Throwable) { }

                            // 条件成立且是「否→是」的跃变时，再把句号后的问题问一遍，答案拿去播报
                            var ansText = ""
                            val q = Condition.questionOf(p.ask)
                            if (q.isNotBlank() && r.trim() == "是" && nlState[p.id] != true) {
                                val t1 = SystemClock.elapsedRealtime()
                                var ans = try {
                                    // openAnswer：这句是问答，不是是/否判断
                                    // 传整句 ask：questionPrompt 会把「。」前的条件一起带进提示词
                                    Vlm.ask(rgb, bw, bh, p.ask, p.think, openAnswer = true).trim()
                                } catch (e: Throwable) { "ERR:" + e.message }
                                pnLog("local: 追问「$q」→ 「$ans」 用时 ${(SystemClock.elapsedRealtime() - t1) / 1000.0}s")
                                // 思考模式被截断 / 返回空 → 换成不思考再问一次，别让答案变成空
                                if (ans.isBlank() || ans.startsWith("ERR")) {
                                    val t2 = SystemClock.elapsedRealtime()
                                    val retry = try {
                                        Vlm.ask(rgb, bw, bh, p.ask, false, openAnswer = true).trim()
                                    } catch (e: Throwable) { "ERR:" + e.message }
                                    pnLog("local: 追问重试(不思考) 「${retry}」 用时 ${(SystemClock.elapsedRealtime() - t2) / 1000.0}s")
                                    if (retry.isNotBlank() && !retry.startsWith("ERR")) ans = retry
                                }
                                pnLog("local: 问答原始输出 prompt=「${Condition.questionPrompt(p.ask)}」 raw=「${ans.replace("\n", " ")}」")
                                if (ans.isNotBlank() && !ans.startsWith("ERR")) {
                                    ansText = ans
                                    r = "是|" + ans
                                }
                            }
                            // 命中记一行：时间 / 判定 / 问题 / 回答
                            if (hit) try { PatchShot.event(p, "是", q, ansText) } catch (_: Throwable) { }
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
            } catch (t: Throwable) {
                pnLog("next 异常: " + android.util.Log.getStackTraceString(t).take(800))
                busy = false
                localBusy = false
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

    private fun testWithImage(uri: android.net.Uri) {
        setStatus("正在判断这张照片……")
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
                        val st = Vlm.probe(resolveModelDir().also { localModelDir = it })
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
                    // 只是让用户看看模型能读到什么，别预设成「读数字」，
                    // 也不用封闭问句（那会把提示词改成「只回答是或否」）
                    "OK:" + w + "x" + h + "\u0001" + Vlm.ask(
                        rgb, w, h, "描述这张图，有文字或数字就原样读出来", false, openAnswer = true
                    )
                } catch (e: Exception) {
                    "ERR:" + e.message
                }
            }
            if (result.startsWith("ERR:")) { setStatus(result); return@launch }
            val parts = result.removePrefix("OK:").split("\u0001")
            setStatus("照片判断完成")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("照片判断结果")
                .setMessage("尺寸：" + parts.getOrNull(0) + "\n\n模型回答：\n" + (parts.getOrNull(1) ?: ""))
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /** 目录里有非 mmproj 的 .gguf 才算模型目录 */
    private fun hasGguf(dir: java.io.File): Boolean =
        dir.isDirectory && dir.listFiles()?.any {
            it.isFile && it.name.endsWith(".gguf", true) && !it.name.startsWith("mmproj", true)
        } == true

    /** 在常见位置里找模型目录，避免用户放对了却因为路径不同读不到 */
    private fun resolveModelDir(): String {
        val priv = java.io.File(filesDir, "models")
        if (!hasAllFilesAccess()) {
            if (hasGguf(priv)) return priv.absolutePath
            return localModelDir
        }
        val root = android.os.Environment.getExternalStorageDirectory()
        val cands = listOf(
            java.io.File(localModelDir),
            java.io.File(filesDir, "models/qwen_2b"),
            java.io.File(root, "patchnotify/qwen_2b"),
            java.io.File(root, "Download/patchnotify/qwen_2b"),
            java.io.File(root, "Download/qwen_2b"),
            java.io.File(root, "qwen_2b")
        )
        for (c in cands) if (hasGguf(c)) return c.absolutePath
        for (c in cands) c.listFiles()?.filter { it.isDirectory }?.forEach {
            if (hasGguf(it)) return it.absolutePath
        }
        // 最后才是安装包自带、释放到私有目录的那份
        if (hasGguf(priv)) return priv.absolutePath
        return localModelDir
    }


    /** 直接在屏幕上显示日志尾部，避免来回复制文件拿到旧内容 */
    private fun showLog() {
        val sb = StringBuilder()
        for (src in listOf(java.io.File(filesDir, "app.log"),
                           java.io.File(logDir(), "app.log"))) {
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

    /** 诊断日志：写 logcat + patchnotify/app.log（模型目录的上一层） */
    /**
     * 日志和截图固定放 sdcard/patchnotify，跟模型放哪无关
     * （自带模型在私有目录，不能再把 2 GB 截图写进 /data）
     */
    private fun logDir(): String {
        if (!sdOk) return filesDir.absolutePath
        val sd = java.io.File(android.os.Environment.getExternalStorageDirectory(), "patchnotify")
        return if (sd.isDirectory || sd.mkdirs()) sd.absolutePath else filesDir.absolutePath
    }
    private fun pnLog(msg: String) {
        android.util.Log.i("PatchNotify", msg)
        try {
            val f = java.io.File(logDir(), "app.log")
            f.parentFile?.mkdirs()
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$ts $msg\n")
        } catch (e: Throwable) { }
        try {
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastBarAt > 2000) {
                lastBarAt = nowMs
                runOnUiThread { binding.buildText.text = "PatchNotify $BUILD_TAG · " + msg.take(40) }
            }
        } catch (e: Throwable) { }
        try {
            val g = java.io.File(filesDir, "app.log")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            g.appendText("$ts $msg\n")
        } catch (e: Throwable) { }
    }

    /** 本进程已经确认能写 /sdcard 一次之后就不再重复探测 */
    private var lastBarAt = 0L
    @Volatile private var sdOk = false          // 能读写 /sdcard/patchnotify
    private var storageOk = false

    private fun hasAllFilesAccess(): Boolean {
        if (storageOk) { sdOk = true; return true }
        if (android.os.Build.VERSION.SDK_INT >= 30 &&
            android.os.Environment.isExternalStorageManager()) {
            storageOk = true
            sdOk = true
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
    private var storageAskedThisRun = false
    private fun askAllFilesAccess() {
        if (askingAccess) return
        askingAccess = true
        val msg = "授予「所有文件访问权限」后可以：\n" +
            "· 用你放在 /sdcard/patchnotify 里的自定义模型\n" +
            "· 把截图和日志写到 sdcard（方便导出查看）\n\n" +
            "不授予也能正常推理和判断——会使用应用私有目录里的内置模型，" +
            "只是不写截图。\n\n" +
            "下一步会打开系统设置，请找到「PatchNotify」并打开「允许访问所有文件管理」。"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("文件访问权限（可选）")
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
            .setNegativeButton("稍后再说") { _, _ -> askingAccess = false }
            .show()
    }

    /**
     * 一句话条件的判定结果。
     * 只在「否 → 是」的跃变时响一次；条件恢复后才重新武装，避免条件持续满足时一直响。
     */
    private fun onVerdict(p: Patch, rawIn: String) {
        val parts = rawIn.split("|", limit = 2)
        val raw = parts[0]
        val answer = parts.getOrNull(1)?.trim().orEmpty()
        val yes = Condition.parse(raw)
        val shown = when (yes) {
            true -> if (answer.isEmpty()) "是" else "是 · $answer"
            false -> "否"
            null -> rawIn.take(16).ifBlank { "?" }
        }
        reads[p.id] = PatchRead(p.id, shown, SystemClock.elapsedRealtime())

        val prev = nlState[p.id] ?: false
        nlState[p.id] = (yes == true)

        val now = SystemClock.elapsedRealtime()
        if (yes == false) {                      // 条件恢复 → 重新武装
            if (prev) nlRearmAt[p.id] = now + REARM_MS
        }

        // 只在判定真的变了才刷界面：每帧重建列表/改状态栏都会挤压布局，拖着预览一起抖
        if (lastShown[p.id] != shown) {
            lastShown[p.id] = shown
            runOnUiThread {
                refreshPatchList()
                setStatus("${p.title()}：${shown.take(24)}")
            }
        }

        pnLog("judge: raw=${raw.replace("\n", " ").take(20)} yes=$yes prev=$prev running=$running alerting=$alerting " +
              "rearm=${(nlRearmAt[p.id] ?: 0L) - now}ms prompt=${Condition.prompt(p.ask).take(60)}")
        if (yes == true && !prev && running && !alerting) {
            val canFire = now >= (nlRearmAt[p.id] ?: 0L)
            if (canFire) {
                nlRearmAt[p.id] = now + REARM_MS
                val text = if (answer.isNotBlank()) answer else ""
                pnLog("条件成立：${p.title()} → 播报「$text」")
                triggerAlert(p, text, if (text.isBlank()) 5 else 8, p.title())
            }
        }

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

    private fun triggerAlert(p: Patch, sayIn: String, seconds: Int, label: String) {
        alerting = true
        val secs = seconds.coerceIn(1, 120)
        val text = sayIn.trim()
        val beepOnly = text.isEmpty()          // 没有问题可答 → 只嘟嘟嘟
        runOnUiThread {
            binding.btnStopAlert.visibility = View.VISIBLE
            setStatus("⚠ ${p.title()}：${reads[p.id]?.raw ?: "?"}  $label  " +
                    (if (beepOnly) "提示音 ${secs} 秒" else "播报 ${secs} 秒"))
        }
        alertJob?.cancel()
        alertJob = lifecycleScope.launch {
            val end = SystemClock.elapsedRealtime() + secs * 1000L
            if (!beepOnly) {
                speak(text)                     // 答案只说一遍，重复三遍是以前的循环遗留
                while (isActive && alerting && SystemClock.elapsedRealtime() < end) {
                    delay(200)
                }
            } else {
                while (isActive && alerting) {
                    beepOnce(160)
                    delay(200)
                    beepOnce(160)
                    val left = end - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    delay(minOf(1000L, left))
                }
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
        resetDetection()
        if (running) {
            if (!storageAskedThisRun && !hasAllFilesAccess()) {
                storageAskedThisRun = true
                askAllFilesAccess()
            }
            setStatus("监控中 · 每 0.25 秒取一帧，逐个区域判断")
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

    /** 清掉按 patch 缓存的判定/指纹，保证下一次判定重新从「未命中」开始 */
    private fun resetDetection() {
        nlState.clear()
        nlRearmAt.clear()
        lastShown.clear()
        sigByPatch.clear()
        resultByPatch.clear()
        resultAtByPatch.clear()
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

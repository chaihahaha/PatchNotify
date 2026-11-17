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
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.patchnotify.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 条件编辑对话框里的一行（条件/问题/思考/脚本） */
    private data class RuleEditor(
        val card: LinearLayout,
        val title: TextView,
        val condition: EditText,
        val question: EditText,
        val think: CheckBox,
        val pyToggle: CheckBox,
        val pyPath: EditText
    )

    /** 轮询单位：一个 patch 的一条规则 */
    private data class MonitorTarget(val patch: Patch, val rule: TriggerRule)

    /** 一条规则已经命中，但当前播报还没结束：排队，等当前播报结束后再念 */
    private data class PendingAlert(
        val patch: Patch, val ruleId: String, val text: String, val secs: Int, val label: String
    )

    // UI 线程会增删 patch，相机线程会遍历它：普通 MutableList 会偶发
    // ConcurrentModificationException；CopyOnWriteArrayList 让读侧永远拿到稳定快照。
    private val patches = CopyOnWriteArrayList<Patch>()
    private val reads = mutableMapOf<String, PatchRead>()
    private val hits = mutableSetOf<String>()

    private val cameraExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "camera-analysis").apply { isDaemon = true }
    }
    // 推理串行执行：不再每帧新建 Thread，也避免 watch dog 复位后真的并发跑两份推理
    private val inferExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vlm-infer").apply { isDaemon = true }
    }
    private var analysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var alertJob: Job? = null
    private var alerting = false
    private val pendingAlerts = java.util.ArrayDeque<PendingAlert>()

    private val tone: ToneGenerator by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 95) }

    @Volatile private var running = false
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private val afHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastFrameAt = 0L
    private var rrIndex = 0
    private val lastShown = HashMap<String, String>()    // patch -> 上次显示的结果
    // 下面这些 map 同时被相机/推理线程和 UI 线程访问，必须用并发容器
    private val ruleState = ConcurrentHashMap<String, Boolean>()   // patch -> 上次是否命中
    private val ruleRearmAt = ConcurrentHashMap<String, Long>()    // patch -> 允许再次触发的时间
    private var busy = false
    private var busyAt = 0L
    private var lastBusyLogAt = 0L
    private var lastCropDumpAt = 0L
    private var lastNoTargetLogAt = 0L
    private var lastFrameSize = ""
    private var lastImgW = 0
    private var lastImgH = 0
    // 帧间缓存按 patch 分开存：多个 patch 共用一份会把 A 的读数当成 B 的
    private val sigByRule = ConcurrentHashMap<String, IntArray>()
    private val resultByRule = ConcurrentHashMap<String, String>()
    private val resultAtByRule = ConcurrentHashMap<String, Long>()
    @Volatile private var localBusy = false
    private var localBusyAt = 0L
    @Volatile private var localReady = false
    @Volatile private var localModelDir = File(
        android.os.Environment.getExternalStorageDirectory(), "patchnotify/qwen_2b"
    ).absolutePath

    // 补光灯状态（CameraX 手电筒）
    private var hasFlash = false
    private var torchOn = false

    // 条件编辑框里的 Python 路径输入框；文件选择器返回后要写回它
    private var pyPathInput: EditText? = null

    /** 选一张照片跑一遍同样的判断，用来定位相机侧的问题 */
    private val imagePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) testWithImage(uri) }

    /** 选 Python 脚本：能转成真实路径就直接用，不能就复制到应用私有目录 */
    private val pyPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPythonPicked(uri) }

    // 导出/导入方框 JSON
    private var pendingExportPatch: Patch? = null
    private val exportPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) writePatchExport(uri) else pendingExportPatch = null
    }
    private val importPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importPatchJson(uri) }

    companion object {
        /** 每次发版改这里，屏幕上会显示，便于确认装的是哪个包 */
        const val BUILD_TAG = "v108-editable-boxes"
        private const val REQ_PERMS = 1001
        private const val REQ_STORAGE = 1002
        private const val FRAME_INTERVAL_MS = 250L      // 4 fps
        private const val ROUND_BUDGET_MS = 200L
        private const val REARM_MS = 8000L              // 条件恢复后至少等这么久才允许再报
        private const val RING_MS = 180                 // 播报前的单次方波长度
        private const val MAX_LOG_BYTES = 2L * 1024 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 常亮：监控靠相机持续取帧，屏幕一睡相机就停，等于监控中断。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        patches.addAll(ConfigStore.load(this))
        // 先读用户目录再做自带模型释放，避免后台线程和这里的赋值互相覆盖
        localModelDir = getSharedPreferences("patchnotify", MODE_PRIVATE)
            .getString("modelDir", localModelDir) ?: localModelDir

        // 免配置安装包：首次启动把 assets 里的模型释放到应用私有目录（普通包会直接跳过）
        if (ModelBundle.bundled(this)) {
            Thread {
                ModelBundle.ensure(this) { msg -> runOnUiThread { setStatus(msg) } }
                // 自带模型释放完就把默认目录指过去（sdcard 上有用户的模型时不会被覆盖）
                val priv = ModelBundle.targetDir(this)
                if (priv.isDirectory && hasVlmPair(priv) && !hasVlmPair(File(localModelDir))) {
                    localModelDir = priv.absolutePath
                }
                runOnUiThread { refreshPatchList() }
            }.start()
        }

        // Python 只在真的配了脚本时才预热，避免不用这个功能的人白付启动时间
        if (patches.any { p -> p.rules.any { it.pyPath.isNotBlank() } }) PythonRunner.warmUp(applicationContext)

        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"
        val filesOk = hasAllFilesAccess()
        pnLog("=== 启动 $BUILD_TAG 权限=$filesOk ===")
        pnLog("device: model=${android.os.Build.MODEL} soc=$soc hw=${android.os.Build.HARDWARE} abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        if (filesOk) initPatchShot()
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
                testWithImage(Uri.fromFile(File(path)))
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
        binding.btnTorch.isEnabled = false          // 相机起来并确认有闪光灯后才可用
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnImport.setOnClickListener {
            importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.btnExport.setOnClickListener { startPatchExport() }
        binding.overlayView.onPatchGeometryChanged = { p -> onPatchGeometryChanged(p) }

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
            val sel = binding.overlayView.selectedPatch
            if (sel != null && patches.none { it.id == sel.id }) {
                binding.overlayView.clearSelection()
            }
            binding.patchList.removeAllViews()
            if (patches.isEmpty()) {
                val tv = TextView(this)
                tv.text = "还没有监控区域。点「框选区域」，在画面上拖一个框。"
                tv.setTextColor(Color.BLACK)
                tv.textSize = 15f
                binding.patchList.addView(tv)
            }
            for (p in patches) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 6, 0, 6)
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
                    text = "${p.title()}" +
                        (if (p.rules.size > 1) " ·${p.rules.size}条" else "") +
                        (if (p.hasPython()) " [py]" else "") + "  [$shownRaw]"
                    textSize = 15f
                    setTextColor(Color.BLACK)
                    if (hits.contains(p.id)) {
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        text = "● " + text
                    }
                    setOnClickListener { editConditionDialog(p) }
                    setOnLongClickListener {
                        val sel = binding.overlayView.selectedPatch
                        if (sel?.id == p.id) {
                            binding.overlayView.clearSelection()
                            setStatus("已退出方框编辑")
                        } else {
                            binding.overlayView.selectPatch(p)
                            setStatus("已选中「${p.title()}」：拖框内移动，拖四角缩放；点框外退出")
                        }
                        true
                    }
                }
                row.addView(cb)
                row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val del = factoryButton("删除", size = 13f).apply {
                    setOnClickListener {
                        patches.remove(p)
                        reads.remove(p.id)
                        p.rules.forEach { r ->
                            ruleState.remove(r.id)
                            ruleRearmAt.remove(r.id)
                            sigByRule.remove(r.id)
                            resultByRule.remove(r.id)
                            resultAtByRule.remove(r.id)
                        }
                        if (binding.overlayView.selectedPatch?.id == p.id) {
                            binding.overlayView.clearSelection()
                        }
                        hits.remove(p.id)
                        lastShown.remove(p.id)
                        ConfigStore.save(this@MainActivity, patches)
                        binding.overlayView.patches = patches
                        refreshPatchList()
                    }
                }
                row.addView(del)
                binding.patchList.addView(row)
            }
            binding.overlayView.patches = patches
            syncOverlay()
        }
    }

    /** 把当前读数/命中状态同步给叠加层（传副本，避免绘制线程看到正在改的 map） */
    private fun syncOverlay() {
        binding.overlayView.reads = HashMap(reads)
        binding.overlayView.hits = HashSet(hits)
    }

    /** 工厂风格按钮：白底黑框黑字，按下不变色；primary = 黑底白字 */
    private fun factoryButton(text: String, primary: Boolean = false, size: Float = 14f): Button =
        Button(this).apply {
            this.text = text
            textSize = size
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else Color.BLACK)
            setBackgroundResource(if (primary) R.drawable.btn_factory_primary else R.drawable.btn_factory)
            backgroundTintList = null
            minWidth = 0
            minimumWidth = 0
            minHeight = (44 * resources.displayMetrics.density).toInt()
            setPadding((8 * resources.displayMetrics.density).toInt(), 0,
                (8 * resources.displayMetrics.density).toInt(), 0)
        }

    private fun startRectEdit() {
        if (!binding.overlayView.hasImage()) {
            Toast.makeText(this, "相机画面还没就绪，稍等一下再框选", Toast.LENGTH_SHORT).show()
            return
        }
        binding.overlayView.clearSelection()
        binding.overlayView.editMode = true
        setStatus("框选模式：在画面上拖动出一个矩形")
        Toast.makeText(this, "在画面上拖出一个矩形", Toast.LENGTH_SHORT).show()
    }

    /** 方框拖动/缩放结束：保存几何、清掉按旧裁剪图算出的缓存 */
    private fun onPatchGeometryChanged(p: Patch) {
        if (patches.none { it.id == p.id }) return
        p.updatedAt = System.currentTimeMillis()
        p.rules.forEach { removeRuleState(it.id) }
        hits.remove(p.id)
        lastShown.remove(p.id)
        reads.remove(p.id)
        ConfigStore.save(this, patches)
        syncOverlay()
        refreshPatchList()
        setStatus("已调整「${p.title()}」的方框；条件仍可点「设条件」修改")
        pnLog("方框调整：${p.title()} rect=${p.x},${p.y},${p.w},${p.h}")
    }

    private fun askFieldName(x: Float, y: Float, w: Float, h: Float) {
        // 不再让用户起字段名：显示名直接用判断条件的前 10 个字
        val np = Patch(x = x, y = y, w = w, h = h, monitor = true, updatedAt = System.currentTimeMillis())
        patches.add(np)
        ConfigStore.save(this, patches)
        refreshPatchList()
        binding.overlayView.selectPatch(np)
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

    /** 条件编辑：多行触发条件；左列条件、右列命中后的问题，任意一行满足都执行该行动作 */
    private fun editConditionDialog(p: Patch) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val half = pad / 2
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, half, pad, 0)
        }
        val helpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        helpRow.addView(TextView(this).apply {
            text = "任意条件成立即执行对应动作"
            textSize = 13f
            setTextColor(0xFF222222.toInt())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        helpRow.addView(factoryButton("?", size = 16f).apply {
            contentDescription = "说明"
            setOnClickListener { showConditionHelp() }
        }, LinearLayout.LayoutParams(
            (48 * resources.displayMetrics.density).toInt(),
            (48 * resources.displayMetrics.density).toInt()))
        layout.addView(helpRow)

        val ruleContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(ruleContainer)
        val editors = mutableListOf<RuleEditor>()

        fun updateTitles() {
            editors.forEachIndexed { i, e -> e.title.text = "条件 ${i + 1}" }
        }

        fun addRuleEditor(rule: TriggerRule) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, half, 0, half)
                background = android.graphics.drawable.ColorDrawable(Color.WHITE)
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                textSize = 16f
                setTextColor(Color.BLACK)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
            header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = factoryButton("删除", size = 13f)
            header.addView(del)
            card.addView(header)

            // 两个独立列：用户不用再记「用句号分隔」这种规则
            val fields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val condCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            condCol.addView(TextView(this).apply {
                text = "条件"
                textSize = 13f
                setTextColor(Color.BLACK)
            })
            val condInput = EditText(this).apply {
                setText(rule.condition)
                hint = "例：读数是否大于等于 001680"
                textSize = 16f
                setTextColor(Color.BLACK)
                setHintTextColor(0xFF555555.toInt())
                gravity = Gravity.TOP
                setBackgroundResource(R.drawable.input_factory)
                backgroundTintList = null
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding(12, 10, 12, 10)
                minLines = 3
                maxLines = 8
                isVerticalScrollBarEnabled = true
            }
            condCol.addView(condInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val qCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            qCol.addView(TextView(this).apply {
                text = "问题"
                textSize = 13f
                setTextColor(Color.BLACK)
            })
            val qInput = EditText(this).apply {
                setText(rule.question)
                hint = "例：还需要充多久？"
                textSize = 16f
                setTextColor(Color.BLACK)
                setHintTextColor(0xFF555555.toInt())
                gravity = Gravity.TOP
                setBackgroundResource(R.drawable.input_factory)
                backgroundTintList = null
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding(12, 10, 12, 10)
                minLines = 2
                maxLines = 8
                isVerticalScrollBarEnabled = true
            }
            qCol.addView(qInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            fields.addView(condCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f))
            fields.addView(qCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = half
            })
            card.addView(fields)

            val thinkBox = CheckBox(this).apply {
                text = "思考（慢）"
                textSize = 14f
                setTextColor(Color.BLACK)
                isChecked = rule.think
            }
            val pyToggle = CheckBox(this).apply {
                text = "Python 动作"
                textSize = 14f
                setTextColor(Color.BLACK)
                isChecked = rule.pyPath.isNotBlank()
            }
            val optionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            optionsRow.addView(thinkBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            optionsRow.addView(pyToggle, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = half })
            card.addView(optionsRow)

            val pyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = if (pyToggle.isChecked) View.VISIBLE else View.GONE
            }
            pyRow.addView(TextView(this).apply {
                text = "脚本"
                textSize = 14f
                setTextColor(Color.BLACK)
            })
            val pathInput = EditText(this).apply {
                setText(rule.pyPath)
                hint = File(android.os.Environment.getExternalStorageDirectory(), "patchnotify/on_hit.py").absolutePath
                textSize = 14f
                setTextColor(Color.BLACK)
                setHintTextColor(0xFF555555.toInt())
                maxLines = 2
            }
            pyPathInput = pathInput
            pyRow.addView(pathInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val pickBtn = factoryButton("选择", size = 13f).apply {
                setOnClickListener {
                    pyPathInput = pathInput
                    try {
                        pyPicker.launch(arrayOf("*/*"))
                    } catch (t: Throwable) {
                        Toast.makeText(this@MainActivity, "打不开文件选择器：${t.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            pyRow.addView(pickBtn)
            card.addView(pyRow)
            card.addView(View(this).apply { setBackgroundColor(0xFF999999.toInt()) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

            pyToggle.setOnCheckedChangeListener { _, checked ->
                pyRow.visibility = if (checked) View.VISIBLE else View.GONE
            }

            val editor = RuleEditor(card, title, condInput, qInput, thinkBox, pyToggle, pathInput)
            del.setOnClickListener {
                ruleContainer.removeView(card)
                editors.remove(editor)
                updateTitles()
            }
            ruleContainer.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = half
            })
            editors.add(editor)
            updateTitles()
        }

        val initial = p.rules.ifEmpty { mutableListOf(TriggerRule()) }
        initial.forEach { addRuleEditor(it.copy()) }

        val scroll = android.widget.ScrollView(this).apply {
            addView(layout, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        val addBtn = factoryButton("＋ 添加条件", size = 16f).apply {
            setOnClickListener {
                addRuleEditor(TriggerRule())
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        layout.addView(addBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = half
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑条件")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNeutralButton("试听", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnDismissListener { pyPathInput = null }
        dialog.setOnShowListener {
            // 两列在手机上容易挤，尽量接近屏幕宽度，但不铺满平板
            val density = resources.displayMetrics.density
            val width = minOf((resources.displayMetrics.widthPixels * 0.96f).toInt(),
                (720 * density).toInt())
            dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            for (id in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)) {
                dialog.getButton(id).apply {
                    setTextColor(Color.BLACK)
                    textSize = 16f
                    isAllCaps = false
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var error: String? = null
                val out = mutableListOf<TriggerRule>()
                editors.forEachIndexed { i, e ->
                    val condition = e.condition.text.toString().replace("\n", " ").trim()
                    val question = e.question.text.toString().replace("\n", " ").trim()
                    val py = if (e.pyToggle.isChecked) e.pyPath.text.toString().trim() else ""
                    if (condition.isEmpty() && question.isEmpty() && py.isEmpty()) return@forEachIndexed
                    if (condition.isEmpty()) {
                        if (error == null) error = "第 ${i + 1} 行：触发条件不能为空"
                        return@forEachIndexed
                    }
                    // 保存时换新 id：旧 id 的推理结果即使迟到也不会被当成新配置
                    out.add(TriggerRule(condition = condition, question = question,
                        think = e.think.isChecked, pyPath = py))
                }
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (out.isEmpty()) {
                    Toast.makeText(this, "至少填写一条触发条件", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (out.any { it.pyPath.isNotBlank() && !(File(it.pyPath).isFile && File(it.pyPath).canRead()) }) {
                    Toast.makeText(this, "有 Python 脚本路径当前不可读，命中时会记录错误", Toast.LENGTH_LONG).show()
                }
                // 规则 id 变化后旧缓存不能复用；全部清掉最安全
                p.rules.forEach { removeRuleState(it.id) }
                p.rules = out.toMutableList()
                p.updatedAt = System.currentTimeMillis()
                p.monitor = true
                reads.remove(p.id)
                hits.remove(p.id)
                lastShown.remove(p.id)
                ConfigStore.save(this, patches)
                if (out.any { it.pyPath.isNotBlank() }) PythonRunner.warmUp(applicationContext)
                refreshPatchList()
                dialog.dismiss()
                val text = "已保存 ${out.size} 条触发条件：" + out.first().condition.take(12)
                setStatus(text)
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val q = editors.firstOrNull()?.question?.text?.toString()?.trim().orEmpty()
                if (q.isBlank()) {
                    Toast.makeText(this, "这一行没有播报问题，命中只响提示音", Toast.LENGTH_SHORT).show()
                } else {
                    speak(q)
                    Toast.makeText(this, "试听：$q", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun showConditionHelp() {
        val tv = TextView(this).apply {
            text = "每个区域可以有多条条件，任意一条成立就执行对应动作。\n\n" +
                   "条件：模型只回答「是 / 否」。\n" +
                   "例：读数是否大于等于 001680\n\n" +
                   "问题：条件成立后要模型回答并语音播报的内容；留空只响提示音。\n" +
                   "例：还需要充多久？\n\n" +
                   "思考：让模型先推理再回答，慢约 10 秒，开放式条件才需要。\n\n" +
                   "Python 动作：勾选后才显示脚本路径；这一行命中时执行脚本。\n" +
                   "脚本变量：PATCH_TITLE / PATCH_CONDITION / PATCH_QUESTION / PATCH_ANSWER / " +
                   "PATCH_ID / APP_FILES_DIR / SCRIPT_PATH。"
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(48, 24, 48, 8)
            setTextIsSelectable(true)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("说明")
            .setView(sv)
            .setPositiveButton("知道了", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(Color.BLACK)
                textSize = 16f
                isAllCaps = false
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            minOf((resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (620 * resources.displayMetrics.density).toInt()),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // ---------------------------------------------------------- 方框 JSON

    private fun startPatchExport() {
        val p = binding.overlayView.selectedPatch
            ?: patches.singleOrNull()
        if (p == null) {
            Toast.makeText(this, "先长按下方条件标题选中方框", Toast.LENGTH_SHORT).show()
            return
        }
        if (patches.none { it.id == p.id }) {
            Toast.makeText(this, "这个方框已被删除", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExportPatch = p
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = p.title().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(24).ifBlank { "patch" }
        exportPicker.launch("PatchNotify_${safe}_$stamp.json")
    }

    private fun patchExportJson(p: Patch): String {
        val root = JSONObject().apply {
            put("format", "patchnotify.patch")
            put("version", 1)
            put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            put("app", BUILD_TAG)
        }
        val patch = JSONObject().apply {
            put("title", p.title())
            put("monitor", p.monitor)
            put("rect_normalized", JSONObject().apply {
                put("x", p.x.toDouble()); put("y", p.y.toDouble())
                put("w", p.w.toDouble()); put("h", p.h.toDouble())
            })
            put("rules", JSONArray().apply {
                p.rules.filter { it.condition.isNotBlank() }.forEach { r ->
                    put(JSONObject().apply {
                        put("condition", r.condition)
                        put("question", r.question)
                        put("think", r.think)
                        put("py_path", r.pyPath)
                    })
                }
            })
        }
        root.put("patch", patch)
        return root.toString(2)
    }

    private fun writePatchExport(uri: Uri) {
        val p = pendingExportPatch
        pendingExportPatch = null
        if (p == null) return
        val json = try {
            patchExportJson(p)
        } catch (t: Throwable) {
            Toast.makeText(this, "生成 JSON 失败：${t.message}", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                        ?: return@withContext "无法打开目标文件"
                    null
                } catch (t: Throwable) {
                    t.message ?: t.javaClass.simpleName
                }
            }
            if (error == null) {
                setStatus("已导出「${p.title()}」的 ${p.rules.count { it.condition.isNotBlank() }} 条条件")
                Toast.makeText(this@MainActivity, "已导出 JSON", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "导出失败：$error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importPatchJson(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取文件")
                    parseImportedPatches(JSONObject(text))
                }
            }
            result.onSuccess { list ->
                patches.addAll(list)
                ConfigStore.save(this@MainActivity, patches)
                refreshPatchList()
                binding.overlayView.selectPatch(list.last())
                if (list.any { p -> p.rules.any { it.pyPath.isNotBlank() } }) {
                    PythonRunner.warmUp(applicationContext)
                }
                val n = list.sumOf { it.rules.count { r -> r.condition.isNotBlank() } }
                val msg = "已导入 ${list.size} 个方框、$n 条条件"
                setStatus("$msg；点「设条件」可修改")
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }.onFailure { t ->
                Toast.makeText(this@MainActivity, "导入失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseImportedPatches(root: JSONObject): List<Patch> {
        val arr = root.optJSONArray("patches")
        if (arr != null) {
            val out = mutableListOf<Patch>()
            var firstError: Throwable? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                try { out.add(parseImportedPatch(o)) } catch (t: Throwable) {
                    if (firstError == null) firstError = t
                }
            }
            if (out.isEmpty()) {
                throw (firstError ?: IllegalStateException("没有可导入的方框"))
            }
            return out
        }
        val single = root.optJSONObject("patch") ?: root
        return listOf(parseImportedPatch(single))
    }

    private fun parseImportedPatch(o: JSONObject): Patch {
        val rect = o.optJSONObject("rect_normalized") ?: o.optJSONObject("rect") ?: o
        val x0 = rect.optDouble("x", o.optDouble("x", Double.NaN))
        val y0 = rect.optDouble("y", o.optDouble("y", Double.NaN))
        val w0 = rect.optDouble("w", o.optDouble("w", Double.NaN))
        val h0 = rect.optDouble("h", o.optDouble("h", Double.NaN))
        if (!x0.isFinite() || !y0.isFinite() || !w0.isFinite() || !h0.isFinite() ||
            w0 <= 0.0 || h0 <= 0.0) {
            throw IllegalArgumentException("方框坐标无效")
        }
        var x = x0.toFloat().coerceIn(0f, 0.98f)
        var y = y0.toFloat().coerceIn(0f, 0.98f)
        var w = w0.toFloat().coerceIn(0.01f, 1f)
        var h = h0.toFloat().coerceIn(0.01f, 1f)
        if (x + w > 1f) w = 1f - x
        if (y + h > 1f) h = 1f - y
        if (w < 0.01f || h < 0.01f) throw IllegalArgumentException("方框太小")

        val rules = mutableListOf<TriggerRule>()
        val arr = o.optJSONArray("rules")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val condition = r.optString("condition", "").trim()
                if (condition.isEmpty()) continue
                rules.add(TriggerRule(
                    condition = condition,
                    question = r.optString("question", "").trim(),
                    think = r.optBoolean("think", false),
                    pyPath = r.optString("py_path", "").trim()
                ))
            }
        } else {
            // 兼容只有 ask 的旧格式
            val ask = o.optString("ask", "")
            val (condition, question) = Condition.split(ask)
            if (condition.isNotBlank()) {
                rules.add(TriggerRule(
                    condition = condition,
                    question = question,
                    think = o.optBoolean("think", false),
                    pyPath = o.optString("py_path", "").trim()
                ))
            }
        }
        if (rules.isEmpty()) throw IllegalArgumentException("没有有效触发条件")
        return Patch(
            x = x, y = y, w = w, h = h,
            monitor = o.optBoolean("monitor", true),
            rules = rules,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun removeRuleState(ruleId: String) {
        ruleState.remove(ruleId)
        ruleRearmAt.remove(ruleId)
        sigByRule.remove(ruleId)
        resultByRule.remove(ruleId)
        resultAtByRule.remove(ruleId)
    }

    /** 文件选择器返回：优先拿真实路径（Python 里 import/读写同目录文件才自然） */
    private fun onPythonPicked(uri: Uri) {
        val input = pyPathInput ?: return
        try {
            val path = resolvePickedPath(uri)
            input.setText(path)
            PythonRunner.warmUp(applicationContext)
            Toast.makeText(this, "Python 脚本：$path", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "读取脚本失败：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resolvePickedPath(uri: Uri): String {
        if (uri.scheme == "file") return uri.path ?: error("空文件路径")
        if (uri.authority == "com.android.externalstorage.documents") {
            val docId = DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2) {
                val base = if (parts[0].equals("primary", true)) {
                    android.os.Environment.getExternalStorageDirectory()
                } else {
                    File("/storage", parts[0])
                }
                val f = File(base, parts[1])
                if (f.isFile && f.canRead()) return f.absolutePath
            }
        }
        // 其它 provider（网盘、下载管理器等）没有稳定路径：复制到应用私有目录
        var name = "script.py"
        var size = -1L
        try {
            contentResolver.query(uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        } catch (_: Throwable) { }
        if (size > 2L * 1024 * 1024) error("脚本文件太大（${size / 1024} KB），上限 2 MB")
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60).ifBlank { "script.py" }
        val dir = File(filesDir, "python").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}_$safe")
        contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { outs -> ins.copyTo(outs) }
        } ?: error("无法读取所选文件")
        return out.absolutePath
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
        when (rc) {
            REQ_PERMS -> {
                if (res.all { it == PackageManager.PERMISSION_GRANTED }) startCamera()
                else setStatus("缺少相机权限，无法工作")
            }
            REQ_STORAGE -> {
                // 旧系统（API 26~29）的存储权限；别和相机请求码混用，否则会二次绑定相机
                storageDenied = false
                if (res.all { it == PackageManager.PERMISSION_GRANTED } && hasAllFilesAccess()) {
                    initPatchShot()
                    setStatus("文件权限已就绪")
                }
            }
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
                // 和 PatchOverlayView.contentRect() 的 FILL_CENTER 变换保持一致
                binding.previewView.scaleType =
                    androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                // 预览和分析必须用同一个分辨率策略：两者宽高比不一致时，
                // 用户按预览框的区域和分析帧对不上，裁出来会偏。
                val resSel = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(1920, 1080),
                            androidx.camera.core.resolutionselector.ResolutionStrategy
                                .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(resSel)
                    .build().also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }
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
                hasFlash = try { cam.cameraInfo.hasFlashUnit() } catch (_: Throwable) { false }
                torchOn = false
                updateTorchButton()
                if (!hasFlash) pnLog("本机没有可用的相机闪光灯")
                // 连续对焦不一定一直锁得住；定期主动触发一次 AF
                afHandler.removeCallbacksAndMessages(null)
                val af = object : Runnable {
                    override fun run() {
                        if (isDestroyed) return
                        try {
                            val factory = binding.previewView.meteringPointFactory
                            val pt = factory.createPoint(
                                binding.previewView.width / 2f, binding.previewView.height / 2f)
                            cam.cameraControl.startFocusAndMetering(
                                androidx.camera.core.FocusMeteringAction.Builder(
                                    pt, androidx.camera.core.FocusMeteringAction.FLAG_AF
                                ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build())
                        } catch (e: Throwable) { }
                        if (!isDestroyed) afHandler.postDelayed(this, 2000)
                    }
                }
                afHandler.post(af)
                setStatus("相机已启动")
            } catch (e: Exception) {
                setStatus("相机启动失败：${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------- 手电筒

    private fun toggleTorch() {
        val cc = cameraControl
        if (cc == null || !hasFlash) {
            Toast.makeText(this, "相机还没有就绪，或本机没有闪光灯", Toast.LENGTH_SHORT).show()
            return
        }
        val want = !torchOn
        try {
            val future = cc.enableTorch(want)
            future.addListener({
                try {
                    future.get()
                    torchOn = want
                    updateTorchButton()
                    setStatus(if (want) "手电筒已常亮，继续监控中……" else "手电筒已关闭")
                } catch (t: Throwable) {
                    torchOn = false
                    updateTorchButton()
                    Toast.makeText(this, "手电筒不可用：${t.message ?: "被其它应用占用"}", Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (t: Throwable) {
            Toast.makeText(this, "手电筒不可用：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTorchButton() {
        binding.btnTorch.isEnabled = hasFlash
        binding.btnTorch.alpha = if (hasFlash) 1f else 0.35f
        binding.btnTorch.setImageResource(
            if (torchOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
        binding.btnTorch.contentDescription = when {
            !hasFlash -> getString(R.string.btn_torch_none)
            torchOn -> getString(R.string.btn_torch_on)
            else -> getString(R.string.btn_torch_off)
        }
    }

    private fun turnTorchOff() {
        if (!torchOn) return
        torchOn = false
        try { cameraControl?.enableTorch(false) } catch (_: Throwable) { }
        updateTorchButton()
    }

    // ----------------------------------------------------------- 判断循环

    /** 包一层：任何未捕获异常都要记下来并把 busy 解开，否则管线会永久卡死 */
    private fun onFrame(proxy: ImageProxy) {
        try {
            onFrameInner(proxy)
        } catch (t: Throwable) {
            pnLog("onFrame 异常: " + android.util.Log.getStackTraceString(t).take(1200))
            busy = false
            // 异常路径也必须把 ImageProxy 还给相机，否则分析流会永久停在上一帧
            try { proxy.close() } catch (_: Throwable) { }
            runOnUiThread { setStatus("帧处理异常：" + (t.message ?: t.javaClass.simpleName).take(40)) }
        }
    }

    private fun onFrameInner(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()

        // 即使没开始监控也要把画面尺寸告诉叠加层：框选坐标必须按真实画面裁，
        // 不能按视图比例算（PreviewView 是 FILL_CENTER，宽高比不同就会整体偏移）。
        val rot = proxy.imageInfo.rotationDegrees
        val uprightW = if (rot % 180 == 0) proxy.width else proxy.height
        val uprightH = if (rot % 180 == 0) proxy.height else proxy.width
        if (uprightW != lastImgW || uprightH != lastImgH) {
            lastImgW = uprightW
            lastImgH = uprightH
            runOnUiThread { binding.overlayView.setImageSize(uprightW, uprightH) }
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
        val bitmap: Bitmap
        try {
            bitmap = proxy.toBitmap()
        } catch (e: Exception) {
            pnLog("toBitmap 失败: " + (e.message ?: e.javaClass.simpleName))
            proxy.close(); return
        }
        proxy.close()

        // 不再每帧把 1080p 整帧旋转一遍：裁剪时按旋转矩阵反算源坐标，只转小图。
        PatchShot.setFrameSize(uprightW, uprightH)

        // 每天留一张整帧（未裁剪），方便日后确认这些裁剪图拍的是什么
        if (PatchShot.enabled && PatchShot.dailyDue()) {
            try {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (copy != null) {
                    Thread {
                        var up = copy
                        if (rot != 0) {
                            try {
                                val r = rotateBitmap(copy, rot)
                                if (r !== copy) { up = r; copy.recycle() }
                            } catch (_: Throwable) { }
                        }
                        PatchShot.saveDaily(up)
                    }.apply { isDaemon = true }.start()
                }
            } catch (_: Throwable) { }
        }

        // 轮询单位是一条规则：同一区域的多条条件各自独立判断、各自触发
        val targets = patches.filter { it.monitor }.flatMap { p ->
            p.rules.filter { it.condition.isNotBlank() }.map { MonitorTarget(p, it) }
        }
        if (targets.isEmpty()) {
            // 每帧都写日志/刷状态会拖慢预览，5 秒提示一次就够了
            if (now - lastNoTargetLogAt > 5000) {
                lastNoTargetLogAt = now
                pnLog("没有处于监控状态的区域/触发条件（共 ${patches.size} 个区域），请点「设条件」保存一次")
                setStatus("没有可监控的触发条件：点「设条件」写一行条件并保存")
            }
            bitmap.recycle(); busy = false
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
                    return
                }
                val target = targets[idx % targets.size]
                val p = target.patch
                val rule = target.rule
                idx++
                processed++
                // 每尝试一条规则就把游标前移；多行触发条件才会轮得到。
                rrIndex = (rrIndex + 1) % targets.size

                val crop = try {
                    cropPatch(bitmap, p, rot)
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

                if (localBusy) {
                    val stuck = SystemClock.elapsedRealtime() - localBusyAt
                    // 首次加载模型可能要几十秒；已经加载好后 60 秒还不回来才当死锁
                    val limit = if (localReady) 60_000L else 180_000L
                    if (stuck > limit) {
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
                val fs = "${bmp.width}x${bmp.height}"
                if (fs != lastFrameSize) { lastFrameSize = fs; pnLog("local: 整帧=${bitmap.width}x${bitmap.height} crop=$fs") }
                pnLog("local: 进入 patch=${p.title()} rule=${rule.condition.take(16)} crop=$fs think=${rule.think}")
                val found = resolveModelDir()
                if (found != localModelDir) { pnLog("local: 换用 $found"); localModelDir = found }

                try {
                    inferExecutor.execute {
                        var r = "ERR"
                    try {
                        if (!localReady) {
                            pnLog("local: 开始加载模型…")
                            val st = Vlm.probe(localModelDir)
                            pnLog("local: probe → $st")
                            localReady = st == "ok" || st.startsWith("ok ")
                            if (!localReady) {
                                r = if (st.startsWith("ERR:")) st else "模型加载失败：$st"
                            }
                        }
                        if (localReady) {
                            val bw = bmp.width
                            val bh = bmp.height
                            // 先做 48x20 亮度指纹：画面没变就直接复用上次结果。
                            // 以前在这之前就把整张裁剪图转成 RGB，缓存命中时白拷一份。
                            val sig = try {
                                val sw = 48; val sh = 20
                                val sc = Bitmap.createScaledBitmap(bmp, sw, sh, true)
                                val sp = IntArray(sw * sh)
                                sc.getPixels(sp, 0, sw, 0, 0, sw, sh)
                                if (sc !== bmp) sc.recycle()   // 尺寸刚好 48x20 时可能直接返回原图
                                IntArray(sw * sh) { i ->
                                    val c = sp[i]
                                    (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                                }
                            } catch (_: Throwable) { null }
                            val prev = sigByRule[rule.id]
                            var changed = true
                            if (prev != null && sig != null && prev.size == sig.size) {
                                var n = 0
                                for (i in sig.indices) if (kotlin.math.abs(sig[i] - prev[i]) > 28) n++
                                changed = n > sig.size * 15 / 1000
                            }
                            val cached = resultByRule[rule.id]
                            val cachedAt = resultAtByRule[rule.id] ?: 0L
                            if (!changed && cached != null && !cached.startsWith("ERR") &&
                                SystemClock.elapsedRealtime() - cachedAt < 120_000) {
                                r = cached
                                pnLog("local: 画面未变化，复用「$r」")
                            } else {
                                // 诊断：每 5 秒存一张实际喂给模型的裁剪图
                                val nowDump = SystemClock.elapsedRealtime()
                                if (nowDump - lastCropDumpAt > 5000) {
                                    lastCropDumpAt = nowDump
                                    if (sdOk) try {
                                        File(logDir(), "last_crop.jpg").outputStream().use {
                                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                                        }
                                        pnLog("local: 已存 last_crop.jpg ${bmp.width}x${bmp.height}")
                                    } catch (e: Exception) { pnLog("dump crop 失败: ${e.message}") }
                                }
                                val rgb = bmp.toRgbBytes()
                                val tInfer = SystemClock.elapsedRealtime()
                                r = Vlm.ask(rgb, bw, bh, rule.condition, rule.question, rule.think)
                                if (r.startsWith("ERR:THINK_TRUNC")) {
                                    pnLog("local: 思考被截断，改用不思考重跑一次")
                                    r = Vlm.ask(rgb, bw, bh, rule.condition, rule.question, false)
                                }
                                if (r.startsWith("ERR:TOOBIG:")) {
                                    val a = r.split(":")
                                    r = "框太大：${a.getOrNull(2)} 个图像token（上限 ${a.getOrNull(3)}），请把框划小一点"
                                }
                                sigByRule[rule.id] = sig ?: IntArray(0)
                                resultByRule[rule.id] = r
                                resultAtByRule[rule.id] = SystemClock.elapsedRealtime()
                                val dt = (SystemClock.elapsedRealtime() - tInfer) / 1000.0
                                pnLog("local: 判定 → 「$r」 用时 %.1fs".format(dt))
                                runOnUiThread {
                                    if (running) setStatus("${String.format(Locale.US, "%.1f", dt)}s → ${r.take(20)}")
                                }
                            }

                            val prevHit = ruleState[rule.id] == true
                            val hit = r.trim().startsWith("是") && !prevHit

                            // 截图存档：平时 60 秒一张，条件成立的那一帧强制补一张
                            try { PatchShot.maybe(p, bmp, hit) } catch (_: Throwable) { }

                            // 条件成立且是「否→是」的跃变时，再把这一行的问题问一遍，答案拿去播报
                            var ansText = ""
                            val q = rule.question
                            if (hit && r.trim() == "是" && q.isNotBlank()) {
                                val t1 = SystemClock.elapsedRealtime()
                                var ans = try {
                                    // openAnswer：这句是问答，不是是/否判断
                                    // 条件和问题分开传，questionPrompt 会一起放进提示词
                                    Vlm.ask(bmp.toRgbBytes(), bw, bh, rule.condition, q, rule.think, openAnswer = true).trim()
                                } catch (e: Throwable) { "ERR:" + e.message }
                                pnLog("local: 追问「$q」→ 「$ans」 用时 ${(SystemClock.elapsedRealtime() - t1) / 1000.0}s")
                                // 思考模式被截断 / 返回空 → 换成不思考再问一次，别让答案变成空
                                if (ans.isBlank() || ans.startsWith("ERR")) {
                                    val t2 = SystemClock.elapsedRealtime()
                                    val retry = try {
                                        Vlm.ask(bmp.toRgbBytes(), bw, bh, rule.condition, q, false, openAnswer = true).trim()
                                    } catch (e: Throwable) { "ERR:" + e.message }
                                    pnLog("local: 追问重试(不思考) 「${retry}」 用时 ${(SystemClock.elapsedRealtime() - t2) / 1000.0}s")
                                    if (retry.isNotBlank() && !retry.startsWith("ERR")) ans = retry
                                }
                                pnLog("local: 问答原始输出 prompt=「${Condition.questionPrompt(rule.condition, q)}」 raw=「${ans.replace("\n", " ")}」")
                                if (ans.isNotBlank() && !ans.startsWith("ERR")) {
                                    ansText = ans
                                    r = "是|" + ans
                                }
                            }
                            // 命中记一行：时间 / 判定 / 问题 / 回答
                            if (hit) try { PatchShot.event(p, "是", rule.question, ansText) } catch (_: Throwable) { }
                        }
                    } catch (e: Throwable) {
                        r = "ERR:" + (e.message ?: "").take(40)
                        pnLog("local: 异常 " + android.util.Log.getStackTraceString(e).take(1200))
                    } finally {
                        bmp.recycle()
                        localBusy = false
                        runOnUiThread { onVerdict(p, rule, r) }
                    }
                    }
                } catch (t: Throwable) {
                    crop.recycle()
                    throw t
                }
                bitmap.recycle()
                busy = false              // 推理在后台串行执行，这里不能占着 busy
                return
            } catch (t: Throwable) {
                pnLog("next 异常: " + android.util.Log.getStackTraceString(t).take(800))
                busy = false
                localBusy = false
                try { bitmap.recycle() } catch (_: Throwable) { }
            }
        }
        next()
    }

    /** 按转正后的归一化坐标裁剪。rotation 只用来反算源图坐标，转的是一张小图。 */
    private fun cropPatch(src: Bitmap, p: Patch, rotation: Int): Bitmap? {
        val sw = src.width
        val sh = src.height
        val u0 = p.x.coerceIn(0f, 1f)
        val v0 = p.y.coerceIn(0f, 1f)
        val u1 = (p.x + p.w).coerceIn(0f, 1f)
        val v1 = (p.y + p.h).coerceIn(0f, 1f)
        if (u1 - u0 <= 0f || v1 - v0 <= 0f) return null

        // 归一化源矩形：把「转正后」的四条边逆变换回传感器坐标
        var sl: Float; var st: Float; var sr: Float; var sb: Float
        when (((rotation % 360) + 360) % 360) {
            90 -> { sl = v0; st = 1f - u1; sr = v1; sb = 1f - u0 }
            180 -> { sl = 1f - u1; st = 1f - v1; sr = 1f - u0; sb = 1f - v0 }
            270 -> { sl = 1f - v1; st = u0; sr = 1f - v0; sb = u1 }
            else -> { sl = u0; st = v0; sr = u1; sb = v1 }
        }

        val left = (sl * sw).toInt().coerceIn(0, sw - 1)
        val top = (st * sh).toInt().coerceIn(0, sh - 1)
        val right = (sr * sw).toInt().coerceIn(left + 1, sw)
        val bottom = (sb * sh).toInt().coerceIn(top + 1, sh)

        var bmp = Bitmap.createBitmap(src, left, top, right - left, bottom - top)
        // 整帧框选时 createBitmap 可能直接把 src 返回；后面 src 会被 recycle，
        // 必须复制一份，否则推理线程拿到的是已回收的 Bitmap。
        if (bmp === src) {
            bmp = src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        }
        val deg = ((rotation % 360) + 360) % 360
        if (deg != 0) {
            val rotated = try { rotateBitmap(bmp, deg) } catch (_: Throwable) { null }
            if (rotated != null && rotated !== bmp) {
                bmp.recycle()
                bmp = rotated
            }
        }
        return bmp
    }

    private fun rotateBitmap(src: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return src
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun Bitmap.toRgbBytes(): ByteArray {
        val w = width
        val h = height
        val px = IntArray(w * h)
        getPixels(px, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) {
            val c = px[i]
            rgb[i * 3] = ((c shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (c and 0xFF).toByte()
        }
        return rgb
    }

    private fun testWithImage(uri: android.net.Uri) {
        setStatus("正在判断这张照片……")
        lifecycleScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val stream = if (uri.scheme == "file") {
                        java.io.FileInputStream(File(uri.path!!))
                    } else {
                        contentResolver.openInputStream(uri)
                    } ?: return@withContext "ERR:打不开图片"
                    val bmp0 = stream.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    } ?: return@withContext "ERR:图片解码失败"
                    val maxSide = 1600f
                    val sc = maxSide / maxOf(bmp0.width, bmp0.height)
                    val img = if (sc < 1f) {
                        Bitmap.createScaledBitmap(bmp0, (bmp0.width * sc).toInt(), (bmp0.height * sc).toInt(), true)
                            .also { if (it !== bmp0) bmp0.recycle() }
                    } else bmp0
                    if (!localReady) {
                        val st = Vlm.probe(resolveModelDir().also { localModelDir = it })
                        localReady = st == "ok" || st.startsWith("ok ")
                        if (!localReady) { img.recycle(); return@withContext "ERR:$st" }
                    }
                    val w = img.width; val h = img.height
                    // 只是让用户看看模型能读到什么，别预设成「读数字」，
                    // 也不用封闭问句（那会把提示词改成「只回答是或否」）
                    "OK:" + w + "x" + h + "\u0001" + Vlm.ask(
                        img.toRgbBytes(), w, h, "", "描述这张图，有文字或数字就原样读出来",
                        false, openAnswer = true
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
    private fun hasGguf(dir: File): Boolean =
        dir.isDirectory && dir.listFiles()?.any {
            it.isFile && it.name.endsWith(".gguf", true) && !it.name.startsWith("mmproj", true)
        } == true

    /** 模型本体和视觉编码器必须成对；只放了一半的目录不要选中，否则 probe 必失败 */
    private fun hasVlmPair(dir: File): Boolean =
        hasGguf(dir) && dir.listFiles()?.any {
            it.isFile && it.name.startsWith("mmproj", true) && it.name.endsWith(".gguf", true)
        } == true

    /** 在常见位置里找模型目录，避免用户放对了却因为路径不同读不到 */
    @Synchronized
    private fun resolveModelDir(): String {
        // 模型目录不会每秒变；已经加载好之后 60 秒内直接复用，别每次推理都扫 6 个目录
        val nowScan = SystemClock.elapsedRealtime()
        if (localReady && nowScan - lastModelScanAt < 60_000) return localModelDir
        lastModelScanAt = nowScan
        val priv = File(filesDir, "models")
        if (!hasAllFilesAccess()) {
            if (hasVlmPair(priv)) return priv.absolutePath
            return localModelDir
        }
        val root = android.os.Environment.getExternalStorageDirectory()
        val cands = listOf(
            File(localModelDir),
            File(filesDir, "models/qwen_2b"),
            File(root, "patchnotify/qwen_2b"),
            File(root, "Download/patchnotify/qwen_2b"),
            File(root, "Download/qwen_2b"),
            File(root, "qwen_2b")
        )
        for (c in cands) if (hasVlmPair(c)) return c.absolutePath
        for (c in cands) c.listFiles()?.filter { it.isDirectory }?.forEach {
            if (hasVlmPair(it)) return it.absolutePath
        }
        // 最后才是安装包自带、释放到私有目录的那份
        if (hasVlmPair(priv)) return priv.absolutePath
        return localModelDir
    }


    /** 直接在屏幕上显示日志尾部，避免来回复制文件拿到旧内容 */
    private fun showLog() {
        val sb = StringBuilder()
        // 没 sdcard 权限时 logDir() 就是 filesDir，直接去重，别把同一份日志显示两遍
        for (src in linkedSetOf(File(logDir(), "app.log"), File(filesDir, "app.log"))) {
            val alive = if (src.isFile) {
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(java.util.Date(src.lastModified()))
            } else "不存在"
            sb.append(src.absolutePath).append("  [").append(alive).append("]\n")
            tailLines(src, 80).forEach { sb.append(it).append("\n") }
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
        val sd = File(android.os.Environment.getExternalStorageDirectory(), "patchnotify")
        return if (sd.isDirectory || sd.mkdirs()) sd.absolutePath else filesDir.absolutePath
    }
    private fun pnLog(msg: String) {
        android.util.Log.i("PatchNotify", msg)
        val line = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            .format(java.util.Date()) + " " + msg + "\n"
        // 同一条日志只写一次；有 sdcard 权限时两个路径不同，各留一份
        for (f in linkedSetOf(File(logDir(), "app.log"), File(filesDir, "app.log"))) {
            appendLog(f, line)
        }
        try {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastBarAt > 2000) {
                lastBarAt = nowMs
                runOnUiThread { binding.buildText.text = "PatchNotify $BUILD_TAG · " + msg.take(40) }
            }
        } catch (_: Throwable) { }
    }

    /** 超过 2 MB 就轮转成 app.log.1；否则长时间运行会把日志撑到几百 MB，showLog 也会 OOM */
    @Synchronized
    private fun appendLog(f: File, line: String) {
        try {
            f.parentFile?.mkdirs()
            if (f.length() > MAX_LOG_BYTES) {
                val old = File(f.parentFile, f.name + ".1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line)
        } catch (_: Throwable) { }
    }

    /** 只保留尾部 n 行，内存占用与文件总大小无关 */
    private fun tailLines(f: File, n: Int): List<String> {
        if (!f.isFile) return emptyList()
        val dq = ArrayDeque<String>()
        try {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    dq.addLast(line)
                    if (dq.size > n) dq.removeFirst()
                }
            }
        } catch (_: Throwable) { }
        return dq.toList()
    }

    /** 本进程已经确认能写 /sdcard 一次之后就不再重复探测 */
    @Volatile private var lastBarAt = 0L
    @Volatile private var sdOk = false          // 能读写 /sdcard/patchnotify
    @Volatile private var storageOk = false
    // 没授权时不要每次推理都试写一次：写失败会走进异常路径，白白拖慢主流程
    @Volatile private var storageDenied = false
    @Volatile private var lastModelScanAt = 0L

    @Synchronized
    private fun hasAllFilesAccess(): Boolean {
        if (storageOk) { sdOk = true; return true }
        if (storageDenied) return false
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
            val dir = File(android.os.Environment.getExternalStorageDirectory(), "patchnotify")
            dir.mkdirs()
            val probe = File(dir, ".perm_probe")
            probe.writeText("ok")
            probe.delete()
            storageOk = true
            pnLog("权限实测通过：可以写 " + dir.absolutePath)
            true
        } catch (e: Throwable) {
            storageDenied = true
            false
        }
    }

    private fun initPatchShot() {
        try {
            PatchShot.init(File(logDir()), BUILD_TAG,
                "${android.os.Build.MODEL} / ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"} / ${android.os.Build.VERSION.RELEASE}")
        } catch (e: Exception) {
            pnLog("截图目录初始化失败: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时立刻重新判定，不必重启 App
        askingAccess = false
        storageDenied = false
        lastModelScanAt = 0L
        if (!storageOk && hasAllFilesAccess()) {
            pnLog("onResume: 权限已生效")
            localReady = false
            initPatchShot()      // 授权前 PatchShot 没初始化，授权后立刻补上截图/日志目录
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
            "· 从 sdcard 读取条件命中后要执行的 Python 脚本\n" +
            "· 把截图和日志写到 sdcard（方便导出查看）\n\n" +
            "不授予也能正常推理和判断——会使用应用私有目录里的内置模型，" +
            "Python 脚本可以用「选择」复制到私有目录，只是不写截图。\n\n" +
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
                        requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
                    }
                } catch (e: Throwable) {
                    // 兜底动作只能在 API 30+ 用；低版本回到本应用详情页，避免二次崩溃
                    try {
                        val fallback = if (android.os.Build.VERSION.SDK_INT >= 30) {
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        } else {
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:$packageName"))
                        }
                        startActivity(fallback)
                    } catch (t2: Throwable) {
                        Toast.makeText(this, "打不开系统设置：${t2.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("稍后再说") { _, _ -> askingAccess = false }
            .show()
    }

    /**
     * 一条规则的判定结果。
     * 只在「否 → 是」的跃变时执行这一行的动作；条件恢复后才重新武装。
     */
    private fun onVerdict(p: Patch, rule: TriggerRule, rawIn: String) {
        if (isDestroyed) return
        if (!running) {
            pnLog("judge: 监控已停止，忽略迟到的结果 ${p.title()}")
            return
        }
        // 推理在后台跑，期间用户可能已删除区域/修改规则：别为幽灵规则弹窗/播报
        val live = patches.firstOrNull { it.id == p.id }
        if (live == null || live.rules.none { it.id == rule.id }) {
            pnLog("judge: patch/规则已删除，忽略结果 ${p.title()}")
            return
        }
        if (!live.monitor) {
            pnLog("judge: patch 已暂停监控，忽略结果 ${p.title()}")
            return
        }
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

        val prev = ruleState[rule.id] ?: false
        ruleState[rule.id] = (yes == true)
        // 一个区域任意一条规则命中就显示红框；所有规则都恢复「否」才取消
        val wasHit = hits.contains(p.id)
        if (live.rules.any { ruleState[it.id] == true }) hits.add(p.id) else hits.remove(p.id)
        syncOverlay()
        if (wasHit != hits.contains(p.id)) runOnUiThread { refreshPatchList() }

        val now = SystemClock.elapsedRealtime()
        if (yes == false) {                      // 条件恢复 → 重新武装
            if (prev) ruleRearmAt[rule.id] = now + REARM_MS
        }

        // 只在判定真的变了才刷界面：每帧重建列表/改状态栏都会挤压布局，拖着预览一起抖
        if (lastShown[p.id] != shown) {
            lastShown[p.id] = shown
            runOnUiThread {
                refreshPatchList()
                setStatus("${p.title()}：${shown.take(24)}")
            }
        }

        pnLog("judge: rule=${rule.condition.take(20)} raw=${raw.replace("\n", " ").take(20)} yes=$yes " +
              "prev=$prev running=$running alerting=$alerting " +
              "rearm=${(ruleRearmAt[rule.id] ?: 0L) - now}ms prompt=${Condition.prompt(rule.condition).take(60)}")
        if (yes == true && !prev && running) {
            val canFire = now >= (ruleRearmAt[rule.id] ?: 0L)
            if (canFire) {
                ruleRearmAt[rule.id] = now + REARM_MS
                val text = answer
                val label = rule.condition.take(18).ifBlank { p.title() }
                // 中文 TTS 大约 3~4 字/秒；固定 8 秒会把长回答拦腰切断
                val secs = if (text.isBlank()) 5 else (6 + text.length / 3).coerceIn(8, 45)
                if (!alerting) {
                    pnLog("条件成立：${p.title()} / ${rule.condition.take(20)} → 播报「$text」")
                    triggerAlert(p, text, secs, label)
                } else {
                    // 任意一条都要执行对应动作：语音排队，Python 仍立刻执行
                    if (pendingAlerts.size < 8) {
                        pendingAlerts.addLast(PendingAlert(p, rule.id, text, secs, label))
                        pnLog("条件成立：${p.title()} / ${rule.condition.take(20)}；当前在播报，已排队")
                    } else {
                        pnLog("条件成立：${p.title()} / ${rule.condition.take(20)}；语音队列已满，丢弃本次播报")
                    }
                }
                // Python 是这一行自己的副作用：不因为语音被占用就吞掉
                if (rule.pyPath.isNotBlank()) {
                    PythonRunner.run(applicationContext, p, rule, text) { result ->
                        pnLog("python[${p.title()} / ${rule.condition.take(12)}]: ${result.take(600)}")
                        if (result.startsWith("ERR ")) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity,
                                    "Python 执行失败：" + result.removePrefix("ERR ").take(80),
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }

    }

    // ---------------------------------------------------------------- 播报

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                pnLog("TTS 初始化失败 status=$status")
            } else {
                val lang = try {
                    tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                } catch (_: Throwable) { TextToSpeech.LANG_NOT_SUPPORTED }
                ttsReady = lang != TextToSpeech.LANG_MISSING_DATA &&
                        lang != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) pnLog("TTS 没有中文语音包 lang=$lang")
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "s")
    }

    private fun triggerAlert(p: Patch, sayIn: String, seconds: Int, label: String) {
        // 排队期间区域可能被删除
        if (patches.none { it.id == p.id }) {
            pnLog("播报跳过：区域已删除 ${p.title()}")
            return
        }
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
            try {
                if (!beepOnly) {
                    // 播报前先响一声（和纯提示音同一个 2.8 kHz 方波），只响一次
                    beepOnce(RING_MS)
                    delay(450)
                    if (!isActive || !alerting) return@launch
                    speak(text)                 // 答案只说一遍，重复三遍是以前的循环遗留
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
            } catch (t: Throwable) {
                pnLog("播报异常: " + android.util.Log.getStackTraceString(t).take(600))
            } finally {
                // 任何异常/取消都不能让 alerting 永久卡在 true，否则之后再也不播报
                if (isActive && alerting) {
                    alerting = false
                    try { tts?.stop() } catch (_: Throwable) { }
                    runOnUiThread { binding.btnStopAlert.visibility = View.GONE }
                    setStatus("播报结束，继续监控中……")
                    var next = pendingAlerts.pollFirst()
                    while (next != null) {
                        val live = patches.firstOrNull { it.id == next.patch.id }
                        if (live != null && live.monitor && live.rules.any { it.id == next.ruleId }) break
                        next = pendingAlerts.pollFirst()
                    }
                    if (next != null) {
                        // 当前协程马上结束；直接起下一条，避免 post 与用户点“停止”竞态
                        triggerAlert(next.patch, next.text, next.secs, next.label)
                    }
                }
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
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
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
        pendingAlerts.clear()
        hits.clear()
        syncOverlay()
        refreshPatchList()
        binding.btnStopAlert.visibility = View.GONE
        setStatus("已停止播报")
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
            pendingAlerts.clear()
            hits.clear()
            syncOverlay()
            refreshPatchList()
            binding.btnStopAlert.visibility = View.GONE
            afHandler.removeCallbacksAndMessages(null)
            setStatus("已停止监控")
        }
    }

    /** 清掉按 patch 缓存的判定/指纹，保证下一次判定重新从「未命中」开始 */
    private fun resetDetection() {
        ruleState.clear()
        ruleRearmAt.clear()
        lastShown.clear()
        sigByRule.clear()
        resultByRule.clear()
        resultAtByRule.clear()
    }

    override fun onPause() {
        super.onPause()
        // 相机在后台会被解绑，手电筒实际已经灭；把状态和按钮同步过来
        turnTorchOff()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        alerting = false
        alertJob?.cancel()
        pendingAlerts.clear()
        tts?.stop(); tts?.shutdown()
        afHandler.removeCallbacksAndMessages(null)
        turnTorchOff()
        analysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        inferExecutor.shutdownNow()
        // PythonRunner 是进程级单例，不在这里 shutdown：Activity 重建后还要继续用
    }
}

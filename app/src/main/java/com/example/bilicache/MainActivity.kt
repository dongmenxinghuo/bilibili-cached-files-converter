package com.example.bilicache

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // 顶部状态
    private lateinit var statusTv: TextView
    private lateinit var authBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var scanProgress: ProgressBar

    // 合并面板
    private lateinit var panelMerge: View
    private lateinit var panelTranscode: View
    private lateinit var tabMerge: View
    private lateinit var tabTranscode: View
    private lateinit var tabMergeText: TextView
    private lateinit var tabTranscodeText: TextView
    private lateinit var tabMergeIndicator: View
    private lateinit var tabTranscodeIndicator: View

    private lateinit var filesContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var processBtn: Button
    private lateinit var processProgress: ProgressBar
    private lateinit var processStage: TextView
    private lateinit var mergeLog: TextView
    private lateinit var mergeResult: LinearLayout

    // 转码面板
    private lateinit var transcodePickBtn: Button
    private lateinit var transcodeFileTv: TextView
    private lateinit var transcodeOutName: EditText
    private lateinit var transcodeGoBtn: Button
    private lateinit var transcodeProgress: ProgressBar
    private lateinit var transcodeStage: TextView
    private lateinit var transcodeLog: TextView
    private lateinit var transcodeResult: LinearLayout
    private lateinit var qHigh: TextView
    private lateinit var qMid: TextView
    private lateinit var qLow: TextView

    // 数据
    private val items = mutableListOf<BiliCacheItem>()
    private var selectedTranscodeUri: Uri? = null
    private var selectedTranscodeName: String = ""
    private var transcodeQuality: Int = 4  // Mbps

    // Shizuku 请求回调
    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { req, res ->
        if (req == ShizukuHelper.REQUEST_CODE) {
            runOnUiThread { updateShizukuStatus() }
        }
    }

    // 文件选择器
    private val pickTranscodeFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedTranscodeUri = uri
            val name = getFileName(uri)
            selectedTranscodeName = name
            transcodeFileTv.text = "$name（已选择）"
            transcodeFileTv.setTextColor(ContextCompat.getColor(this, R.color.ok))
            transcodeGoBtn.isEnabled = true
        }
    }

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupTabs()
        setupListeners()
        setupShizuku()
        setupQualityChips()

        // 请求基础权限
        if (Build.VERSION.SDK_INT >= 33) {
            requestPerms.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS))
        } else {
            requestPerms.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }

        updateShizukuStatus()
    }

    private fun bindViews() {
        statusTv = findViewById(R.id.statusTv)
        authBtn = findViewById(R.id.authBtn)
        scanBtn = findViewById(R.id.scanBtn)
        scanProgress = findViewById(R.id.scanProgress)

        panelMerge = findViewById(R.id.panelMerge)
        panelTranscode = findViewById(R.id.panelTranscode)
        tabMerge = findViewById(R.id.tabMerge)
        tabTranscode = findViewById(R.id.tabTranscode)
        tabMergeText = findViewById(R.id.tabMergeText)
        tabTranscodeText = findViewById(R.id.tabTranscodeText)
        tabMergeIndicator = findViewById(R.id.tabMergeIndicator)
        tabTranscodeIndicator = findViewById(R.id.tabTranscodeIndicator)

        filesContainer = findViewById(R.id.filesContainer)
        emptyHint = findViewById(R.id.emptyHint)
        processBtn = findViewById(R.id.processBtn)
        processProgress = findViewById(R.id.processProgress)
        processStage = findViewById(R.id.processStage)
        mergeLog = findViewById(R.id.mergeLog)
        mergeResult = findViewById(R.id.mergeResult)

        transcodePickBtn = findViewById(R.id.transcodePickBtn)
        transcodeFileTv = findViewById(R.id.transcodeFileTv)
        transcodeOutName = findViewById(R.id.transcodeOutName)
        transcodeGoBtn = findViewById(R.id.transcodeGoBtn)
        transcodeProgress = findViewById(R.id.transcodeProgress)
        transcodeStage = findViewById(R.id.transcodeStage)
        transcodeLog = findViewById(R.id.transcodeLog)
        transcodeResult = findViewById(R.id.transcodeResult)
        qHigh = findViewById(R.id.qHigh)
        qMid = findViewById(R.id.qMid)
        qLow = findViewById(R.id.qLow)
    }

    private fun setupTabs() {
        tabMerge.setOnClickListener { switchTab(true) }
        tabTranscode.setOnClickListener { switchTab(false) }
        switchTab(true)
    }

    private fun switchTab(merge: Boolean) {
        val accColor = ContextCompat.getColor(this, R.color.acc)
        val dimColor = ContextCompat.getColor(this, R.color.dim)
        if (merge) {
            panelMerge.visibility = View.VISIBLE
            panelTranscode.visibility = View.GONE
            tabMergeText.setTextColor(accColor)
            tabTranscodeText.setTextColor(dimColor)
            tabMergeIndicator.setBackgroundColor(accColor)
            tabTranscodeIndicator.setBackgroundColor(Color.TRANSPARENT)
        } else {
            panelMerge.visibility = View.GONE
            panelTranscode.visibility = View.VISIBLE
            tabMergeText.setTextColor(dimColor)
            tabTranscodeText.setTextColor(accColor)
            tabMergeIndicator.setBackgroundColor(Color.TRANSPARENT)
            tabTranscodeIndicator.setBackgroundColor(accColor)
        }
    }

    private fun setupQualityChips() {
        qHigh.setOnClickListener { selectQuality(6) }
        qMid.setOnClickListener { selectQuality(4) }
        qLow.setOnClickListener { selectQuality(2) }
        selectQuality(4)
    }

    private fun selectQuality(mbps: Int) {
        transcodeQuality = mbps
        val chips = listOf(qHigh to 6, qMid to 4, qLow to 2)
        val accColor = ContextCompat.getColor(this, R.color.acc)
        val dimColor = ContextCompat.getColor(this, R.color.dim)
        for ((v, q) in chips) {
            v.isSelected = (q == mbps)
            v.setTextColor(if (q == mbps) accColor else dimColor)
            v.setTypeface(null, if (q == mbps) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun setupListeners() {
        authBtn.setOnClickListener {
            if (!ShizukuHelper.isBinderAlive()) {
                toast("请先启动 Shizuku 服务")
            } else {
                ShizukuHelper.requestPermission()
            }
        }
        scanBtn.setOnClickListener { doScan() }
        processBtn.setOnClickListener { doProcess() }

        transcodePickBtn.setOnClickListener {
            pickTranscodeFile.launch(arrayOf("video/*"))
        }
        transcodeGoBtn.setOnClickListener { doTranscode() }
    }

    /* ==================== Shizuku ==================== */

    private fun setupShizuku() {
        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        try {
            Shizuku.addBinderReceivedListenerSticky {
                runOnUiThread { updateShizukuStatus() }
            }
            Shizuku.addBinderDeadListener {
                runOnUiThread { updateShizukuStatus() }
            }
        } catch (_: Throwable) {}
    }

    private fun updateShizukuStatus() {
        val alive = ShizukuHelper.isBinderAlive()
        val has = ShizukuHelper.hasPermission()
        when {
            !alive -> {
                statusTv.text = "Shizuku：未运行 · 请先启动 Shizuku 服务"
                statusTv.setTextColor(ContextCompat.getColor(this, R.color.warn))
                authBtn.text = "请先启动 Shizuku"
                authBtn.isEnabled = false
                scanBtn.isEnabled = false
            }
            !has -> {
                statusTv.text = "Shizuku：已运行 · 尚未授权"
                statusTv.setTextColor(ContextCompat.getColor(this, R.color.warn))
                authBtn.text = "授权 Shizuku"
                authBtn.isEnabled = true
                scanBtn.isEnabled = false
            }
            else -> {
                statusTv.text = "Shizuku：已授权 ✓"
                statusTv.setTextColor(ContextCompat.getColor(this, R.color.ok))
                authBtn.text = "已授权"
                authBtn.isEnabled = false
                scanBtn.isEnabled = true
            }
        }
    }

    /* ==================== 扫描 ==================== */

    private fun doScan() {
        scanBtn.isEnabled = false
        scanProgress.visibility = View.VISIBLE
        filesContainer.removeAllViews()
        emptyHint.visibility = View.VISIBLE
        emptyHint.text = "正在扫描 B 站缓存…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { CacheScanner.scan() } catch (e: Throwable) { emptyList() }
            }
            items.clear()
            items.addAll(result)
            renderFileList()
            scanProgress.visibility = View.GONE
            scanBtn.isEnabled = true
            if (items.isEmpty()) {
                emptyHint.visibility = View.VISIBLE
                emptyHint.text = "没有找到 B 站缓存。\n请确认 B 站已下载视频，且 Shizuku 已授权。"
            } else {
                emptyHint.visibility = View.GONE
            }
        }
    }

    private fun renderFileList() {
        filesContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val v = inflater.inflate(R.layout.item_cache, filesContainer, false)
            val cb = v.findViewById<CheckBox>(R.id.itemCheck)
            val title = v.findViewById<TextView>(R.id.itemTitle)
            val size = v.findViewById<TextView>(R.id.itemSize)
            val codec = v.findViewById<TextView>(R.id.itemCodec)
            val stat = v.findViewById<TextView>(R.id.itemStatus)

            title.text = item.title
            size.text = fmtSize(item.sizeBytes)
            cb.isChecked = item.selected
            cb.setOnCheckedChangeListener { _, b -> item.selected = b }

            if (item.codec.isNotEmpty()) {
                codec.visibility = View.VISIBLE
                val (bg, fg, label) = when {
                    item.codec.startsWith("hvc") || item.codec.startsWith("hev") ->
                        Triple(R.color.tag_u_bg, R.color.tag_u_fg, "HEVC")
                    item.codec.startsWith("avc") ->
                        Triple(R.color.tag_a_bg, R.color.tag_a_fg, "H.264")
                    else ->
                        Triple(R.color.tag_v_bg, R.color.tag_v_fg, item.codec)
                }
                codec.text = label
                codec.setBackgroundColor(ContextCompat.getColor(this, bg))
                codec.setTextColor(ContextCompat.getColor(this, fg))
            } else {
                codec.visibility = View.GONE
            }

            if (item.status.isNotEmpty()) {
                stat.visibility = View.VISIBLE
                stat.text = item.status
                stat.setTextColor(ContextCompat.getColor(this, when {
                    item.status.startsWith("完成") -> R.color.ok
                    item.status.startsWith("失败") -> R.color.err
                    else -> R.color.warn
                }))
            } else {
                stat.visibility = View.GONE
            }

            filesContainer.addView(v)
        }
        updateProcessBtnState()
    }

    private fun updateProcessBtnState() {
        val anySelected = items.any { it.selected }
        processBtn.isEnabled = anySelected
    }

    /* ==================== 处理（合并 + 转码） ==================== */

    private fun doProcess() {
        val selected = items.filter { it.selected }
        if (selected.isEmpty()) { toast("请先勾选"); return }

        processBtn.isEnabled = false
        processProgress.visibility = View.VISIBLE
        processProgress.max = selected.size
        processProgress.progress = 0
        processStage.visibility = View.VISIBLE
        mergeLog.visibility = View.VISIBLE
        mergeLog.text = ""
        mergeResult.visibility = View.GONE

        lifecycleScope.launch {
            val outDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "BiliCache"
            )
            outDir.mkdirs()
            logLine("[输出目录] ${outDir.absolutePath}")

            var successCount = 0

            for ((idx, item) in selected.withIndex()) {
                processStage.text = "(${idx + 1}/${selected.size}) 处理中：${item.title.take(30)}"
                item.status = "合并中…"
                refreshItem(item)

                val ok = processOne(item, outDir)
                if (ok) successCount++
                processProgress.progress = idx + 1
            }

            processStage.text = "完成：成功 $successCount / ${selected.size}"
            processBtn.isEnabled = true
            logLine("全部完成 ✓  输出目录：${outDir.absolutePath}", "ok")

            if (successCount > 0) {
                showResult(outDir)
            }
        }
    }

    private suspend fun processOne(item: BiliCacheItem, outDir: File): Boolean {
        return try {
            // 1) 拷贝 m4s 到应用私有目录
            val tmpDir = File(cacheDir, "m4s_tmp").apply { mkdirs() }
            val safeKey = item.dir.hashCode().toString()
            val vLocal = File(tmpDir, "v_$safeKey.m4s")
            val aLocal = File(tmpDir, "a_$safeKey.m4s")

            logLine("→ ${item.title}")
            logLine("  复制 video.m4s…")
            if (!withContext(Dispatchers.IO) { ShizukuHelper.copyToApp(item.videoPath, vLocal) }) {
                item.status = "失败：复制视频失败"
                refreshItem(item)
                logLine("  失败：复制视频失败", "er")
                return false
            }
            logLine("  复制 audio.m4s…")
            if (!withContext(Dispatchers.IO) { ShizukuHelper.copyToApp(item.audioPath, aLocal) }) {
                item.status = "失败：复制音频失败"
                refreshItem(item)
                logLine("  失败：复制音频失败", "er")
                return false
            }

            // 2) 合并
            logLine("  合并中…")
            val merged = File(tmpDir, "merged_$safeKey.mp4")
            val codec = withContext(Dispatchers.IO) { M4sMerger.merge(vLocal, aLocal, merged) }
            item.codec = codec
            logLine("  视频编码: $codec")
            vLocal.delete(); aLocal.delete()

            // 3) 是否转码
            val safeName = item.title
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(60)
                .ifBlank { "video_$safeKey" }
            val finalOut = File(outDir, "$safeName.mp4")

            val needTranscode = codec.startsWith("hvc") || codec.startsWith("hev")
            if (needTranscode) {
                item.status = "转码中…"
                refreshItem(item)
                logLine("  转码 HEVC → H.264（硬件加速）…")
                withContext(Dispatchers.Main) {
                    Transcoder.transcode(this@MainActivity, merged, finalOut)
                }
                merged.delete()
                logLine("  转码完成 → ${finalOut.name}", "ok")
            } else {
                merged.copyTo(finalOut, overwrite = true)
                merged.delete()
                logLine("  无需转码，直接保存 → ${finalOut.name}", "ok")
            }

            item.status = "完成 ✓"
            refreshItem(item)
            true
        } catch (e: Throwable) {
            item.status = "失败：${e.message?.take(25) ?: "未知"}"
            refreshItem(item)
            logLine("  失败：${e.message}", "er")
            false
        }
    }

    private fun refreshItem(item: BiliCacheItem) {
        val idx = items.indexOf(item)
        if (idx < 0) return
        val v = filesContainer.getChildAt(idx) ?: return
        val stat = v.findViewById<TextView>(R.id.itemStatus)
        stat.visibility = View.VISIBLE
        stat.text = item.status
        stat.setTextColor(ContextCompat.getColor(this, when {
            item.status.startsWith("完成") -> R.color.ok
            item.status.startsWith("失败") -> R.color.err
            else -> R.color.warn
        }))
    }

    /* ==================== 单独转码 ==================== */

    private fun doTranscode() {
        val uri = selectedTranscodeUri ?: return
        transcodeGoBtn.isEnabled = false
        transcodeGoBtn.text = "处理中…"
        transcodeProgress.visibility = View.VISIBLE
        transcodeProgress.isIndeterminate = true
        transcodeStage.visibility = View.VISIBLE
        transcodeStage.text = "准备中…"
        transcodeLog.visibility = View.VISIBLE
        transcodeLog.text = ""
        transcodeResult.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val tmpDir = File(cacheDir, "transcode_tmp").apply { mkdirs() }
                val inputFile = File(tmpDir, "input.mp4")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        inputFile.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                }
                logLine("输入: ${selectedTranscodeName}")
                logLine("大小: ${fmtSize(inputFile.length())}")
                logLine("开始转码（硬件加速）…")

                val outDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "BiliCache"
                )
                outDir.mkdirs()

                val baseName = selectedTranscodeName
                    .substringBeforeLast('.')
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                var finalName = transcodeOutName.text.toString().trim()
                if (finalName.isEmpty()) finalName = "${baseName}_h264"
                finalName = finalName.removeSuffix(".mp4") + ".mp4"
                val outFile = File(outDir, finalName)

                withContext(Dispatchers.Main) {
                    Transcoder.transcode(this@MainActivity, inputFile, outFile)
                }
                inputFile.delete()

                transcodeProgress.isIndeterminate = false
                transcodeProgress.progress = 100
                transcodeStage.text = "完成"
                logLine("转码完成 → ${outFile.name}", "ok")
                logLine("大小: ${fmtSize(outFile.length())}", "ok")
                showTranscodeResult(outFile)
            } catch (e: Throwable) {
                logLine("失败: ${e.message}", "er")
                transcodeStage.text = "失败"
                transcodeProgress.isIndeterminate = false
            } finally {
                transcodeGoBtn.isEnabled = true
                transcodeGoBtn.text = "开始转码"
            }
        }
    }

    /* ==================== 辅助 ==================== */

    private fun showResult(outDir: File) {
        mergeResult.removeAllViews()
        mergeResult.visibility = View.VISIBLE
        val tv = TextView(this).apply {
            text = "✓ 处理完成\n所有文件已保存到：\n${outDir.absolutePath}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok))
            textSize = 13f
            setPadding(30, 30, 30, 30)
            setBackgroundResource(R.drawable.bg_result)
        }
        mergeResult.addView(tv)
    }

    private fun showTranscodeResult(outFile: File) {
        transcodeResult.removeAllViews()
        transcodeResult.visibility = View.VISIBLE
        val tv = TextView(this).apply {
            text = "✓ 转码完成\n文件：${outFile.name}\n路径：${outFile.absolutePath}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok))
            textSize = 13f
            setPadding(30, 30, 30, 30)
            setBackgroundResource(R.drawable.bg_result)
        }
        transcodeResult.addView(tv)
    }

    private var logStartTime = 0L

    private fun logLine(msg: String, level: String = "") {
        if (logStartTime == 0L) logStartTime = System.currentTimeMillis()
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val prefix = "[$ts] "
        val full = prefix + msg + "\n"
        mergeLog.append(full)
        if (level == "ok") { /* 可以用 Spannable 上色 */ }
    }

    private fun tLogLine(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        transcodeLog.append("[$ts] $msg\n")
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun fmtSize(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
        else -> "%.2f GB".format(n / 1024.0 / 1024.0 / 1024.0)
    }

    private fun getFileName(uri: Uri): String {
        var name = "video.mp4"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx) ?: name
            }
        }
        return name
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        } catch (_: Throwable) {}
    }
}

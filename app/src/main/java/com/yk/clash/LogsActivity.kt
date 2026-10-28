package com.yk.clash

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yk.clash.ui.AppUi
import okhttp3.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class LogsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var history: CoreLogStore
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var queryGeneration = 0
    private var shownRevision = -1L
    private var loading = false
    private var total = 0
    private var matches = 0
    @Volatile private var active = false
    @Volatile private var paused = false
    private var level = "all"
    private var query = ""
    private var rows = emptyList<CoreLog>()
    private lateinit var ui: AppUi
    private lateinit var status: TextView
    private lateinit var outputLevel: TextView
    private lateinit var list: ListView
    private lateinit var adapter: BaseAdapter
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val searchRefresh = Runnable { refreshRows() }
    private val refresh = object : Runnable {
        override fun run() {
            if (!active) return
            updateStatus()
            if (!paused && !loading && shownRevision != history.revision) refreshRows()
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ui = AppUi(this)
        setTheme(if (ui.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        history = CoreLogStore.get(this)
        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0); setBackgroundColor(ui.page) }
        body.addView(ui.row().apply {
            addView(ui.backButton { finish() })
            addView(ui.text("运行日志", 23f, ui.ink, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(16) })
        })
        body.addView(ui.gap(12))
        body.addView(ui.row().apply {
            val filter = ui.button("全部级别") {}
            filter.setOnClickListener {
                val values = arrayOf("all", "trace", "debug", "info", "warn", "error")
                val labels = arrayOf("全部级别", "Trace", "Debug", "Info", "Warn", "Error")
                ui.choiceDialog("筛选日志级别", labels.toList(), emptyList(), values.indexOf(level)) { which ->
                    level = values[which]; filter.text = labels[which]
                    ui.paintButton(filter, level != "all"); refreshRows()
                }
            }
            addView(filter, LinearLayout.LayoutParams(0, -2, 1f))
            val pause = ui.button("暂停") {}
            pause.setOnClickListener {
                paused = !paused; pause.text = if (paused) "继续" else "暂停"
                ui.paintButton(pause, paused)
                pause.stateDescription = if (paused) "已暂停" else "实时显示"
                if (!paused) refreshRows() else updateStatus()
            }
            addView(pause, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
            addView(ui.button("清空") {
                val dialog = AlertDialog.Builder(this@LogsActivity).setTitle("清空累计日志？")
                    .setMessage("清空已保存的历史记录；内核运行时仍会继续记录新日志。")
                    .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                        history.clear { main.post { if (active) refreshRows() } }
                    }.create()
                dialog.show(); ui.styleDialog(dialog)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
        })
        outputLevel = ui.button("内核输出级别 · 未连接") {
            val values = arrayOf("trace", "debug", "info", "warning", "error", "silent")
            ui.choiceDialog("内核输出级别（本次运行）", values.toList(), emptyList(), -1) { index -> requestOutputLevel(values[index]) }
        }
        body.addView(outputLevel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        body.addView(ui.searchField("搜索全部累计日志") {
            query = it; queryGeneration++; loading = false
            main.removeCallbacks(searchRefresh); main.postDelayed(searchRefresh, 250)
        },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12); bottomMargin = ui.dp(12) })
        status = ui.text("正在读取累计日志…", 12f, ui.muted)
        body.addView(status); body.addView(ui.gap(8))
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val entry = rows[position]
                return (convertView as? TextView ?: ui.text("", 12f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setLineSpacing(ui.dp(2).toFloat(), 1f)
                    setPadding(0, ui.dp(3), 0, ui.dp(3))
                }).apply {
                    val prefix = "${formatter.format(Date(entry.time))} [${entry.level.uppercase()}] "
                    text = android.text.SpannableString(prefix + entry.message).apply {
                        setSpan(android.text.style.ForegroundColorSpan(when (entry.level) {
                            "error" -> ui.danger
                            "warn" -> ui.warning
                            else -> ui.muted
                        }), 0, prefix.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
        list = ListView(this).apply {
            divider = null
            setSelector(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            adapter = this@LogsActivity.adapter
        }
        val output = FrameLayout(this).apply {
            background = ui.surface(ui.card, 18)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            addView(list, FrameLayout.LayoutParams(-1, -1))
        }
        body.addView(output, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = ui.dp(12) })
        setContentView(body)
    }

    override fun onResume() {
        super.onResume(); active = true; refreshRows(); requestOutputLevel(null); main.post(refresh)
    }
    override fun onPause() {
        active = false; queryGeneration++; main.removeCallbacks(refresh); main.removeCallbacks(searchRefresh)
        super.onPause()
    }
    override fun onDestroy() {
        client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun updateStatus() {
        if (::status.isInitialized) status.text = buildString {
            append(CoreLogCollector.state)
            if (paused) append(" · 显示已暂停")
            append("\n累计 $total 条 · 匹配 $matches 条")
            if (matches > rows.size) append(" · 显示最近 ${rows.size} 条，请搜索缩小范围")
            append(" · 最多保留约 8 MB")
            if (history.error.isNotEmpty()) append("\n${history.error}")
        }
    }
    private fun requestOutputLevel(level: String?) {
        if (ClashVpnService.state != "已连接") {
            if (level != null) Toast.makeText(this, "请先连接代理", Toast.LENGTH_SHORT).show()
            return
        }
        client.dispatcher.executorService.execute {
            val result = runCatching {
                if (level != null) CoreApi.request("/configs", "PATCH", JSONObject().put("log-level", level))
                CoreApi.request("/configs").optString("log-level", "warning")
            }
            main.post {
                if (active && !isDestroyed) result.onSuccess { outputLevel.text = "内核输出级别 · $it（点击修改）" }
                    .onFailure { Toast.makeText(this, it.message ?: "读取日志级别失败", Toast.LENGTH_LONG).show() }
            }
        }
    }
    private fun refreshRows() {
        if (!::adapter.isInitialized || !active) return
        val attempt = ++queryGeneration
        val revision = history.revision
        loading = true
        history.read(level, query) { result -> main.post {
            if (!active || attempt != queryGeneration) return@post
            loading = false; shownRevision = revision
            val follow = rows.isEmpty() || list.lastVisiblePosition >= rows.lastIndex - 1
            val first = list.firstVisiblePosition
            val top = list.getChildAt(0)?.top ?: 0
            rows = result.entries; total = result.total; matches = result.matches
            adapter.notifyDataSetChanged(); updateStatus()
            if (follow && rows.isNotEmpty()) list.setSelection(rows.lastIndex)
            else if (rows.isNotEmpty()) list.setSelectionFromTop(first.coerceAtMost(rows.lastIndex), top)
        } }
    }
}

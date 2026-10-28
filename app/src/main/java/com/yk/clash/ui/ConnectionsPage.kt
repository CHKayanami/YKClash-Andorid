package com.yk.clash.ui

import android.annotation.SuppressLint

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.yk.clash.CoreApi
import com.yk.clash.R
import java.net.URLEncoder
import java.util.concurrent.Executors

@SuppressLint("ViewConstructor")
class ConnectionsPage(private val activity: Activity, private val ui: AppUi) : LinearLayout(activity) {
    private val network = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var records = emptyList<ConnectionRecord>()
    private var visible = emptyList<ConnectionRecord>()
    private var closed = false
    private var paused = false
    private var loading = false
    private val summary = ui.text("正在读取连接…", 13f, ui.muted)
    private val search = EditText(activity).apply {
        hint = "搜索域名、IP 或规则集"; textSize = 14f; isSingleLine = true
        setTextColor(ui.ink); setHintTextColor(ui.muted); background = null
        setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
    }
    private val searchBox = ui.row().apply {
        background = ui.surface(ui.block, 16); setPadding(ui.dp(12), 0, ui.dp(12), 0)
        addView(ui.icon(Glyph.SEARCH, ui.muted, 20)); addView(search, LayoutParams(0, -2, 1f)); visibility = GONE
    }
    private val list = ListView(activity).apply {
        divider = null; setSelector(ColorDrawable(Color.TRANSPARENT)); isVerticalScrollBarEnabled = false
        setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16)); clipToPadding = false
    }
    private val empty = ui.empty("暂无活动连接", "开始访问网络后，命中的规则集将在这里显示", Glyph.CONNECTIONS)
    private val adapter = RecordsAdapter()
    private val poll = object : Runnable {
        override fun run() { if (!closed && !paused) reload(); if (!closed) main.postDelayed(this, 2000) }
    }

    init {
        orientation = VERTICAL
        addView(searchBox, LayoutParams(-1, -2).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(12)) })
        addView(summary, LayoutParams(-1, -2).apply { setMargins(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(14)) })
        addView(FrameLayout(activity).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1)); addView(empty, FrameLayout.LayoutParams(-1, -1))
        }, LayoutParams(-1, 0, 1f))
        list.emptyView = empty; list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        main.post(poll)
    }
    fun close() { closed = true; main.removeCallbacks(poll); network.shutdownNow() }
    fun setPaused(value: Boolean) { paused = value; if (!value) reload() }
    fun toggleSearch() {
        val open = searchBox.visibility != VISIBLE
        searchBox.visibility = if (open) VISIBLE else GONE
        if (open) {
            search.requestFocus(); activity.getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        } else { search.setText(""); activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0) }
    }
    fun reload() {
        if (loading || closed) return
        loading = true
        network.execute {
            val result = runCatching {
                val response = CoreApi.request("/connections")
                val items = response.optJSONArray("connections")
                val parsed = if (items == null) emptyList() else (0 until items.length()).map { ConnectionRecord.parse(items.getJSONObject(it)) }
                Triple(parsed, response.optLong("uploadTotal"), response.optLong("downloadTotal"))
            }
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                loading = false
                result.onSuccess { (items, up, down) ->
                    records = items
                    summary.text = activity.getString(R.string.connection_summary, items.size, formatBytes(up), formatBytes(down))
                    filter()
                }.onFailure { summary.text = it.message ?: "读取连接失败" }
            }
        }
    }
    fun closeAll() {
        val dialog = AlertDialog.Builder(activity).setTitle("关闭全部连接？")
            .setMessage("当前活动连接将中断，应用后续可重新建立连接。")
            .setNegativeButton("取消", null).setPositiveButton("关闭全部") { _, _ -> performClose("/connections") }.create()
        dialog.show(); ui.styleDialog(dialog)
    }
    private fun performClose(path: String) {
        network.execute {
            val result = runCatching { CoreApi.request(path, "DELETE") }
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                result.onSuccess { reload() }.onFailure { Toast.makeText(activity, it.message ?: "关闭失败", Toast.LENGTH_LONG).show() }
            }
        }
    }
    private fun filter() {
        val query = search.text.toString().trim()
        visible = if (query.isEmpty()) records else records.filter {
            it.host.contains(query, true) || it.ip.contains(query, true) || it.rule.value.contains(query, true) || it.chains.contains(query, true)
        }
        adapter.notifyDataSetChanged()
    }
    private fun showDetails(item: ConnectionRecord) {
        val content = ui.column().apply {
            setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12))
            listOf("目标" to "${item.host}:${item.port}", "目标 IP" to item.ip,
                "来源" to item.source, item.rule.label to item.rule.value,
                "出站链路" to item.chains.ifBlank { "未提供" },
                "流量" to "↑ ${formatBytes(item.upload)}  ↓ ${formatBytes(item.download)}").forEach { (label, value) ->
                addView(ui.text(label, 12f, ui.muted)); addView(ui.gap(4))
                addView(ui.text(value.ifBlank { "—" }, 14f).apply { setTextIsSelectable(true) }); addView(ui.gap(14))
            }
        }
        val dialog = AlertDialog.Builder(activity).setTitle("连接详情").setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("返回", null).setPositiveButton("关闭连接") { _, _ -> performClose("/connections/${encode(item.id)}") }.create()
        dialog.show(); ui.styleDialog(dialog)
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private inner class RecordCell {
        val root = ui.column().apply { setPadding(0, ui.dp(5), 0, ui.dp(5)) }
        val card = ui.panel(22).apply { background = ui.touch(ui.card, 22); setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)); isFocusable = true }
        val host = ui.text("", 15f, ui.ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val protocol = ui.text("", 10f, ui.muted, true)
        val rule = ui.text("", 13f, ui.primary, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val chain = ui.text("", 12f, ui.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val traffic = ui.text("", 11f, ui.muted)
        var current: ConnectionRecord? = null
        init {
            val close = ui.iconButton(Glyph.CLOSE, "关闭此连接") { current?.let { performClose("/connections/${encode(it.id)}") } }.apply {
                background = ui.touch(ui.card, 12); minimumHeight = ui.dp(40); minimumWidth = ui.dp(40)
                layoutParams = LayoutParams(ui.dp(40), ui.dp(40))
            }
            card.addView(ui.row().apply {
                val title = ui.column().apply { addView(host); addView(ui.gap(4)); addView(protocol) }
                addView(title, LayoutParams(0, -2, 1f)); addView(close)
            })
            card.addView(ui.gap(12)); card.addView(rule); card.addView(ui.gap(7)); card.addView(chain)
            card.addView(ui.gap(12)); card.addView(traffic); root.addView(card); root.tag = this
            card.setOnClickListener { current?.let { showDetails(it) } }
        }
        fun bind(item: ConnectionRecord) {
            current = item; host.text = activity.getString(R.string.connection_endpoint, item.host, item.port); protocol.text = item.network
            rule.text = activity.getString(R.string.connection_rule, item.rule.label, item.rule.value)
            chain.text = activity.getString(R.string.connection_chain, item.chains.ifBlank { "—" })
            traffic.text = activity.getString(R.string.connection_traffic, formatBytes(item.upload), formatBytes(item.download))
            card.contentDescription = "${item.host}，${item.rule.label} ${item.rule.value}，出站 ${item.chains}"
        }
    }
    private inner class RecordsAdapter : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int) = visible[position].id.hashCode().toLong()
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? RecordCell) ?: RecordCell()
            holder.bind(visible[position]); return holder.root
        }
    }
}

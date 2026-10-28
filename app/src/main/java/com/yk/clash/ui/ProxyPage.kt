package com.yk.clash.ui

import android.annotation.SuppressLint

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.yk.clash.CoreApi
import com.yk.clash.NativeCore
import com.yk.clash.ProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

@SuppressLint("ViewConstructor")
class ProxyPage(
    private val activity: Activity,
    private val ui: AppUi,
    private val store: ProfileStore,
    private val onTesting: (Boolean) -> Unit,
) : LinearLayout(activity) {
    private val network = Executors.newSingleThreadExecutor()
    private val tests = Executors.newFixedThreadPool(2)
    private val prefs = activity.getSharedPreferences("interface", Activity.MODE_PRIVATE)
    private val profileKey = "group_${store.selected()?.id}"
    private var chosen = prefs.getString(profileKey, "").orEmpty()
    private var catalog = ProxyCatalog(emptyList(), emptyMap())
    private var visibleNodes = emptyList<String>()
    private var closed = false
    private var revision = 0
    private var pendingTests = 0
    private val delays = mutableMapOf<String, String>()
    private val chips = ui.row()
    private val strip = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
    private val summary = ui.text("正在加载代理组…", 15f, ui.ink, true)
    private val detail = ui.text("", 12f, ui.muted)
    private val search = EditText(activity).apply {
        hint = "搜索当前组的节点"; textSize = 14f; setTextColor(ui.ink); setHintTextColor(ui.muted)
        isSingleLine = true; background = null; setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
    }
    private val searchBox = ui.row().apply {
        background = ui.surface(ui.block, 16); setPadding(ui.dp(12), 0, ui.dp(12), 0)
        addView(ui.icon(Glyph.SEARCH, ui.muted, 20)); addView(search, LayoutParams(0, -2, 1f))
        visibility = GONE
    }
    private val list = ListView(activity).apply {
        divider = null; setSelector(ColorDrawable(Color.TRANSPARENT)); isVerticalScrollBarEnabled = false
        setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16)); clipToPadding = false
    }
    private val empty = ui.empty("正在加载", "读取内核代理信息")
    private val adapter = NodesAdapter()
    private val columns = if (resources.configuration.screenWidthDp < 340 || resources.configuration.fontScale > 1.25f) 1 else 2

    init {
        orientation = VERTICAL
        val groupsBar = ui.row().apply {
            setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(8))
            addView(strip, LayoutParams(0, ui.dp(44), 1f))
            addView(ui.iconButton(Glyph.MORE, "全部代理组") { showGroups() }, LayoutParams(ui.dp(40), ui.dp(40)).apply { leftMargin = ui.dp(8) })
        }
        addView(groupsBar)
        addView(searchBox, LayoutParams(-1, -2).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(12)) })
        val heading = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12))
            addView(summary); addView(ui.gap(6)); addView(detail)
        }
        addView(heading)
        val content = FrameLayout(activity).apply { addView(list, FrameLayout.LayoutParams(-1, -1)); addView(empty, FrameLayout.LayoutParams(-1, -1)) }
        addView(content, LayoutParams(-1, 0, 1f))
        list.emptyView = empty; list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        reload()
    }

    fun close() { closed = true; revision++; network.shutdownNow(); tests.shutdownNow() }
    fun toggleSearch() {
        val open = searchBox.visibility != VISIBLE
        searchBox.visibility = if (open) VISIBLE else GONE
        if (open) {
            search.requestFocus()
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        } else {
            search.setText("")
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        }
    }

    fun reload() {
        val request = ++revision
        network.execute {
            val result = runCatching {
                val active = CoreApi.request("/proxies").getJSONObject("proxies")
                val order = runCatching {
                    val profile = store.selected() ?: error("请先选择配置")
                    val config = com.yk.clash.editor.GlobalOverrideStore(activity).resolve(store.read(profile))
                    JSONArray(NativeCore.groupOrder(config)).stringValues()
                }.getOrElse {
                    runCatching {
                        val last = com.yk.clash.RuntimeYamlStore(activity).read() ?: error("没有运行快照")
                        JSONArray(NativeCore.groupOrder(last)).stringValues()
                    }.getOrDefault(emptyList())
                }
                ProxyCatalog.parse(active, order)
            }
            activity.runOnUiThread {
                if (closed || request != revision) return@runOnUiThread
                result.onSuccess {
                    catalog = it
                    if (catalog.groups.none { group -> group.name == chosen }) chosen = catalog.groups.firstOrNull()?.name.orEmpty()
                    renderGroups(); filter()
                }.onFailure { failure ->
                    summary.text = "代理加载失败"; detail.text = failure.message ?: "请重试"
                    showEmpty("加载失败", "使用右上角菜单重新加载")
                }
            }
        }
    }

    private fun group() = catalog.groups.find { it.name == chosen }
    private fun renderGroups() {
        chips.removeAllViews()
        var selectedChip: View? = null
        catalog.groups.forEach { group ->
            val selected = group.name == chosen
            val chip = ui.text(group.name, 13f, if (selected) ui.onPrimary else ui.ink, selected).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(15), ui.dp(10), ui.dp(15), ui.dp(10))
                background = ui.touch(if (selected) ui.primary else ui.block, 16)
                isSelected = selected; contentDescription = "${group.name}${if (selected) "，当前代理组" else ""}"
                isFocusable = true; setOnClickListener { choose(group.name) }
            }
            chips.addView(chip, LayoutParams(-2, ui.dp(40)).apply { rightMargin = ui.dp(7) })
            if (selected) selectedChip = chip
        }
        strip.post { selectedChip?.let { strip.smoothScrollTo((it.left - ui.dp(12)).coerceAtLeast(0), 0) } }
    }

    private fun choose(name: String) {
        chosen = name; prefs.edit().putString(profileKey, name).apply()
        search.setText(""); renderGroups(); filter(); list.setSelection(0)
    }
    private fun showGroups() {
        if (catalog.groups.isEmpty()) return
        val groups = catalog.groups
        ui.choiceDialog("代理组", groups.map { it.name }, groups.map { "${groupType(it.type)} · ${it.members.size} 个节点" },
            groups.indexOfFirst { it.name == chosen }) { which -> choose(groups[which].name) }
    }
    private fun filter() {
        val group = group()
        val query = search.text.toString().trim()
        visibleNodes = group?.members?.filter { it.contains(query, true) }.orEmpty()
        summary.text = group?.name ?: "没有代理组"
        detail.text = group?.let { "${groupType(it.type)} · ${it.members.size} 个节点${if (it.selected.isNotEmpty()) " · 当前 ${it.selected}" else ""}" } ?: "请在配置中添加代理组"
        detail.maxLines = 2; detail.ellipsize = TextUtils.TruncateAt.END
        adapter.notifyDataSetChanged()
        if (visibleNodes.isEmpty()) showEmpty(if (query.isEmpty()) "暂无节点" else "没有匹配的节点", if (query.isEmpty()) "当前代理组为空" else "尝试其他名称")
    }
    private fun showEmpty(title: String, description: String) {
        empty.removeAllViews()
        empty.addView(ui.icon(Glyph.PROXIES, ui.muted, 36)); empty.addView(ui.gap(16))
        empty.addView(ui.text(title, 17f, ui.ink, true).apply { gravity = Gravity.CENTER }); empty.addView(ui.gap(8)); empty.addView(ui.text(description, 13f, ui.muted).apply { gravity = Gravity.CENTER })
    }
    private fun select(name: String) {
        val group = group() ?: return
        if (!group.type.equals("Selector", true) && !group.type.equals("Select", true)) {
            Toast.makeText(activity, "自动代理组由内核按延迟选择节点", Toast.LENGTH_SHORT).show(); return
        }
        if (group.selected == name) return
        network.execute {
            val result = runCatching { CoreApi.request("/proxies/${encode(group.name)}", "PUT", JSONObject().put("name", name)) }
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                result.onSuccess {
                    catalog = catalog.copy(groups = catalog.groups.map { if (it.name == group.name) it.copy(selected = name) else it })
                    filter()
                }.onFailure { Toast.makeText(activity, it.message ?: "选择失败", Toast.LENGTH_LONG).show() }
            }
        }
    }
    fun testCurrentGroup() { testNodes(group()?.members.orEmpty()) }
    private fun testNodes(names: List<String>) {
        if (pendingTests > 0 || closed) return
        val targets = names.distinct().filter { catalog.nodes[it]?.type?.equals("Reject", true) != true }
        if (targets.isEmpty()) return
        pendingTests = targets.size; onTesting(true)
        targets.forEach { delays[it] = "测速中" }
        adapter.notifyDataSetChanged()
        val url = group()?.testUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: "https://www.gstatic.com/generate_204"
        targets.forEach { name ->
            tests.execute {
                val measured = runCatching { CoreApi.request("/proxies/${encode(name)}/delay?timeout=5000&url=${encode(url)}").optLong("delay", -1) }
                activity.runOnUiThread {
                    if (closed) return@runOnUiThread
                    delays[name] = measured.getOrNull()?.takeIf { it > 0 }?.let { "$it ms" } ?: "超时"
                    pendingTests--; adapter.notifyDataSetChanged()
                    if (pendingTests == 0) onTesting(false)
                }
            }
        }
    }
    private fun groupType(type: String) = when (type.lowercase()) {
        "selector", "select" -> "手动选择"; "urltest", "url-test" -> "自动选择"; "fallback" -> "故障转移"; "loadbalance", "load-balance" -> "负载均衡"; else -> type
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private inner class NodeCell {
        val root = ui.column().apply {
            background = ui.touch(ui.card, 20); minimumHeight = ui.dp(98)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(13)); isFocusable = true
        }
        val title = ui.text("", 14f, ui.ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; minLines = 2 }
        val check = ui.icon(Glyph.CHECK, ui.onPrimary, 20).apply { background = ui.surface(ui.primary, 8); setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
        val type = ui.text("", 11f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val delay = ui.text("", 12f, ui.muted, true)
        init {
            root.addView(ui.row().apply {
                gravity = Gravity.TOP
                addView(title, LayoutParams(0, -2, 1f)); addView(check, LayoutParams(ui.dp(20), ui.dp(20)).apply { leftMargin = ui.dp(4) })
            })
            root.addView(ui.gap(10))
            root.addView(ui.row().apply { addView(type, LayoutParams(0, -2, 1f)); addView(delay) })
        }
        fun bind(name: String) {
            val node = catalog.nodes[name]
            val selected = group()?.selected == name
            title.text = name; check.visibility = if (selected) VISIBLE else INVISIBLE
            type.text = node?.type ?: "Proxy"
            val value = delays[name] ?: node?.delay?.let { "$it ms" } ?: if (node?.failed == true) "超时" else "—"
            delay.text = value
            val ms = value.removeSuffix(" ms").toLongOrNull()
            delay.setTextColor(when { value == "超时" -> ui.danger; ms == null -> ui.muted; ms < 300 -> ui.good; ms < 800 -> ui.warning; else -> ui.danger })
            root.isSelected = selected
            root.contentDescription = "$name，${node?.type.orEmpty()}${if (selected) "，当前选中" else ""}，$value；长按单独测速"
            root.setOnClickListener { select(name) }
            root.setOnLongClickListener { testNodes(listOf(name)); true }
        }
    }
    private inner class NodeRow {
        val root = ui.row().apply { gravity = Gravity.TOP; setPadding(0, ui.dp(5), 0, ui.dp(5)) }
        val cells = List(columns) { NodeCell() }
        init {
            cells.forEachIndexed { index, cell ->
                root.addView(cell.root, LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = ui.dp(10) })
            }
            root.tag = this
        }
    }
    private inner class NodesAdapter : BaseAdapter() {
        override fun getCount() = (visibleNodes.size + columns - 1) / columns
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? NodeRow) ?: NodeRow()
            holder.cells.forEachIndexed { index, cell ->
                val name = visibleNodes.getOrNull(position * columns + index)
                cell.root.visibility = if (name == null) INVISIBLE else VISIBLE
                if (name != null) cell.bind(name)
            }
            return holder.root
        }
    }
}

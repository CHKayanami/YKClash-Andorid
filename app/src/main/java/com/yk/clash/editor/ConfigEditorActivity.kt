package com.yk.clash.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yk.clash.*
import com.yk.clash.ui.AppUi
import com.yk.clash.ui.Glyph
import org.json.JSONObject
import java.util.concurrent.Executors

data class EditorRow(
    val title: String, val detail: String = "", val action: () -> Unit, val more: (() -> Unit)? = null,
    val groupId: String? = null, val header: Boolean = false, val depth: Int = 0, val actionLabel: String? = null,
    val toggleState: Boolean? = null,
)

fun filterEditorRows(rows: List<EditorRow>, query: String): List<EditorRow> {
    if (query.isBlank()) return rows
    val matching = rows.filter { it.title.contains(query, true) || it.detail.contains(query, true) }.toSet()
    val parents = matching.mapNotNull { it.groupId }.toSet()
    val wholeGroups = matching.filter { it.header }.mapNotNull { it.groupId }.toSet()
    return rows.filter { it in matching || (it.header && it.groupId in parents) || it.groupId in wholeGroups }
}

class ConfigEditorActivity : Activity() {
    internal lateinit var ui: AppUi
    internal lateinit var document: EditorDocument
    private var profile: ProfileStore.Profile? = null
    private lateinit var store: ProfileStore
    private lateinit var overrides: GlobalOverrides
    private lateinit var overrideStore: GlobalOverrideStore
    private lateinit var base: JSONObject
    private var original = ""
    private var nodeSnapshot = "[]"
    private var moduleScroll: HorizontalScrollView? = null
    private var section = 0
    private var dirty = false
    private var busy = true
    private var query = ""
    private var importTarget: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private data class Retained(val profile: ProfileStore.Profile?, val original: String, val document: EditorDocument, val overrides: GlobalOverrides, val base: JSONObject, val section: Int, val dirty: Boolean, val nodeSnapshot: String)
    private data class Loaded(val profile: ProfileStore.Profile?, val original: String, val document: EditorDocument, val overrides: GlobalOverrides, val base: JSONObject, val nodeSnapshot: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        ui = AppUi(this); setTheme(if (ui.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        importTarget = savedInstanceState?.getString("importTarget")
        store = ProfileStore(this)
        overrideStore = GlobalOverrideStore(this)
        val retained = lastNonConfigurationInstance as? Retained
        if (retained != null) {
            profile = retained.profile; original = retained.original; document = retained.document
            overrides = retained.overrides; base = retained.base
            section = retained.section; dirty = retained.dirty; nodeSnapshot = retained.nodeSnapshot; busy = false; render(); return
        }
        render()
        background({
            val chosen = store.selected()
            val raw = chosen?.let { store.read(it) } ?: "rules: ['MATCH,DIRECT']\n"
            val inherited = JSONObject(NativeCore.configDocument(raw))
            val saved = overrideStore.read()
            val state = overrideStore.load(saved)
            val nodes = com.yk.clash.nodes.GlobalNodeStore(this).read()
            Loaded(chosen, saved, EditorDocument(overrideStore.compose(inherited, state, forEditor = true, nodeSnapshot = nodes)), state, inherited, nodes)
        }, failure = { finish() }) {
            profile = it.profile; original = it.original; document = it.document
            overrides = it.overrides; base = it.base; nodeSnapshot = it.nodeSnapshot; busy = false; render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        importTarget?.let { outState.putString("importTarget", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onRetainNonConfigurationInstance(): Any? = if (!busy && ::document.isInitialized) Retained(profile, original, document, overrides, base, section, dirty, nodeSnapshot) else null
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    @Deprecated("Platform back callback")
    override fun onBackPressed() { leave() }

    private fun leave() {
        if (busy) { toast("请等待当前操作完成"); return }
        if (!dirty) { finish(); return }
        val dialog = AlertDialog.Builder(this).setTitle("放弃未保存的修改？").setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃") { _, _ -> finish() }.create()
        dialog.show(); ui.styleDialog(dialog)
    }

    internal fun changed() { overrides.capture(document.root); dirty = true; render() }
    internal fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun render() {
        if (isDestroyed || isFinishing) return
        val root = ui.column().apply { setBackgroundColor(ui.page); setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8)) }
        root.addView(ui.row().apply {
            addView(ui.backButton { leave() })
            addView(ui.text("全局覆写${if (dirty) " · 未保存" else ""}", 20f, ui.ink, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(12) })
            if (!busy) addView(ui.button("保存", true) { save() })
        })
        if (busy) {
            root.addView(ui.gap(24)); root.addView(ui.text("正在处理配置…", 15f, ui.muted)); setContentView(root); return
        }
        root.addView(ui.gap(10)); root.addView(ui.text(
            if (overrides.masterEnabled) "应用于所有配置 · 保存后重连生效\n可恢复内置默认配置，原始配置保持不变"
            else "入口总开关已关闭 · 模块设置和数据仍保留\n可继续编辑，重新开启总开关并重连后生效", 12f, ui.muted))
        root.addView(ui.button("查看最终 YAML") {
            if (dirty) toast("请先保存覆写，再查看最终 YAML")
            else startActivity(Intent(this, RuntimeYamlActivity::class.java))
        })
        val previousScroll = moduleScroll?.scrollX ?: 0
        val tabs = ui.row()
        var selectedTab: View? = null
        listOf("DNS2", "规则", "代理组", "嗅探", "规则提供者", "地理库").forEachIndexed { index, title ->
            val tab = ui.button(title, index == section) { section = index; query = ""; render() }
            if (index == section) selectedTab = tab
            tabs.addView(tab, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(7); topMargin = ui.dp(12); bottomMargin = ui.dp(10) })
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        moduleScroll = scroll
        root.addView(scroll)
        scroll.post {
            // Leave enough trailing space for even the last module to align to the left.
            val lastWidth = tabs.getChildAt(tabs.childCount - 1).width + ui.dp(7)
            tabs.setPadding(0, 0, (scroll.width - lastWidth).coerceAtLeast(0), 0)
            scroll.post {
                scroll.scrollTo(previousScroll, 0)
                scroll.smoothScrollTo(selectedTab?.left ?: 0, 0)
            }
        }
        val module = GlobalOverrides.modules[section]
        val enabled = module in overrides.enabled
        root.addView(ui.button(if (enabled) "本模块：全局覆写 · 点击跟随配置" else "本模块：跟随配置 · 点击启用覆写", enabled) {
            if (enabled) overrides.enabled.remove(module) else overrides.enabled.add(module)
            dirty = true; render()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        if (enabled) root.addView(ui.button("恢复本模块默认值") {
            val dialog = AlertDialog.Builder(this).setTitle("恢复 ${GlobalOverrides.labels[section]} 默认值？")
                .setNegativeButton("取消", null).setPositiveButton("恢复") { _, _ ->
                    overrides.reset(module); document = EditorDocument(overrideStore.compose(base, overrides, forEditor = true, nodeSnapshot = nodeSnapshot)); dirty = true; render()
                }.create()
            dialog.show(); ui.styleDialog(dialog)
        })
        root.addView(ui.text(if (enabled) "下面的数据将替换所选配置中的整个模块。" else "下面仅预览内置或已保存的覆写数据，当前不影响配置。", 12f, ui.muted))
        val rows = EditorPages(this).rows(section)
        var visible = filterEditorRows(rows, query)
        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(ui.page)
            dividerHeight = ui.dp(10)
            setBackgroundColor(ui.page)
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = visible[position]
                val content = ui.row().apply {
                    background = ui.surface(if (row.header) ui.block else ui.card, 20)
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(10), ui.dp(14))
                    if (row.depth > 0) addView(ui.text("↳", 18f, ui.muted), LinearLayout.LayoutParams(ui.dp(24), -2))
                    addView(ui.column().apply {
                        addView(ui.text(row.title, if (row.header) 17f else 15f, ui.ink, true).apply { maxLines = 2 })
                        if (row.detail.isNotEmpty()) { addView(ui.gap(6)); addView(ui.text(row.detail, 12f, ui.muted).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }) }
                        setOnClickListener { if (enabled) row.action() else toast("请先启用本模块的全局覆写") }; isClickable = true; isFocusable = true
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (row.toggleState != null) addView(ui.button(if (row.toggleState) "已开启" else "已关闭", row.toggleState) {
                        if (enabled) row.action() else toast("请先启用本模块的全局覆写")
                    }.apply { stateDescription = if (row.toggleState) "已开启" else "已关闭" })
                    else if (row.actionLabel != null) addView(ui.button(row.actionLabel, true) { if (enabled) row.action() else toast("请先启用本模块的全局覆写") })
                    else if (row.more != null) addView(ui.iconButton(Glyph.MORE, "${row.title}更多操作") { if (enabled) row.more.invoke() else toast("请先启用本模块的全局覆写") })
                    else addView(ui.iconButton(Glyph.CHEVRON, "打开${row.title}") { if (enabled) row.action() else toast("请先启用本模块的全局覆写") })
                    alpha = if (enabled) 1f else 0.6f
                }
                return FrameLayout(this@ConfigEditorActivity).apply {
                    setPadding(ui.dp(row.depth * 18), if (row.header) ui.dp(12) else 0, 0, 0)
                    addView(content, FrameLayout.LayoutParams(-1, -2))
                }
            }
        }
        list.adapter = adapter
        root.addView(ui.searchField("搜索当前模块", query) { text ->
            query = text; visible = filterEditorRows(rows, query); adapter.notifyDataSetChanged()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12); bottomMargin = ui.dp(10) })
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun save() {
        busy = true; render()
        background({
            overrides.masterEnabled = overrideStore.isEnabled()
            val chosen = store.selected()
            val currentBase = chosen?.let { JSONObject(NativeCore.configDocument(store.read(it))) } ?: base
            if (overrides.masterEnabled) {
                val effective = overrideStore.compose(currentBase, overrides)
                EditorDocument(effective).validateReferences()
                NativeCore.encodeConfig(effective.toString())
            }
            overrideStore.save(original, overrides)
        }, failure = { busy = false; render() }) { saved ->
            original = saved; dirty = false; busy = false; toast("全局覆写已保存，重新连接后生效"); render()
        }
    }

    internal fun perform(message: String, action: () -> String) {
        if (dirty) { toast("请先保存配置，再执行资源操作"); return }
        busy = true; render(); toast(message)
        background(action, failure = { busy = false; render() }) { result -> busy = false; toast(result); render() }
    }

    internal fun isActiveProfile(): Boolean = ClashVpnService.state == "已连接" && store.selected()?.id == profile?.id

    internal fun importResource(provider: String? = null) {
        importTarget = provider ?: ""
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 10)
    }

    @Deprecated("Platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 10 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val provider = importTarget ?: return
        importTarget = null
        busy = true; render()
        background({
            val relative = "imports/${java.util.UUID.randomUUID()}.data"
            val target = java.io.File(filesDir, relative)
            target.parentFile!!.mkdirs()
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val bytes = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val count = input.read(bytes); if (count < 0) break
                            total += count; require(total <= 64L * 1024 * 1024) { "资源文件超过 64 MB" }
                            output.write(bytes, 0, count)
                        }
                        require(total > 0) { "资源文件为空" }; output.fd.sync()
                    }
                } ?: error("无法读取文件")
                relative
            } catch (failure: Throwable) { target.delete(); throw failure }
        }, failure = { busy = false; render() }) { relative ->
            if (provider.isEmpty()) document.root.objectAt("dns2").arrayAt("hosts-files").put(relative)
            else document.root.objectAt("rule-providers").getJSONObject(provider).apply { put("type", "file"); put("path", relative) }
            busy = false; toast("文件已导入，保存并重新连接后生效"); changed()
        }
    }

    internal fun <T> background(task: () -> T, failure: () -> Unit = {}, done: (T) -> Unit) {
        worker.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) result.onSuccess(done).onFailure { toast(it.message ?: "操作失败"); failure() }
            }
        }
    }
}

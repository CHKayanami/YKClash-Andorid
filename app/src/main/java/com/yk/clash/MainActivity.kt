package com.yk.clash

import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.yk.clash.nodes.NodeActivity
import com.yk.clash.editor.GlobalOverrideStore
import com.yk.clash.editor.ConfigEditorActivity
import java.text.DateFormat
import java.util.Date
import android.provider.Settings
import android.net.Uri
import android.content.pm.PackageManager
import java.util.concurrent.Executor
import java.io.InputStream
import java.io.ByteArrayOutputStream
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.TextUtils
import com.yk.clash.ui.*
import org.json.JSONObject
import com.yk.clash.subscription.SubscriptionActivity
import com.yk.clash.subscription.SubscriptionRepository
import com.yk.clash.subscription.SubscriptionScheduler
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var ui: AppUi
    private val ink get() = ui.ink
    private val muted get() = ui.muted
    private val accent get() = ui.primary
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val subscriptionWorker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var store: ProfileStore
    private var page = 0
    private var shownState = ""
    private var shownError = ""
    private var shownProfilesRevision = -1L
    private var statusLabel: TextView? = null
    private var uploadLabel: TextView? = null
    private var downloadLabel: TextView? = null
    private var uploadTotalLabel: TextView? = null
    private var downloadTotalLabel: TextView? = null
    private var memoryLabel: TextView? = null
    private var proxyPage: ProxyPage? = null
    private var connectionsPage: ConnectionsPage? = null
    private var proxyTestButton: TextView? = null
    private var currentMode = "rule"
    private var pendingAppRoutingMode: String? = null
    private var bodyScroll: ScrollView? = null
    private var renderedPage = -1
    private val scrollOffsets = IntArray(5)
    private var memorySnapshot: MemorySnapshot? = null
    private val memoryBytes get() = memorySnapshot?.pss
    private var connectionLabel: TextView? = null
    private var previousUp = 0L
    private var previousDown = 0L
    private var previousTime = 0L
    private var polling = false
    private var preparingVpn = false
    private val poll = object : Runnable {
        override fun run() {
            if (shownState != ClashVpnService.state || shownError != ClashVpnService.error || page == 2 && shownProfilesRevision != ProfileStore.changes.get()) render()
            if (page == 0 && !polling) {
                polling = true
                val connected = ClashVpnService.state == "已连接"
                worker.execute {
                    val memory = ProcessMemory.snapshot()
                    val snapshot = if (connected) runCatching { CoreApi.request("/connections") }.getOrNull() else null
                    runOnUiThread {
                        polling = false
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        memorySnapshot = memory
                        memoryLabel?.text = memory.pss?.let(::bytes) ?: "—"
                        if (snapshot != null && ClashVpnService.state == "已连接") {
                            val up = snapshot.optLong("uploadTotal")
                            val down = snapshot.optLong("downloadTotal")
                            uploadTotalLabel?.text = "累计上传 ${bytes(up)}"
                            downloadTotalLabel?.text = "累计下载 ${bytes(down)}"
                            val now = SystemClock.elapsedRealtime()
                            val elapsed = ((now - previousTime) / 1000.0).coerceAtLeast(0.1)
                            if (previousTime != 0L) {
                                uploadLabel?.text = getString(R.string.transfer_rate, bytes(((up - previousUp).coerceAtLeast(0) / elapsed).toLong()))
                                downloadLabel?.text = getString(R.string.transfer_rate, bytes(((down - previousDown).coerceAtLeast(0) / elapsed).toLong()))
                            }
                            connectionLabel?.text = snapshot.optJSONArray("connections")?.length()?.toString() ?: "0"
                            previousUp = up; previousDown = down; previousTime = now
                        }
                    }
                }
            }
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (AppUi(this).dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        ui = AppUi(this)
        store = ProfileStore(this)
        store.ensureDefault()
        page = savedInstanceState?.getInt("page") ?: 0
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        render()
        worker.execute { SubscriptionScheduler.reconcile(applicationContext) }
        handleSubscriptionIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleSubscriptionIntent(intent) }
    private fun handleSubscriptionIntent(incoming: Intent) {
        if (incoming.action != Intent.ACTION_VIEW) return
        val uri = incoming.data ?: return
        if (uri.scheme !in listOf("clash", "ykclash") || uri.host != "install-config") return
        val address = uri.getQueryParameter("url")
        incoming.data = null
        if (address.isNullOrBlank()) { toast("导入链接缺少订阅地址"); return }
        startActivity(Intent(this, SubscriptionActivity::class.java).putExtra("url", address).putExtra("name", uri.getQueryParameter("name").orEmpty()))
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); connectionsPage?.setPaused(false); main.post(poll) }
    override fun onPause() { main.removeCallbacks(poll); connectionsPage?.setPaused(true); super.onPause() }
    override fun onDestroy() { proxyPage?.close(); connectionsPage?.close(); worker.shutdown(); subscriptionWorker.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = ui.dp(value)
    private fun text(value: String, size: Float = 16f, color: Int = ink) = ui.text(value, size, color)
    private fun column() = ui.column()
    private fun button(label: String, filled: Boolean = false, action: () -> Unit) = ui.button(label, filled, action).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun card(title: String, subtitle: String = "", tint: Int = ui.card, build: LinearLayout.() -> Unit = {}): LinearLayout {
        val panel = ui.panel().apply {
            background = ui.surface(tint)
            addView(ui.text(title, 17f, ink, true))
            if (subtitle.isNotEmpty()) { addView(ui.gap(8)); addView(ui.text(subtitle, 13f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }) }
            build()
        }
        body.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return panel
    }
    private fun navigate(index: Int) { page = index; render() }
    private fun render() {
        if (isFinishing || isDestroyed) return
        if (renderedPage >= 0) bodyScroll?.let { scrollOffsets[renderedPage] = it.scrollY }
        bodyScroll = null
        proxyPage?.close(); proxyPage = null; connectionsPage?.close(); connectionsPage = null
        proxyTestButton = null
        shownState = ClashVpnService.state
        shownError = ClashVpnService.error
        shownProfilesRevision = ProfileStore.changes.get()
        statusLabel = null; uploadLabel = null; downloadLabel = null; uploadTotalLabel = null; downloadTotalLabel = null; memoryLabel = null; connectionLabel = null
        if (shownState != "已连接") previousTime = 0
        val root = column().apply { setBackgroundColor(ui.page); isFocusableInTouchMode = true }
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        root.addView(toolbar())
        val connected = ClashVpnService.state == "已连接"
        val content: View = when {
            page == 1 && connected -> ProxyPage(this, ui, store) { testing ->
                proxyTestButton?.apply { text = if (testing) "测速中" else "测速"; isEnabled = !testing; alpha = if (testing) 0.6f else 1f }
            }.also { proxyPage = it }
            page == 3 && connected -> ConnectionsPage(this, ui).also { connectionsPage = it }
            else -> {
                body = column().apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
                ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false; clipToPadding = false; addView(body)
                    bodyScroll = this
                }
            }
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navigation(), LinearLayout.LayoutParams(-1, dp(90)))
        setContentView(root); root.requestFocus(); updateSystemBars()
        renderedPage = page
        when (page) {
            0 -> dashboard()
            1 -> if (!connected) requireConnected()
            2 -> profiles()
            3 -> if (!connected) requireConnected()
            4 -> settings()
        }
        bodyScroll?.let { scroll -> scroll.post { scroll.scrollTo(0, scrollOffsets[page]) } }
    }
    @Suppress("DEPRECATION")
    private fun updateSystemBars() {
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = ui.page; window.navigationBarColor = ui.page
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (ui.dark) 0 else mask, mask)
        } else {
            window.decorView.systemUiVisibility = if (ui.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
    }
    private fun toolbar(): View = ui.row().apply {
        setPadding(dp(22), dp(16), dp(16), dp(16))
        addView(ui.text(listOf("仪表盘", "代理", "配置", "连接", "设置")[page], 27f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        fun action(view: View) { addView(view, LinearLayout.LayoutParams(view.layoutParams?.width ?: -2, dp(44)).apply { leftMargin = dp(8) }) }
        when (page) {
            1 -> {
                action(ui.iconButton(Glyph.SEARCH, "搜索代理节点") { proxyPage?.toggleSearch() })
                proxyTestButton = ui.button("测速") { proxyPage?.testCurrentGroup() }.apply { minWidth = dp(60); setPadding(dp(14), 0, dp(14), 0) }
                action(proxyTestButton!!)
                action(ui.iconButton(Glyph.MORE, "代理操作") {
                    showMenu("代理", listOf("重新加载代理", "测试当前组延迟")) { if (it == 0) proxyPage?.reload() else proxyPage?.testCurrentGroup() }
                })
            }
            2 -> action(ui.iconButton(Glyph.PLUS, "添加配置", true) { addProfileMenu() })
            3 -> {
                action(ui.iconButton(Glyph.SEARCH, "搜索连接和规则集") { connectionsPage?.toggleSearch() })
                action(ui.iconButton(Glyph.MORE, "连接操作") {
                    showMenu("连接", listOf("立即刷新", "关闭全部连接")) { if (it == 0) connectionsPage?.reload() else connectionsPage?.closeAll() }
                })
            }
        }
    }
    private fun navigation(): View {
        val dock = ui.row().apply {
            background = ui.surface(ui.card, 28); elevation = dp(3).toFloat()
            setPadding(dp(5), dp(7), dp(5), dp(7))
        }
        val icons = listOf(Glyph.DASHBOARD, Glyph.PROXIES, Glyph.PROFILE, Glyph.CONNECTIONS, Glyph.SETTINGS)
        listOf("概览", "代理", "配置", "连接", "设置").forEachIndexed { index, label ->
            val selected = index == page
            val item = column().apply {
                gravity = Gravity.CENTER; minimumHeight = dp(56)
                val well = FrameLayout(this@MainActivity).apply {
                    if (selected) background = ui.surface(ui.block, 14)
                    addView(ui.icon(icons[index], if (selected) accent else muted, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }
                addView(well, LinearLayout.LayoutParams(dp(52), dp(30)))
                addView(ui.gap(4)); addView(ui.text(label, 12f, if (selected) accent else muted, selected).apply { gravity = Gravity.CENTER })
                isSelected = selected; isFocusable = true; contentDescription = label
                background = ui.touch(ui.card, 20); setOnClickListener { navigate(index) }
            }
            dock.addView(item, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        return FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(12)); addView(dock, FrameLayout.LayoutParams(-1, dp(70)))
        }
    }
    private fun dashboardNetworkSwitches() {
        val prefs = getSharedPreferences("network", MODE_PRIVATE)
        val panel = card("网络开关", "修改后重新连接生效；关闭 QUIC 将阻断经过内核的 UDP 443。")
        val row = ui.row()
        panel.addView(row)
        fun toggle(title: String, initial: Boolean?, save: (Boolean) -> Unit): TextView {
            var enabled = initial
            val button = ui.button("", initial == true) {
                enabled?.let { current ->
                    save(!current)
                    toast("重新连接后生效")
                    render()
                }
            }.apply { textSize = 13f; setPadding(dp(6), dp(10), dp(6), dp(10)) }
            fun paint(value: Boolean?) {
                enabled = value
                button.text = "$title\n${if (value == null) "读取中…" else if (value) "已开启" else "已关闭"}"
                button.isEnabled = value != null
                ui.paintButton(button, value == true)
            }
            paint(initial)
            row.addView(button, LinearLayout.LayoutParams(0, dp(68), 1f).apply { if (row.childCount > 0) leftMargin = dp(8) })
            button.tag = { value: Boolean -> paint(value) }
            return button
        }
        val ipv6Mode = prefs.getString("ipv6", "profile")
        val ipv6 = toggle("IPv6", if (ipv6Mode == "profile") null else ipv6Mode == "on") { prefs.edit().putString("ipv6", if (it) "on" else "off").apply() }
        val quic = toggle("QUIC", if (prefs.contains("quic")) prefs.getBoolean("quic", true) else null) { prefs.edit().putBoolean("quic", it).apply() }
        if (ipv6Mode == "profile" || !prefs.contains("quic")) {
            val selected = store.selected()
            async({
                runCatching { selected?.let { JSONObject(NativeCore.configDocument(store.read(it))) } }.getOrNull() ?: JSONObject()
            }) { config ->
                @Suppress("UNCHECKED_CAST")
                fun update(button: TextView, value: Boolean) { (button.tag as (Boolean) -> Unit)(value) }
                if (ipv6Mode == "profile") update(ipv6, config.optBoolean("ipv6", false))
                if (!prefs.contains("quic")) update(quic, config.optBoolean("quic", config.optBoolean("allow-quic", config.optBoolean("allow_quic", true))))
            }
        }
    }
    private fun dashboard() {
        val connected = ClashVpnService.state == "已连接"
        val service = ui.panel().apply {
            val info = column().apply {
                addView(ui.text("代理服务", 13f, muted)); addView(ui.gap(10))
                statusLabel = ui.text(if (shownState == "已断开") "未连接" else shownState, 26f, ink, true); addView(statusLabel)
                addView(ui.gap(8)); addView(ui.text(store.selected()?.name ?: "选择配置后开始使用", 13f, muted))
            }
            addView(ui.row().apply {
                addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.iconButton(Glyph.POWER, if (connected) "断开代理" else "启动代理", connected) { toggleVpn() }.apply {
                    isEnabled = !ClashVpnService.alwaysOnActive
                    alpha = if (ClashVpnService.alwaysOnActive) 0.5f else 1f
                    minimumHeight = dp(64); minimumWidth = dp(64)
                    setPadding(dp(19), dp(19), dp(19), dp(19)); background = ui.touch(if (connected) accent else ui.block, 24)
                }, LinearLayout.LayoutParams(dp(64), dp(64)))
            })
        }
        body.addView(service, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        if (ClashVpnService.error.isNotBlank()) card("服务提示", ClashVpnService.error).getChildAt(0).let { (it as TextView).setTextColor(ui.danger) }
        val traffic = ui.panel().apply {
            addView(ui.text("流量统计 · 本次连接", 14f, ink, true)); addView(ui.gap(18))
            addView(ui.row().apply {
                fun meter(label: String, glyph: Glyph): LinearLayout = column().apply {
                    addView(ui.row().apply { addView(ui.icon(glyph, muted, 16)); addView(ui.text(label, 12f, muted), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }) })
                    addView(ui.gap(10))
                }
                val up = meter("上传", Glyph.UPLOAD); uploadLabel = ui.text("0 B/s", 23f, ink, true); up.addView(uploadLabel)
                val down = meter("下载", Glyph.DOWNLOAD); downloadLabel = ui.text("0 B/s", 23f, ink, true); down.addView(downloadLabel)
                up.addView(ui.gap(8)); uploadTotalLabel = ui.text("累计上传 ${bytes(if (connected) previousUp else 0L)}", 12f, muted); up.addView(uploadTotalLabel)
                down.addView(ui.gap(8)); downloadTotalLabel = ui.text("累计下载 ${bytes(if (connected) previousDown else 0L)}", 12f, muted); down.addView(downloadTotalLabel)
                addView(up, LinearLayout.LayoutParams(0, -2, 1f)); addView(down, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        body.addView(traffic, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val metrics = ui.row().apply { gravity = Gravity.TOP }
        val memory = ui.panel().apply {
            addView(ui.row().apply { addView(ui.icon(Glyph.MEMORY, muted, 18)); addView(ui.text("内存占用", 12f, muted), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }) })
            addView(ui.gap(14)); memoryLabel = ui.text(memoryBytes?.let(::bytes) ?: "—", 23f, ink, true); addView(memoryLabel)
            addView(ui.gap(8)); addView(ui.text("应用与内核 · PSS", 10f, muted))
            background = ui.touch(ui.card, 24); setOnClickListener { memoryDetails() }
        }
        val connections = ui.panel().apply {
            addView(ui.row().apply { addView(ui.icon(Glyph.CONNECTIONS, muted, 18)); addView(ui.text("活动连接", 12f, muted), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }) })
            addView(ui.gap(14)); connectionLabel = ui.text("0", 23f, ink, true); addView(connectionLabel)
            addView(ui.gap(8)); addView(ui.text("查看命中规则与链路", 10f, muted))
            background = ui.touch(ui.card, 24); setOnClickListener { navigate(3) }
        }
        metrics.addView(memory, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        metrics.addView(connections, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        body.addView(metrics, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val modeCard = card("出站模式")
        val segment = ui.row().apply { background = ui.surface(ui.block, 17); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        fun paintModes() {
            segment.removeAllViews()
            listOf("规则" to "rule", "全局" to "global", "直连" to "direct").forEach { (label, mode) ->
                val selected = currentMode == mode
                segment.addView(ui.text(label, 13f, if (selected) accent else muted, selected).apply {
                    gravity = Gravity.CENTER; minHeight = dp(40); background = ui.touch(if (selected) ui.card else ui.block, 13)
                    isSelected = selected; isEnabled = connected; isFocusable = true; alpha = if (connected) 1f else 0.55f
                    setOnClickListener {
                        async({ CoreApi.request("/configs", "PATCH", JSONObject().put("mode", mode)) }) { currentMode = mode; paintModes() }
                    }
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            }
        }
        modeCard.addView(ui.gap(14)); modeCard.addView(segment); paintModes()
        dashboardNetworkSwitches()
        if (connected) async({ CoreApi.request("/configs").optString("mode", "rule") }) { mode -> if (page == 0) { currentMode = mode; paintModes() } }
        val profile = ui.row().apply {
            background = ui.touch(ui.card, 24); setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(ui.icon(Glyph.PROFILE, accent, 24))
            addView(column().apply { addView(ui.text("当前配置", 11f, muted)); addView(ui.gap(6)); addView(ui.text(store.selected()?.name ?: "尚未选择", 15f, ink, true)) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            addView(ui.icon(Glyph.CHEVRON, muted, 18)); setOnClickListener { navigate(2) }
        }
        body.addView(profile, LinearLayout.LayoutParams(-1, -2))
    }
    private fun memoryDetails() {
        val snapshot = memorySnapshot
        fun measured(value: Long?) = value?.let(::bytes) ?: "暂不可用"
        val detail = listOf(
            "进程 PSS：${measured(snapshot?.pss)}",
            "原生堆 PSS：${measured(snapshot?.nativeHeap)}",
            "Java 堆 PSS：${measured(snapshot?.javaHeap)}",
            "图形缓冲：${measured(snapshot?.graphics)}",
            "代码映射 PSS：${measured(snapshot?.code)}",
            "进程 RSS：${measured(snapshot?.rss)}",
        ).joinToString("\n")
        val dialog = AlertDialog.Builder(this).setTitle("内存占用").setMessage(detail).setPositiveButton("知道了", null).create()
        dialog.show(); ui.styleDialog(dialog)
    }
    private fun showMenu(title: String, choices: List<String>, action: (Int) -> Unit) {
        ui.choiceDialog(title, choices, emptyList(), -1, action)
    }
    private fun addProfileMenu() {
        showMenu("添加配置", listOf("添加远程订阅", "从文件导入", "粘贴 YAML")) {
            when (it) {
                0 -> subscription()
                1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 2)
                2 -> editProfile("新配置", "")
            }
        }
    }
    private fun toggleVpn() {
        if (ClashVpnService.alwaysOnActive) { toast("请在系统 VPN 设置中管理始终开启的连接"); return }
        if (ClashVpnService.state != "已断开") {
            startService(Intent(this, ClashVpnService::class.java).setAction(ClashVpnService.STOP)); return
        }
        if (store.selected() == null) { page = 2; render(); toast("请先导入配置"); return }
        startVpn()
    }
    private fun startVpn() {
        if (preparingVpn || ClashVpnService.state != "已断开" || !checkAppRoutingSelection()) return
        preparingVpn = true
        async({
            val profile = store.selected() ?: error("请先导入并选择配置")
            val merged = GlobalOverrideStore(this).resolve(store.read(profile))
            val mode = getSharedPreferences("network", MODE_PRIVATE).getString("ipv6", "profile") ?: "profile"
            val generated = JSONObject(NativeCore.networkConfig(merged, mode)).getString("config")
            val document = JSONObject(NativeCore.convertYamlMapping(generated, false))
            profile.id to NodeAvailability.hasNodes(document)
        }, onFailure = { preparingVpn = false }) { (profileId, hasNodes) ->
            preparingVpn = false
            if (ClashVpnService.state != "已断开") return@async
            if (store.selected()?.id != profileId) { toast("所选配置已变化，请重新连接"); return@async }
            if (!hasNodes) {
                ui.choiceDialog("当前配置没有代理节点", listOf("配置全局节点", "订阅配置"),
                    listOf("添加或导入节点，应用于所有配置", "选择已有订阅，或添加远程订阅获取节点"), -1) { which ->
                    if (which == 0) startActivity(Intent(this, NodeActivity::class.java))
                    else { page = 2; render() }
                }
                return@async
            }
            if (!checkAppRoutingSelection()) return@async
            val request = VpnService.prepare(this)
            if (request != null) {
                preparingVpn = true
                startActivityForResult(request, 1)
            } else startForegroundService(Intent(this, ClashVpnService::class.java).setAction(ClashVpnService.START))
        }
    }
    private fun checkAppRoutingSelection(): Boolean {
        val prefs = getSharedPreferences("network", MODE_PRIVATE)
        if (prefs.getString("appRoutingMode", "all") != "include") return true
        val selected = prefs.getStringSet("appRouting_include", emptySet()).orEmpty() - packageName
        if (selected.isNotEmpty()) return true
        val message = "已选择“仅代理所选应用”，请至少选择一个应用后再连接。"
        ClashVpnService.error = message
        render()
        val dialog = AlertDialog.Builder(this).setTitle("尚未选择应用").setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton("选择应用") { _, _ ->
                page = 4; render(); selectRoutingApps("include")
            }.create()
        dialog.show(); ui.styleDialog(dialog)
        return false
    }
    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) preparingVpn = false
        if (resultCode != RESULT_OK) return
        if (requestCode == 1) startVpn()
        if (requestCode == 4) {
            getSharedPreferences("network", MODE_PRIVATE).edit().putBoolean("startOnBoot", true).apply()
            toast("已开启开机自动连接"); render()
        }
        if (requestCode == 2 && data?.data != null) async({
            contentResolver.openInputStream(data.data!!)?.use { input ->
                val bytes = readLimited(input)
                require(bytes.size <= 8 * 1024 * 1024) { "配置超过 8 MB" }; bytes.toString(Charsets.UTF_8)
            } ?: error("无法读取文件")
        }) { source -> editProfile("本地配置", source) }
    }
    private fun profiles() {
        val profiles = store.list()
        card("全局节点", "添加到所有配置，独立于覆写总开关；同名节点优先使用全局节点，订阅更新不会覆盖。") {
            addView(button("管理全局节点") { startActivity(Intent(this@MainActivity, NodeActivity::class.java)) })
        }
        val globalOverrides = GlobalOverrideStore(this)
        val overridesEnabled = globalOverrides.isEnabled()
        card("全局配置覆写", "按模块选择 DNS2、规则、代理组、嗅探、规则提供者和地理库；应用于所有配置，不改写原文件。") {
            addView(button(if (overridesEnabled) "全局覆写 · 已开启" else "全局覆写 · 已关闭", overridesEnabled) {
                globalOverrides.setEnabled(!overridesEnabled)
                toast("已${if (overridesEnabled) "关闭" else "开启"}全局覆写，重新连接后生效")
                render()
            })
            addView(button("编辑全局覆写") { startActivity(Intent(this@MainActivity, ConfigEditorActivity::class.java)) })
            addView(button("查看最终 YAML") { startActivity(Intent(this@MainActivity, RuntimeYamlActivity::class.java)) })
        }
        if (profiles.any { it.url.isNotBlank() }) card("远程订阅") {
            addView(button("更新全部订阅") {
                toast("正在更新远程订阅")
                async({ SubscriptionRepository.updateAll(this@MainActivity) }, executor = subscriptionWorker) { result ->
                    toast("更新完成：成功 ${result.success}，失败 ${result.failed}"); render()
                }
            })
        }
        if (profiles.isEmpty()) {
            card("添加你的第一个配置", "导入订阅链接或本地 YAML 文件，开始连接。") { addView(button("添加配置") { addProfileMenu() }) }
        }
        profiles.forEach { profile ->
            val selected = store.selected()?.id == profile.id
            val panel = ui.panel()
            panel.addView(ui.row().apply {
                val well = FrameLayout(this@MainActivity).apply {
                    background = ui.surface(ui.block, 15)
                    addView(ui.icon(Glyph.PROFILE, accent, 23), FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
                }
                addView(well, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(column().apply {
                    addView(ui.text(profile.name, 17f, ink, true)); addView(ui.gap(7))
                    addView(ui.text(if (profile.url.isEmpty()) "本地配置" else "远程订阅", 12f, muted))
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(13) })
                if (selected) addView(ui.icon(Glyph.CHECK, accent, 22))
            })
            if (profile.url.isNotBlank()) {
                panel.addView(ui.gap(12))
                profile.info?.let { info ->
                    panel.addView(ui.text(info.summary(), 12f, if (info.expire in 1 until System.currentTimeMillis() / 1000) ui.danger else muted))
                    panel.addView(ui.gap(8))
                }
                val updated = if (profile.updatedAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(profile.updatedAt)) else "尚未更新"
                panel.addView(ui.text("$updated · ${if (profile.autoUpdate) "每 ${profile.intervalMinutes} 分钟自动更新" else "手动更新"}", 12f, muted))
                if (profile.lastError.isNotEmpty()) {
                    panel.addView(ui.gap(6)); panel.addView(ui.text(profile.lastError, 12f, ui.danger).apply { maxLines = 3 })
                }
            }
            panel.addView(ui.gap(18))
            panel.addView(ui.row().apply {
                fun action(label: String, filled: Boolean = false, description: String = label, click: () -> Unit): TextView {
                    val control = ui.button(label, filled, click).apply {
                        textSize = 13f; minWidth = 0
                        setPadding(dp(5), dp(8), dp(5), dp(8))
                        contentDescription = description
                    }
                    addView(control, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (childCount > 0) leftMargin = dp(6) })
                    return control
                }
                action(if (selected) "已选" else "使用", !selected, "使用配置 ${profile.name}") {
                    if (ClashVpnService.state != "已断开") toast("请先断开，再切换配置") else { store.select(profile); render() }
                }.apply { isEnabled = !selected; alpha = if (selected) 0.65f else 1f }
                action("节点", description = "节点配置 ${profile.name}") {
                    startActivity(Intent(this@MainActivity, NodeActivity::class.java).putExtra("profileId", profile.id))
                }
                action("YAML", description = "编辑 YAML ${profile.name}") {
                    async({ YamlDisplay.format(store.read(profile)) }) { source -> editProfile(profile.name, source, profile) }
                }
                action("删除", description = "删除配置 ${profile.name}") { deleteProfile(profile) }.setTextColor(ui.danger)
                if (profile.url.isNotBlank()) addView(ui.iconButton(Glyph.MORE, "${profile.name}的订阅操作") {
                    showMenu(profile.name, listOf("更新订阅", "订阅设置与详情")) { index ->
                        if (index == 0) {
                            toast("正在更新订阅")
                            async({ SubscriptionRepository.update(this@MainActivity, profile) }, executor = subscriptionWorker) {
                                toast("订阅已检查，重新连接后使用最新内容"); render()
                            }
                        } else startActivity(Intent(this@MainActivity, SubscriptionActivity::class.java).putExtra("profileId", profile.id))
                    }
                }, LinearLayout.LayoutParams(dp(36), dp(44)).apply { leftMargin = dp(6) })
            })
            body.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
    private fun deleteProfile(profile: ProfileStore.Profile) {
        val dialog = AlertDialog.Builder(this).setTitle("删除 ${profile.name}？").setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (ClashVpnService.state != "已断开") toast("请先断开连接") else { store.delete(profile); render() }
            }.create()
        dialog.show(); ui.styleDialog(dialog)
    }
    private fun editProfile(name: String, source: String, existing: ProfileStore.Profile? = null) {
        val fields = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        val title = EditText(this).apply { setText(name); hint = "配置名称"; setTextColor(ink); setHintTextColor(muted); background = ui.surface(ui.block, 16); setPadding(dp(14), dp(12), dp(14), dp(12)) }; fields.addView(title)
        val editor = EditText(this).apply {
            setText(source); hint = "粘贴 Clash YAML 配置"; minLines = 8; maxLines = 16
            setTextColor(ink); setHintTextColor(muted); background = ui.surface(ui.block, 16); setPadding(dp(14), dp(12), dp(14), dp(12))
            gravity = Gravity.TOP; typeface = Typeface.MONOSPACE; textSize = 12f
        }; fields.addView(editor, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val dialog = AlertDialog.Builder(this).setTitle("编辑配置").setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val label = title.text.toString()
            val sourceText = editor.text.toString()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            async({ store.save(label, existing?.url ?: "", sourceText, existing) },
                { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }) {
                dialog.dismiss(); render(); toast("配置已保存，连接时由内核校验")
            }
        } }; dialog.show(); ui.styleDialog(dialog)
    }
    private fun subscription() { startActivity(Intent(this, SubscriptionActivity::class.java)) }
    private fun settings() {
        val version = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "未知"
        card("YKClash", "版本 $version") {
            addView(ui.gap(12))
            addView(ui.text("项目地址", 12f, muted))
            addView(ui.text("https://github.com/CHKayanami/YKClash-Andorid", 13f, accent).apply {
                minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
                background = ui.touch(Color.TRANSPARENT, 12)
                isClickable = true; isFocusable = true
                contentDescription = "打开 GitHub 项目地址"
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/CHKayanami/YKClash-Andorid")))
                    }.onFailure { toast("无法打开项目地址，请检查浏览器是否可用") }
                }
            })
        }
        card("网络与服务") {
            fun setting(label: String, value: String) {
                addView(ui.gap(16))
                addView(ui.row().apply { addView(ui.text(label, 14f), LinearLayout.LayoutParams(0, -2, 1f)); addView(ui.text(value, 13f, muted)) })
            }
            setting("服务状态", ClashVpnService.state)
            setting("代理内核", "clash-rs")
            setting("流量接管", "Android VPN")
            setting("网络协议", if (ClashVpnService.state == "已连接") {
                if (ClashVpnService.ipv6Active) "IPv4 + IPv6" else "IPv4"
            } else "未连接")

        }
        val networkPrefs = getSharedPreferences("network", MODE_PRIVATE)
        val startOnBoot = networkPrefs.getBoolean("startOnBoot", false)
        card("启动与常驻", "开机自动连接使用已选配置，首次解锁后生效。始终开启 VPN 由 Android 系统管理，可在系统设置中启用。") {
            addView(button(if (startOnBoot) "开机自动连接 · 已开启" else "开机自动连接 · 未开启", startOnBoot) {
                if (startOnBoot) {
                    networkPrefs.edit().putBoolean("startOnBoot", false).apply(); render()
                } else if (store.selected() == null) {
                    toast("请先导入并选择配置")
                } else {
                    val permission = VpnService.prepare(this@MainActivity)
                    if (permission != null) startActivityForResult(permission, 4) else {
                        networkPrefs.edit().putBoolean("startOnBoot", true).apply(); render()
                    }
                }
            })
            addView(button("打开系统 VPN 设置") {
                runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                    .onFailure { toast("无法打开系统 VPN 设置") }
            })
            addView(ui.gap(10))
            addView(ui.text(if (ClashVpnService.alwaysOnActive) "系统始终开启 VPN 已生效；断开请在系统设置中操作。" else "可在 YKClash 的系统 VPN 选项中启用“始终开启 VPN”。", 12f, muted))
            addView(ui.gap(8))
            addView(ui.text("启用“阻止未使用 VPN 的连接”后，被排除应用的流量可能被系统阻断。", 12f, muted))
        }
        val appRoutingMode = networkPrefs.getString("appRoutingMode", "all") ?: "all"
        val appRoutingIndex = AppRoutingPolicy.modes.indexOf(appRoutingMode).coerceAtLeast(0)
        card("应用分流", "选择哪些应用进入 VPN；进入后的流量仍按内核规则处理。名单修改后重新连接生效。") {
            addView(button(AppRoutingPolicy.labels[appRoutingIndex], appRoutingMode != "all") {
                ui.choiceDialog("应用分流模式", AppRoutingPolicy.labels.toList(),
                    listOf("所有应用的流量进入 VPN", "只有选中的应用进入 VPN", "选中的应用直接使用系统网络"), appRoutingIndex) { which ->
                    networkPrefs.edit().putString("appRoutingMode", AppRoutingPolicy.modes[which]).apply()
                    toast("重新连接后生效"); render()
                }
            })
            if (appRoutingMode != "all") {
                val selected = networkPrefs.getStringSet("appRouting_$appRoutingMode", emptySet())!!.toSet()
                addView(button("选择应用 · ${selected.size} 项") {
                    selectRoutingApps(appRoutingMode)
                })
                addView(button("应用列表权限") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                })
            }
        }
        card("运行日志", "内核运行时持续保存日志，断开或重启应用后仍可查询；支持级别筛选、搜索和暂停显示。") {
            addView(button("查看累计日志") { startActivity(Intent(this@MainActivity, LogsActivity::class.java)) })
        }
        card("外观", "跟随系统，或单独选择浅色与深色主题。") {
            addView(button("主题设置") {
                val prefs = getSharedPreferences("interface", MODE_PRIVATE)
                val values = listOf("system", "light", "dark")
                val selected = values.indexOf(prefs.getString("theme", "system"))
                ui.choiceDialog("主题设置", listOf("跟随系统", "浅色", "深色"),
                    listOf("自动适应系统外观", "使用浅色背景", "使用深色背景"), selected) { which ->
                    prefs.edit().putString("theme", values[which]).apply(); recreate()
                }
            })
        }
        card("网络说明", "接管 IPv4；启用 IPv6 后同时接管 IPv6 流量。实际 IPv6 直连能力取决于当前网络，代理访问取决于节点支持。")
        if (ClashVpnService.error.isNotEmpty()) card("最近的服务提示", ClashVpnService.error)
    }
    private fun selectRoutingApps(mode: String) {
        if (AppRoutingDialog.needsPermission(this)) {
            pendingAppRoutingMode = mode
            requestPermissions(arrayOf(AppRoutingDialog.INSTALLED_APPS_PERMISSION), 5)
            return
        }
        val prefs = getSharedPreferences("network", MODE_PRIVATE)
        val selected = prefs.getStringSet("appRouting_$mode", emptySet())!!.toSet()
        toast("正在读取应用列表")
        async({ AppRoutingDialog.loadApps(this, selected) }) { apps ->
            AppRoutingDialog.show(this, ui, apps, selected) { chosen ->
                prefs.edit().putStringSet("appRouting_$mode", chosen).apply()
                toast("重新连接后生效"); render()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 5) {
            val mode = pendingAppRoutingMode
            pendingAppRoutingMode = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && mode != null) selectRoutingApps(mode)
            else toast("请允许读取应用列表，或点击“应用列表权限”前往系统设置开启")
        }
    }
    private fun requireConnected(): Boolean {
        if (ClashVpnService.state == "已连接") return true
        card("连接代理后查看", "请先导入配置并在仪表盘启动服务") { addView(button("前往仪表盘") { page = 0; render() }) }
        return false
    }
    private fun apiAction(action: () -> Unit) {
        if (ClashVpnService.state != "已连接") { toast("请先连接代理"); return }
        async({ action() }) { toast("已更新"); render() }
    }
    private fun <T> async(task: () -> T, onFailure: () -> Unit = {}, executor: Executor = worker, done: (T) -> Unit) {
        executor.execute {
            val result = runCatching(task)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) result.onSuccess { value ->
                    runCatching { done(value) }.onFailure { toast(it.message ?: "操作失败"); onFailure() }
                }.onFailure { toast(it.message ?: "操作失败"); onFailure() }
            }
        }
    }
    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= 8 * 1024 * 1024) {
            val count = input.read(buffer, 0, minOf(buffer.size, 8 * 1024 * 1024 + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun bytes(value: Long): String = when {
        value >= 1024 * 1024 -> "%.1f MB".format(value / 1048576.0)
        value >= 1024 -> "%.1f KB".format(value / 1024.0)
        else -> "$value B"
    }
}

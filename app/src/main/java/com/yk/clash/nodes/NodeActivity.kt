package com.yk.clash.nodes

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yk.clash.*
import com.yk.clash.editor.*
import com.yk.clash.ui.AppUi
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NodeActivity : Activity() {
    private lateinit var ui: AppUi
    private lateinit var store: ProfileStore
    private lateinit var session: NodeDrafts.Session
    private val root get() = session.root
    private val globalMode get() = !intent.hasExtra("profileId")
    private val globalStore by lazy { GlobalNodeStore(applicationContext) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var draftId: String
    private var busy = true
    private var rows = emptyList<JSONObject>()
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var saveButton: TextView
    private lateinit var adapter: BaseAdapter
    private var windowReady = false
    private val persistDraft = Runnable {
        if (::session.isInitialized) NodeDrafts.worker.execute { NodeDrafts.put(applicationContext, draftId, session) }
    }
    override fun onCreate(state: Bundle?) {
        ui = AppUi(this); setTheme(if (ui.dark) R.style.AppThemeDark else R.style.AppTheme)
        super.onCreate(state)
        store = ProfileStore(applicationContext)
        draftId = state?.getString("draftId") ?: UUID.randomUUID().toString()
        setContentView(ui.text("正在读取节点…").apply { setPadding(ui.dp(20), ui.dp(24), ui.dp(20), 0) })
        background({
            NodeDrafts.get(applicationContext, draftId) ?: run {
                val profile = if (globalMode) null else (store.list().firstOrNull { it.id == intent.getStringExtra("profileId") } ?: error("配置不存在"))
                val original = if (globalMode) globalStore.read() else "[]"
                val document = if (globalMode) JSONObject().put("proxies", JSONArray(original))
                    else JSONObject(NativeCore.convertYamlMapping(store.read(profile!!), false))
                NodeDrafts.Session(profile, document, original).also { NodeDrafts.put(applicationContext, draftId, it) }
            }
        }, failure = { finish() }) {
            session = it; render(); restoreForm()
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("draftId", draftId)
        if (::session.isInitialized) { main.removeCallbacks(persistDraft); persistDraft.run() }
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        main.removeCallbacks(persistDraft)
        if (isFinishing) NodeDrafts.worker.execute { NodeDrafts.remove(applicationContext, draftId) }
        super.onDestroy()
    }
    @Deprecated("Platform back callback")
    override fun onBackPressed() {
        if (busy) { toast("正在保存或读取，请稍候"); return }
        if (!session.dirty) finish() else dialog("放弃未保存的节点修改？") { finish() }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dialog(title: String, action: () -> Unit) {
        val d = AlertDialog.Builder(this).setTitle(title).setNegativeButton("取消", null).setPositiveButton("确定") { _, _ -> action() }.create()
        d.show(); ui.styleDialog(d)
    }
    private fun nodes() = root.arrayAt("proxies")
    private fun <T> background(action: () -> T, failure: () -> Unit = {}, done: (T) -> Unit) {
        busy = true
        updateStatus()
        NodeDrafts.worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                updateStatus()
                result.onSuccess(done).onFailure { toast(it.message ?: "操作失败"); failure(); if (windowReady) render() }
            }
        }
    }
    private fun changed(action: () -> Unit) {
        if (busy) return
        background({
            action(); session.dirty = true; session.form = null
            NodeDrafts.put(applicationContext, draftId, session)
        }, failure = { restoreForm() }) { render() }
    }
    private fun setForm(index: Int?, source: JSONObject, value: JSONObject?) {
        session.form = value?.let { JSONObject().put("index", index ?: -1).put("source", source).put("value", it) }
        main.removeCallbacks(persistDraft); main.postDelayed(persistDraft, 250)
    }
    private fun restoreForm() {
        session.form?.let { form -> edit(form.optInt("index", -1).takeIf { it >= 0 }, form.getJSONObject("source"), form.getJSONObject("value")) }
    }
    private fun render() {
        rows = nodes().objects()
        if (!windowReady) buildWindow()
        title.text = "${if (globalMode) "全局节点" else "节点配置"}${if (session.dirty) " · 未保存" else ""}"
        updateStatus()
        ui.paintButton(saveButton, session.dirty)
        adapter.notifyDataSetChanged()
    }
    private fun updateStatus() {
        if (windowReady) status.text = when {
            busy -> "正在处理…"
            rows.isEmpty() -> "暂无节点，添加节点或导入链接。"
            else -> "共 ${rows.size} 个节点"
        }
    }
    private class Row(val panel: LinearLayout, val name: TextView, val detail: TextView, val edit: TextView, val delete: TextView)
    private fun buildWindow() {
        val list = ListView(this).apply { setBackgroundColor(ui.page); divider = null; dividerHeight = ui.dp(10); setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16)); clipToPadding = false }
        val header = ui.column().apply { setPadding(0, ui.dp(16), 0, ui.dp(16)) }
        title = ui.text("", 23f, ui.ink, true)
        header.addView(ui.row().apply {
            addView(ui.backButton { onBackPressed() })
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(12) })
        }); header.addView(ui.gap(12))
        header.addView(ui.text(if (globalMode) "应用于所有配置，同名节点优先使用全局节点。订阅更新不会覆盖，重连后生效。" else "${session.profile!!.name} · 保存后重连生效，订阅更新会覆盖本地修改。", 13f, ui.muted)); header.addView(ui.gap(16))
        saveButton = ui.button("保存节点配置") {
            if (!busy) background({
                if (globalMode) session.original = globalStore.save(session.original, nodes())
                else session.profile = store.save(session.profile!!.name, session.profile!!.url, NativeCore.convertYamlMapping(root.toString(), true), session.profile)
                session.dirty = false; NodeDrafts.put(applicationContext, draftId, session)
            }) { toast("已保存，重新连接后生效"); render() }
        }
        header.addView(saveButton); header.addView(ui.gap(10))
        header.addView(ui.button("添加节点") { if (!busy) edit(null, JSONObject().put("type", "ss")) }); header.addView(ui.gap(10))
        header.addView(ui.button("从节点链接导入") {
            if (!busy) FormDialog.show(this, ui, "识别节点链接", JSONObject(), listOf(FormField("url", "节点链接（本地识别）", required = true))) {
                val imported = NodeLinks.inspect(it.getString("url"))
                if (imported.ignored.isEmpty()) edit(null, imported.node)
                else dialog("以下链接参数未完整支持：${imported.ignored.joinToString()}。请在高级 YAML 中补充后再保存。") { edit(null, imported.node) }
            }
        }); header.addView(ui.gap(18))
        status = ui.text("", 13f, ui.muted); header.addView(status)
        list.addHeaderView(header, null, false)
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val holder = convertView?.tag as? Row ?: run {
                    val panel = ui.panel(); val name = ui.text("", 17f, ui.ink, true); val detail = ui.text("", 13f, ui.muted)
                    panel.addView(name); panel.addView(ui.gap(8)); panel.addView(detail); panel.addView(ui.gap(12))
                    val edit = ui.button("编辑") {}; val delete = ui.button("删除") {}
                    panel.addView(ui.row().apply {
                        addView(edit, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
                        addView(delete, LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { leftMargin = ui.dp(8) })
                    })
                    Row(panel, name, detail, edit, delete).also { panel.tag = it }
                }
                val node = rows[position]
                holder.name.text = node.optString("name")
                holder.detail.text = "${node.optString("type").uppercase()} · ${node.optString("server")}:${node.optInt("port")}"
                holder.edit.setOnClickListener { if (!busy) edit(position, node) }
                holder.delete.setOnClickListener {
                    if (!busy) dialog("删除 ${node.optString("name")}？${if (globalMode) "保存时会检查其他配置和覆写中的引用。" else "当前配置的相关出站引用将改为直连。"}") {
                        changed {
                            val old = node.getString("name")
                            root.optJSONArray("proxy-groups")?.objects()?.forEach { group -> group.optJSONArray("proxies")?.let { it.replaceWith(it.values().filterNot { name -> name == old }) } }
                            renameReferences(old, "DIRECT"); renameDialer(old, "DIRECT"); nodes().remove(position)
                        }
                    }
                }
                return holder.panel
            }
        }
        list.adapter = adapter; setContentView(list); windowReady = true
    }
    private fun renameReferences(old: String, name: String) {
        if (old == name) return
        root.optJSONArray("proxy-groups")?.objects()?.forEach { group ->
            group.optJSONArray("proxies")?.let { it.replaceWith(it.values().map { v -> if (v == old) name else v }) }
            if (group.optString("empty-fallback") == old) group.put("empty-fallback", name)
        }
        root.optJSONArray("rules")?.let { rules -> rules.replaceWith(rules.values().map { raw ->
            val rule = runCatching { RoutingRule.parse(raw.toString()) }.getOrNull()
            if (rule?.target == old) rule.copy(target = name).encode() else raw
        }) }
        root.optJSONObject("dns2")?.optJSONArray("upstreams")?.objects()?.forEach { upstream -> for (key in listOf("proxy", "detour")) if (upstream.optString(key) == old) upstream.put(key, name) }
        for (key in listOf("rule-providers", "proxy-providers")) root.optJSONObject(key)?.let { providers -> providers.namesList().forEach { key -> providers.optJSONObject(key)?.let { if (it.optString("proxy") == old) it.put("proxy", name) } } }
    }
    private fun renameDialer(old: String, name: String) {
        nodes().objects().forEach { node -> for (key in listOf("connect-via", "dialer-proxy")) if (node.optString(key) == old) node.put(key, name) }
    }
    private fun advancedOnly(source: JSONObject, simple: Set<String>): JSONObject {
        val extra = source.copyObject()
        simple.forEach { extra.remove(it) }
        val type = source.optString("type")
        if (type == "shadowquic") ShadowQuicNode.mappedKeys.forEach { extra.remove(it) }
        if (type == "vmess") extra.remove("alter-id")
        if (type == "vless") {
            extra.remove("flow")
            extra.optJSONObject("reality-opts")?.let {
                it.remove("public-key"); it.remove("short-id"); it.remove("short_id")
                if (it.length() == 0) extra.remove("reality-opts")
            }
        }
        if (type !in listOf("ss", "ssh", "http", "socks5")) extra.remove("alpn")
        if (type in listOf("vmess", "vless", "trojan")) {
            extra.remove("network")
            val network = source.optString("network", "tcp")
            val key = "$network-opts"
            extra.optJSONObject(key)?.let { options ->
                when (network) {
                    "ws" -> {
                        options.remove("path")
                        options.optJSONObject("headers")?.let { headers ->
                            headers.remove("Host")
                            if (headers.length() == 0) options.remove("headers")
                        }
                    }
                    "xhttp" -> { options.remove("path"); options.remove("host") }
                    "grpc" -> options.remove("grpc-service-name")
                }
                if (options.length() == 0) extra.remove(key)
            }
        }
        return extra
    }
    private fun edit(index: Int?, original: JSONObject, restored: JSONObject? = null) {
        if (busy) return
        background({
            val source = original.copyObject()
            if (source.has("alter-id") && !source.has("alterId")) source.put("alterId", source.get("alter-id"))
            source.remove("alter-id")
            if (source.optString("type") in listOf("vmess", "vless") && source.has("server-name")) {
                if (!source.has("servername")) source.put("servername", source.get("server-name"))
                source.remove("server-name")
            }
            source.optJSONObject("reality-opts")?.let {
                if (it.has("short_id") && !it.has("short-id")) it.put("short-id", it.get("short_id"))
                it.remove("short_id")
            }
            source to (restored ?: prepare(source))
        }) { (source, value) -> setForm(index, source, value); showEditor(index, source, value) }
    }
    private val simple = setOf("name", "type", "server", "port", "username", "password", "uuid", "cipher", "alterId", "tls", "sni", "servername", "skip-cert-verify", "udp", "private-key", "client-fingerprint")
    private fun prepare(source: JSONObject): JSONObject {
        val value = source.copyObject()
        val extra = advancedOnly(source, simple)
        if (source.optString("type") == "shadowquic") ShadowQuicNode.prepare(value)
        if (source.has("alter-id") && !source.has("alterId")) value.put("alterId", source.get("alter-id"))
        value.put("_security", if (source.has("reality-opts")) "reality" else if (source.optBoolean("tls")) "tls" else "none")
        value.put("_network", source.optString("network", "tcp"))
        val transport = source.optJSONObject("${source.optString("network")}-opts")
        value.put("_path", transport?.optString("path").orEmpty())
        value.put("_host", transport?.optJSONObject("headers")?.optString("Host") ?: transport?.optString("host").orEmpty())
        value.put("_service", source.optJSONObject("grpc-opts")?.optString("grpc-service-name").orEmpty())
        value.put("_publicKey", source.optJSONObject("reality-opts")?.optString("public-key").orEmpty())
        value.put("_shortId", source.optJSONObject("reality-opts")?.optString("short-id").orEmpty())
        value.put("_flow", source.optString("flow"))
        value.put("_alpn", source.optJSONArray("alpn")?.values()?.joinToString(",").orEmpty())
        value.put("_advanced", if (extra.length() == 0) "" else NativeCore.convertYamlMapping(extra.toString(), true))
        return value
    }
    private fun showEditor(index: Int?, source: JSONObject, value: JSONObject) {
        fun text(key: String, label: String, required: Boolean = false) = FormField(key, label, required = required)
        fun flag(key: String, label: String) = FormField(key, label, FieldKind.BOOL)
        val fields = listOf(
            text("name", "节点名称", true),
            FormField("type", "出站协议", FieldKind.CHOICE, NodeLinks.types, "ss", true),
            text("server", "服务器域名或 IP", true), FormField("port", "端口", FieldKind.NUMBER, required = true),
            text("cipher", "SS 加密方式", true).copy(show = { it.optString("type") == "ss" }),
            text("uuid", "UUID", true).copy(show = { it.optString("type") in listOf("vmess", "vless", "tuic") }),
            FormField("alterId", "VMess Alter ID", FieldKind.NUMBER, default = 0, show = { it.optString("type") == "vmess" }),
            text("username", "用户名").copy(show = { it.optString("type") in listOf("http", "socks5", "ssh", "shadowquic") }),
            text("password", "密码 / 认证密钥").copy(trim = false, show = { it.optString("type") !in listOf("vmess", "vless") }),
            text("private-key", "SSH 私钥路径或 PEM 内容").copy(kind = FieldKind.MULTILINE, trim = false, show = { it.optString("type") == "ssh" }),
            text("_flow", "VLESS Flow（如 xtls-rprx-vision）").copy(show = { it.optString("type") == "vless" }),
            flag("udp", "允许 UDP"),
            *ShadowQuicNode.fields().toTypedArray(),
            FormField("_network", "传输方式", FieldKind.CHOICE, NodeLinks.networks(value.optString("type")), "tcp",
                show = { it.optString("type") in listOf("vmess", "vless", "trojan") }, section = "传输设置", choicesFor = { NodeLinks.networks(it.optString("type")) }),
            text("_path", "传输路径").copy(show = { it.optString("type") in listOf("vmess", "vless", "trojan") && it.optString("_network") in listOf("ws", "xhttp") }, section = "传输设置"),
            text("_host", "传输 Host").copy(show = { it.optString("type") in listOf("vmess", "vless", "trojan") && it.optString("_network") in listOf("ws", "xhttp") }, section = "传输设置"),
            text("_service", "gRPC Service Name").copy(show = { it.optString("type") in listOf("vmess", "vless", "trojan") && it.optString("_network") == "grpc" }, section = "传输设置"),
            FormField("_security", "连接安全（none / TLS / Reality）", FieldKind.CHOICE, listOf("none", "tls", "reality"), "none",
                show = { it.optString("type") == "vless" }, section = "TLS 与 Reality"),
            flag("tls", "启用 TLS").copy(show = { it.optString("type") in listOf("http", "socks5", "vmess") }, section = "TLS 设置"),
            text("sni", "TLS SNI").copy(show = { it.optString("type") !in listOf("vmess", "vless", "ss", "ssh", "shadowquic") }, section = "TLS 设置"),
            text("servername", "TLS Server Name / SNI").copy(show = { it.optString("type") == "vmess" || it.optString("type") == "vless" && it.optString("_security") != "none" }, section = "TLS 与 Reality"),
            text("client-fingerprint", "浏览器指纹（如 chrome、firefox、safari、random）").copy(show = {
                it.optString("type") in listOf("vmess", "trojan") || it.optString("type") == "vless" && it.optString("_security") != "none"
            }, section = "TLS 与 Reality"),
            text("_alpn", "ALPN（逗号分隔，如 h2,http/1.1）").copy(show = { it.optString("type") !in listOf("ss", "ssh", "http", "socks5") && (it.optString("type") != "vless" || it.optString("_security") != "none") }, section = "TLS 与 Reality"),
            flag("skip-cert-verify", "跳过证书验证").copy(show = { it.optString("type") !in listOf("ss", "ssh", "shadowquic") && (it.optString("type") != "vless" || it.optString("_security") != "none") }, section = "TLS 与 Reality"),
            text("_publicKey", "Reality 公钥", true).copy(show = { it.optString("type") == "vless" && it.optString("_security") == "reality" }, section = "TLS 与 Reality"),
            text("_shortId", "Reality Short ID").copy(show = { it.optString("type") == "vless" && it.optString("_security") == "reality" }, section = "TLS 与 Reality"),
            FormField("_advanced", "高级参数（YAML，仅填写表单之外的参数）", FieldKind.MULTILINE, section = "高级设置")
        ).map { if (it.section == null) it.copy(section = "基本信息") else it }
        FormDialog.show(this, ui, if (index == null) "添加节点" else "编辑节点", value, fields, onDraft = { setForm(index, source, it) }) { draft ->
            changed {
            val rawAdvanced = draft.optString("_advanced")
            val advanced = if (rawAdvanced.isBlank()) JSONObject() else JSONObject(NativeCore.convertYamlMapping(rawAdvanced, false))
            // The form owns its mapped fields, including nested transport and TLS options.
            val context = advanced.copyObject().put("type", draft.optString("type"))
            if (draft.optString("type") in listOf("vmess", "vless", "trojan")) context.put("network", draft.optString("_network", "tcp"))
            val node = advancedOnly(context, simple)
            draft.namesList().filter { it in simple }.forEach { node.put(it, draft.get(it)) }
            val type = node.optString("type")
            if (type == "shadowquic") ShadowQuicNode.apply(node, draft)
            if (type in listOf("vmess", "vless", "trojan")) {
                val network = draft.optString("_network", "tcp")
                node.put("network", network)
                if (network in listOf("ws", "xhttp")) {
                    val options = node.optJSONObject("$network-opts") ?: JSONObject()
                    options.put("path", draft.optString("_path").ifBlank { "/" })
                    val host = draft.optString("_host")
                    if (network == "ws") {
                        val headers = options.optJSONObject("headers") ?: JSONObject()
                        if (host.isBlank()) headers.remove("Host") else headers.put("Host", host)
                        options.put("headers", headers)
                    } else if (host.isBlank()) options.remove("host") else options.put("host", host)
                    node.put("$network-opts", options)
                }
                if (network == "grpc") node.put("grpc-opts", (node.optJSONObject("grpc-opts") ?: JSONObject()).put("grpc-service-name", draft.optString("_service")))
            }
            if (type == "vless") {
                val security = draft.optString("_security", "none")
                node.put("tls", security != "none")
                if (security == "reality") {
                    val key = draft.optString("_publicKey")
                    require(key.isNotBlank()) { "请填写 Reality 公钥" }
                    node.put("reality-opts", (node.optJSONObject("reality-opts") ?: JSONObject()).put("public-key", key).put("short-id", draft.optString("_shortId")))
                } else node.remove("reality-opts")
                if (draft.optString("_flow").isBlank()) node.remove("flow") else node.put("flow", draft.getString("_flow"))
            }
            val alpn = draft.optString("_alpn").split(',').map(String::trim).filter(String::isNotBlank)
            if (alpn.isEmpty()) node.remove("alpn") else node.put("alpn", JSONArray(alpn))
            NodeLinks.validate(node)
            val old = index?.let { nodes().getJSONObject(it).getString("name") }
            val name = node.getString("name")
            require(name !in listOf("DIRECT", "REJECT", "GLOBAL", "REJECT-DROP") && ',' !in name) { "节点名称包含保留名称或逗号" }
            require(nodes().objects().filterIndexed { i, _ -> i != index }.none { it.optString("name") == name } && root.optJSONArray("proxy-groups")?.objects().orEmpty().none { it.optString("name") == name }) { "节点或代理组名称重复" }
            if (old != null) { renameReferences(old, name); renameDialer(old, name); nodes().put(index!!, node) } else nodes().put(node)
            }
        }
    }
}

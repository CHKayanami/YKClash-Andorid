package com.yk.clash.editor

import android.app.AlertDialog
import com.yk.clash.CoreApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class EditorPages(private val host: ConfigEditorActivity) {
    private val root get() = host.document.root

    fun rows(section: Int): List<EditorRow> = when (section) {
        0 -> Dns2Page(host, this).rows()
        1 -> rules()
        2 -> groups()
        3 -> sniffer()
        4 -> providers()
        else -> GeodataPage(host).rows()
    }

    internal fun form(title: String, value: JSONObject, fields: List<FormField>, saved: (JSONObject) -> Unit) {
        FormDialog.show(host, host.ui, title, value, fields) { saved(it); host.changed() }
    }

    internal fun listRows(list: JSONArray, title: (Any) -> String, detail: (Any) -> String = { "" }, edit: (Int) -> Unit): List<EditorRow> =
        list.values().mapIndexed { index, item -> EditorRow("${index + 1}. ${title(item)}", detail(item), { edit(index) }, { operations(list, index) }) }

    private fun operations(list: JSONArray, index: Int) {
        val options = listOf("编辑顺序", "上移", "下移", "置顶", "删除")
        host.ui.choiceDialog("项目操作", options, emptyList(), -1) { action ->
            when (action) {
                0 -> FormDialog.show(host, host.ui, "移动到", JSONObject().put("position", index + 1), listOf(FormField("position", "位置（1–${list.length()}）", FieldKind.NUMBER, required = true))) { value ->
                    val target = value.getInt("position") - 1
                    require(target in 0 until list.length()) { "位置超出范围" }; move(list, index, target)
                }
                1 -> move(list, index, (index - 1).coerceAtLeast(0))
                2 -> move(list, index, (index + 1).coerceAtMost(list.length() - 1))
                3 -> move(list, index, 0)
                4 -> confirmDelete { list.remove(index); host.changed() }
            }
        }
    }

    private fun move(list: JSONArray, from: Int, to: Int) {
        val items = list.values().toMutableList(); val item = items.removeAt(from); items.add(to, item)
        list.replaceWith(items); host.changed()
    }

    internal fun confirmDelete(action: () -> Unit) {
        val dialog = AlertDialog.Builder(host).setTitle("删除此项目？").setMessage("仍有引用的项目需要先修改引用，保存时会检查。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> action() }.create()
        dialog.show(); host.ui.styleDialog(dialog)
    }

    private fun rules(): List<EditorRow> {
        val list = root.arrayAt("rules")
        fun edit(index: Int) {
            val existing = if (index < list.length()) RoutingRule.parse(list.getString(index)) else RoutingRule("DOMAIN-SUFFIX", "", "DIRECT", false)
            val value = JSONObject().put("type", existing.type).put("matcher", existing.matcher).put("target", existing.target).put("no-resolve", existing.noResolve)
            form("路由规则", value, EditorSchemas.rule(host.document.targets())) { result ->
                val type = result.getString("type")
                val supportsParams = type in listOf("GEOIP", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "RULE-SET")
                val rule = existing.copy(type = type, matcher = result.optString("matcher"), target = result.getString("target"), noResolve = supportsParams && result.optBoolean("no-resolve"))
                val encoded = rule.encode()
                if (index == list.length() && rule.type !in listOf("MATCH", "FINAL")) {
                    val values = list.values().toMutableList()
                    val fallback = values.indexOfFirst { RoutingRule.parse(it.toString()).type in listOf("MATCH", "FINAL") }
                    values.add(if (fallback < 0) values.size else fallback, encoded); list.replaceWith(values)
                } else list.put(index, encoded)
            }
        }
        return listOf(EditorRow("添加规则", "按从上到下的顺序匹配；新规则默认插入 MATCH 之前。", { edit(list.length()) })) +
            listRows(list, { it.toString() }, edit = ::edit)
    }

    private fun groups(): List<EditorRow> {
        val list = root.arrayAt("proxy-groups")
        fun edit(index: Int) {
            val old = list.optJSONObject(index) ?: JSONObject().put("type", "select").put("proxies", JSONArray(listOf("DIRECT")))
            form("代理组", old, EditorSchemas.group(host.document.targets())) { result ->
                val name = result.getString("name")
                require(list.objects().filterIndexed { i, _ -> i != index }.none { it.optString("name") == name }) { "代理组名称已存在" }
                val oldName = old.optString("name")
                list.put(index, result)
                if (oldName.isNotEmpty()) host.document.renameGroup(oldName, name)
            }
        }
        return listOf(EditorRow("添加代理组", "列表顺序即代理页面的显示顺序；重命名会同步更新规则和组内引用。", { edit(list.length()) })) +
            listRows(list, { (it as JSONObject).optString("name") }, { item ->
                (item as JSONObject).let { "${it.optString("type")} · ${it.optJSONArray("proxies")?.length() ?: 0} 个指定成员" }
            }, ::edit)
    }

    private fun sniffer(): List<EditorRow> {
        val sniffer = root.objectAt("sniffer")
        val rows = mutableListOf(EditorRow("嗅探基础设置", "启用、DNS 映射、纯 IP、域名覆盖、强制及跳过域名。", {
            form("嗅探基础设置", sniffer, EditorSchemas.sniffer) { root.put("sniffer", it) }
        }))
        for ((protocol, defaultPorts) in listOf("HTTP" to listOf("80", "8080-8880"), "TLS" to listOf("443", "8443"), "QUIC" to listOf("443"))) {
            val explicit = sniffer.optJSONObject("sniff")
            val legacy = sniffer.optJSONArray("sniffing")?.values()?.any { it.toString().equals(protocol, true) } == true
            val item = explicit?.optJSONObject(protocol) ?: explicit?.optJSONObject(protocol.lowercase())
            rows += EditorRow(protocol, item?.optJSONArray("ports")?.values()?.joinToString() ?: if (legacy) "使用默认端口" else "未单独配置", {
                val value = item?.copyObject() ?: JSONObject().put("ports", JSONArray(defaultPorts))
                form("$protocol 嗅探", value, EditorSchemas.sniffProtocol) { result ->
                    result.optJSONArray("ports")?.let { ports ->
                        result.put("ports", JSONArray(ports.values().map { port -> port.toString().toIntOrNull() ?: port.toString() }))
                    }
                    val sniff = sniffer.objectAt("sniff")
                    sniff.remove(protocol.lowercase()); sniff.put(protocol, result)
                }
            }, {
                confirmDelete {
                    explicit?.remove(protocol); explicit?.remove(protocol.lowercase())
                    sniffer.optJSONArray("sniffing")?.let { legacyList -> legacyList.replaceWith(legacyList.values().filterNot { it.toString().equals(protocol, true) }) }
                    host.changed()
                }
            })
        }
        return rows
    }

    private fun providers(): List<EditorRow> {
        val map = root.objectAt("rule-providers")
        fun edit(name: String?) {
            val old = name?.let { map.getJSONObject(it) } ?: JSONObject().put("type", "http")
            val value = old.copyObject().put("_name", name.orEmpty())
            if (value.has("inline-rules") && !value.has("payload")) value.put("payload", value.get("inline-rules"))
            value.remove("inline-rules")
            form("规则提供者", value, EditorSchemas.provider(host.document.targets())) { result ->
                val newName = result.getString("_name")
                require(name == newName || !map.has(newName)) { "规则提供者名称已存在" }
                result.remove("_name")
                if (result.optString("type") == "file") require(result.optString("path").isNotBlank()) { "文件类型必须填写路径" }
                if (result.optString("type") == "inline") {
                    if (!result.has("payload")) result.put("payload", JSONArray())
                    result.remove("format"); result.remove("interval")
                }
                if (name != null) { map.remove(name); host.document.renameProvider(name, newName) }
                map.put(newName, result)
            }
        }
        val rows = mutableListOf(EditorRow("添加规则提供者", "HTTP、文件或内置规则；支持 yaml、text、mrs 格式。", { edit(null) }))
        for (name in map.namesList()) {
            val provider = map.getJSONObject(name)
            rows += EditorRow(name, "${provider.optString("type")} · ${provider.optString("behavior")} · ${provider.optString("format", "yaml")}", { edit(name) }, {
                val choices = arrayOf("立即更新（当前运行配置）", "编辑 HTTP 请求头", "导入本地规则集文件", "删除")
                host.ui.choiceDialog(name, choices.toList(), emptyList(), -1) { index ->
                    when (index) {
                        0 -> {
                            if (!host.isActiveProfile()) host.toast("请先连接此配置")
                            else host.perform("正在更新规则提供者") {
                                val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                                CoreApi.request("/providers/rules/$encoded", "PUT")
                                "已提交规则提供者更新"
                            }
                        }
                        1 -> headers(provider)
                        2 -> host.importResource(name)
                        3 -> confirmDelete { map.remove(name); host.changed() }
                    }
                }
            })
        }
        return rows
    }

    private fun headers(provider: JSONObject) {
        val headers = provider.optJSONObject("header") ?: provider.optJSONObject("headers") ?: JSONObject()
        val lines = headers.namesList().flatMap { key ->
            val value = headers.get(key)
            (if (value is JSONArray) value.values() else listOf(value)).map { "$key: $it" }
        }
        form("HTTP 请求头", JSONObject().put("lines", JSONArray(lines)), listOf(FormField("lines", "每行 Header-Name: value，可填写 User-Agent", FieldKind.LINES))) { result ->
            val edited = JSONObject()
            result.optJSONArray("lines")?.values()?.forEach { line ->
                val text = line.toString(); val colon = text.indexOf(':')
                require(colon > 0) { "请求头格式应为 Header-Name: value" }
                edited.arrayAt(text.substring(0, colon).trim()).put(text.substring(colon + 1).trim())
            }
            provider.remove("headers"); provider.put("header", edited)
        }
    }
}

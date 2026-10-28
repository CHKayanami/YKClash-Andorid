package com.yk.clash.editor

import org.json.JSONArray
import org.json.JSONObject

class Dns2Page(private val host: ConfigEditorActivity, private val pages: EditorPages) {
    private val dns get() = host.document.root.objectAt("dns2")

    fun rows(): List<EditorRow> {
        val rows = mutableListOf(EditorRow("IPv6 解析", "默认开启；实际生效同时受首页 IPv6 开关控制。", {
            dns.put("ipv6", !dns.optBoolean("ipv6", true)); host.changed()
        }, toggleState = dns.optBoolean("ipv6", true)), EditorRow("基础、Hosts 文件与缓存", "使用 clash-rs DNS2；DNS 监听由 Android VPN 管理，旧 DNS 模块不参与解析。", {
            val value = dns.copyObject()
            if (value.has("bootstrap") && !value.has("default-nameserver")) value.put("default-nameserver", value.get("bootstrap"))
            value.remove("bootstrap")
            pages.form("DNS2 基础设置", value, EditorSchemas.dnsBasic) { result ->
                require(result.optLong("cache-capacity", 4096) > 0) { "缓存容量必须大于 0" }
                result.put("enable", true); host.document.root.put("dns2", result)
            }
        }))
        val upstreams = dns.arrayAt("upstreams")
        rows += EditorRow("DNS 上游 · ${upstreams.length()} 项", "国内 direct-dns 返回真实 IP；国外 proxy-dns 可选择 fakeip 或 remote。", { editUpstream(upstreams.length()) },
            groupId = "upstreams", header = true, actionLabel = "+ 添加")
        rows += pages.listRows(upstreams, { (it as JSONObject).optString("tag") }, { item ->
            val value = item as JSONObject
            val servers = value.opt("servers")
            "${value.optString("type")} · ${if (servers is JSONArray) servers.values().joinToString() else servers ?: value.optString("inet4-range") }"
        }, ::editUpstream).map { it.copy(groupId = "upstreams", depth = 1) }
        if (upstreams.length() == 0) rows += EditorRow("暂无上游", "点击组标题右侧添加 DNS 上游。", { editUpstream(0) }, groupId = "upstreams", depth = 1)
        for (kind in listOf("request", "response")) {
            val list = dns.objectAt("routing").arrayAt(kind)
            val label = if (kind == "request") "请求路由" else "响应路由"
            rows += EditorRow("$label · ${list.length()} 项", "匹配规则按顺序生效，兜底在所有匹配规则之后处理。", { editRule(kind, list.length()) },
                groupId = kind, header = true, actionLabel = "+ 添加")
            rows += pages.listRows(list, { item ->
                val rule = item as JSONObject
                "$label · ${rule.opt("fallback") ?: rule.opt("action") ?: rule.opt("upstream") ?: "accept"}"
            }, { item ->
                val rule = item as JSONObject
                listOf("domain" to "域名", "rule-set" to "规则集", "query-type" to "查询类型", "source-ip-cidr" to "来源", "ip-cidr" to "响应地址")
                    .mapNotNull { (key, label) -> rule.optJSONArray(key)?.let { "$label：${it.values().joinToString()}" } }
                    .joinToString(" · ").ifEmpty { "默认处理" }
            }, { editRule(kind, it) }).map { it.copy(groupId = kind, depth = 1) }
            if (list.length() == 0) rows += EditorRow("暂无$label", "点击组标题右侧添加。", { editRule(kind, 0) }, groupId = kind, depth = 1)
        }
        val files = dns.arrayAt("hosts-files")
        rows += EditorRow("Hosts 文件 · ${files.length()} 项", "导入后复制到应用私有目录。", { host.importResource() },
            groupId = "hosts-files", header = true, actionLabel = "导入")
        files.values().forEachIndexed { index, path ->
            rows += EditorRow(path.toString().substringAfterLast('/'), path.toString(), { host.toast(path.toString()) }, {
                pages.confirmDelete { files.remove(index); host.changed() }
            }, groupId = "hosts-files", depth = 1)
        }
        rows += hosts()
        return rows
    }

    private fun editUpstream(index: Int) {
        val upstreams = dns.arrayAt("upstreams")
        val old = upstreams.optJSONObject(index) ?: JSONObject().put("type", "remote")
        val value = old.copyObject()
        for ((alias, canonical) in listOf("server" to "servers", "address" to "servers", "detour" to "proxy")) {
            if (value.has(alias) && !value.has(canonical)) value.put(canonical, value.get(alias))
            value.remove(alias)
        }
        pages.form("DNS2 上游", value, EditorSchemas.upstream(host.document.targets())) { result ->
            val tag = result.getString("tag")
            require(upstreams.objects().filterIndexed { i, _ -> i != index }.none { it.optString("tag") == tag }) { "上游标识已存在" }
            val oldTag = old.optString("tag")
            upstreams.put(index, result)
            if (oldTag.isNotEmpty() && oldTag != tag) {
                val routing = dns.objectAt("routing")
                for (kind in listOf("request", "response")) routing.optJSONArray(kind)?.objects()?.forEach { rule ->
                    for (key in listOf("upstream", "server", "from-upstream", "from_upstream", "fallback", "to-upstream", "requery")) if (rule.optString(key) == oldTag) rule.put(key, tag)
                }
            }
        }
    }

    private fun editRule(kind: String, index: Int) {
        val list = dns.objectAt("routing").arrayAt(kind)
        val response = kind == "response"
        val value = list.optJSONObject(index)?.copyObject() ?: JSONObject()
        for ((alias, canonical) in listOf("rule_set" to "rule-set", "query_type" to "query-type", "source_ip_cidr" to "source-ip-cidr", "ip_cidr" to "ip-cidr")) {
            if (value.has(alias) && !value.has(canonical)) value.put(canonical, value.get(alias))
            value.remove(alias)
        }
        val fallback = value.has("fallback")
        val action = value.optString("action")
        value.put("_mode", if (fallback) "未匹配时兜底" else "匹配规则")
        value.put("_target", when {
            fallback -> when (val target = value.getString("fallback")) { "accept" -> "接受"; "reject" -> "拒绝"; else -> target }
            action == "reject" -> "拒绝"
            value.has("upstream") -> value.getString("upstream")
            value.has("server") -> value.getString("server")
            response -> "接受"
            else -> "direct-dns"
        })
        val tags = dns.arrayAt("upstreams").objects().map { it.optString("tag") }
        pages.form(if (response) "DNS2 响应路由" else "DNS2 请求路由", value, EditorSchemas.dnsRule(tags, response)) { result ->
            val isFallback = result.getString("_mode") == "未匹配时兜底"
            val target = result.getString("_target")
            result.remove("_mode"); result.remove("_target")
            for (key in listOf("action", "upstream", "server", "fallback", "to-upstream", "requery")) result.remove(key)
            if (isFallback) {
                require(response || target != "拒绝") { "请求兜底请选择一个上游；无兜底规则时默认拒绝" }
                require(list.objects().filterIndexed { i, _ -> i != index }.none { it.has("fallback") }) { "只能设置一条兜底规则" }
                for (key in listOf("domain", "rule-set", "query-type", "source-ip-cidr", "ip-cidr", "from-upstream", "invert")) result.remove(key)
                result.put("fallback", when (target) { "接受" -> "accept"; "拒绝" -> "reject"; else -> target })
            } else {
                val conditions = listOf("domain", "rule-set", "query-type", if (response) "ip-cidr" else "source-ip-cidr")
                require(conditions.any { (result.optJSONArray(it)?.length() ?: 0) > 0 } || (response && result.optString("from-upstream").isNotEmpty())) { "请至少填写一个匹配条件" }
                when (target) { "拒绝" -> result.put("action", "reject"); "接受" -> result.put("action", "accept"); else -> result.put("upstream", target) }
            }
            list.put(index, result)
        }
    }

    private fun hosts(): List<EditorRow> {
        val hosts = dns.objectAt("hosts")
        fun edit(name: String?) {
            val ips = name?.let { hosts.opt(it) }
            val value = JSONObject().put("name", name.orEmpty()).put("ips", if (ips is JSONArray) ips else JSONArray(listOfNotNull(ips)))
            pages.form("DNS2 Hosts", value, listOf(FormField("name", "域名", required = true), FormField("ips", "IP 地址，每行一项", FieldKind.LINES, required = true))) { result ->
                val domain = result.getString("name")
                require(name == domain || !hosts.has(domain)) { "Hosts 域名已存在" }
                if (name != null) hosts.remove(name)
                hosts.put(domain, result.getJSONArray("ips"))
            }
        }
        return listOf(EditorRow("Hosts · ${hosts.length()} 项", "手动映射域名到 IPv4 / IPv6 地址。", { edit(null) },
            groupId = "hosts", header = true, actionLabel = "+ 添加")) + hosts.namesList().map { name ->
            EditorRow(name, hosts.get(name).toString(), { edit(name) }, { pages.confirmDelete { hosts.remove(name); host.changed() } }, groupId = "hosts", depth = 1)
        }
    }
}

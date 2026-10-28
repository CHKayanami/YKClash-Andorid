package com.yk.clash.editor

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.objectAt(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }
fun JSONObject.arrayAt(key: String): JSONArray = optJSONArray(key) ?: JSONArray().also { put(key, it) }
fun JSONArray.values(): List<Any> = (0 until length()).map { get(it) }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.namesList(): List<String> = keys().asSequence().toList()
fun JSONObject.copyObject() = JSONObject(toString())
fun JSONArray.replaceWith(values: List<Any>) { while (length() > 0) remove(length() - 1); values.forEach { put(it) } }

data class RoutingRule(val type: String, val matcher: String, val target: String, val noResolve: Boolean,
    val parameters: List<String> = emptyList()) {
    fun encode(): String = listOf(type).plus(if (type == "MATCH" || type == "FINAL") emptyList() else listOf(matcher))
        .plus(target).plus(parameters).plus(if (noResolve) listOf("no-resolve") else emptyList()).joinToString(",")
    companion object {
        fun parse(raw: String): RoutingRule {
            val first = raw.indexOf(',')
            require(first > 0) { "规则格式无效" }
            val type = raw.substring(0, first).trim().uppercase()
            val rest = raw.substring(first + 1).trim()
            // Parenthesized child rules contain commas that do not delimit the target.
            val parts = mutableListOf<String>()
            val composite = type in listOf("AND", "OR", "NOT")
            var depth = 0
            var start = 0
            for ((index, char) in rest.withIndex()) {
                when (char) {
                    '(' -> if (composite) depth++
                    ')' -> if (composite) depth--
                    ',' -> if (depth == 0) { parts += rest.substring(start, index).trim(); start = index + 1 }
                }
                require(depth >= 0) { "规则括号不匹配" }
            }
            require(depth == 0) { "规则括号不匹配" }
            parts += rest.substring(start).trim()
            val fallback = type == "MATCH" || type == "FINAL"
            // Unquoted legacy regular expressions may contain commas themselves.
            if (type.endsWith("REGEX") && parts.size > 2) {
                return RoutingRule(type, parts.dropLast(1).joinToString(","), parts.last(), false)
            }
            val targetIndex = if (fallback) 0 else 1
            require(parts.size > targetIndex && parts.take(targetIndex + 1).none { it.isBlank() }) { "规则缺少匹配内容或出站" }
            val supportsParams = type in listOf("GEOIP", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "RULE-SET")
            val parameters = parts.drop(targetIndex + 1)
            val noResolve = supportsParams && parameters.any { it.equals("no-resolve", true) }
            return RoutingRule(type, if (fallback) "" else parts[0], parts[targetIndex], noResolve,
                parameters.filterNot { supportsParams && it.equals("no-resolve", true) })
        }
    }
}

class EditorDocument(val root: JSONObject) {
    fun targets(): List<String> = (listOf("DIRECT", "REJECT") +
        root.optJSONArray("proxy-groups")?.objects().orEmpty().map { it.getString("name") } +
        root.optJSONArray("proxies")?.objects().orEmpty().map { it.getString("name") }).distinct()

    fun renameGroup(old: String, name: String) {
        if (old == name) return
        root.optJSONArray("proxy-groups")?.objects()?.forEach { group ->
            group.optJSONArray("proxies")?.let { list -> list.replaceWith(list.values().map { if (it == old) name else it }) }
        }
        root.optJSONArray("rules")?.let { list -> list.replaceWith(list.values().map { raw ->
            val rule = RoutingRule.parse(raw.toString())
            if (rule.target == old) rule.copy(target = name).encode() else raw
        }) }
        root.optJSONObject("dns2")?.optJSONArray("upstreams")?.objects()?.forEach { upstream ->
            for (key in listOf("proxy", "detour")) if (upstream.optString(key) == old) upstream.put(key, name)
        }
        for (key in listOf("rule-providers", "proxy-providers")) root.optJSONObject(key)?.let { map ->
            map.namesList().forEach { provider -> map.optJSONObject(provider)?.let { if (it.optString("proxy") == old) it.put("proxy", name) } }
        }
    }

    fun renameProvider(old: String, name: String) {
        if (old == name) return
        root.optJSONArray("rules")?.let { list -> list.replaceWith(list.values().map { raw ->
            val rule = RoutingRule.parse(raw.toString())
            if (rule.type == "RULE-SET" && rule.matcher == old) rule.copy(matcher = name).encode() else raw
        }) }
        root.optJSONObject("dns2")?.optJSONObject("routing")?.let { routing ->
            for (kind in listOf("request", "response")) routing.optJSONArray(kind)?.objects()?.forEach { rule ->
                rule.optJSONArray("rule-set")?.let { list -> list.replaceWith(list.values().map {
                    when (it) { old -> name; "rule-set:$old" -> "rule-set:$name"; else -> it }
                }) }
            }
        }
    }

    fun validateReferences() {
        val groups = root.optJSONArray("proxy-groups")?.objects().orEmpty()
        val names = groups.map { it.getString("name") }
        require(names.size == names.toSet().size && names.none { it.isBlank() || it in setOf("DIRECT", "REJECT", "REJECT-DROP", "GLOBAL") }) { "代理组名称重复、为空或使用保留名称" }
        val targets = targets().toSet() + "GLOBAL"
        val providers = root.optJSONObject("rule-providers")?.namesList().orEmpty().toSet()
        root.optJSONArray("rules")?.values()?.forEach { raw ->
            val rule = RoutingRule.parse(raw.toString())
            require(rule.target in targets) { "规则引用了不存在的出站：${rule.target}" }
            if (rule.type == "RULE-SET") require(rule.matcher in providers) { "规则引用了不存在的规则提供者：${rule.matcher}" }
        }
        val proxyProviders = root.optJSONObject("proxy-providers")?.namesList().orEmpty()
        groups.forEach { group ->
            group.optJSONArray("proxies")?.values()?.forEach { require(it in targets && it != "GLOBAL") { "代理组 ${group.getString("name")} 引用了无效成员：$it" } }
            group.optJSONArray("use")?.values()?.forEach { require(it in proxyProviders) { "代理组引用了不存在的节点提供者：$it" } }
        }
        val edges = groups.associate { it.getString("name") to it.optJSONArray("proxies")?.values().orEmpty().map(Any::toString) }
        val visiting = mutableSetOf<String>(); val done = mutableSetOf<String>()
        fun visit(name: String) {
            if (name in done || name !in edges) return
            require(visiting.add(name)) { "代理组循环引用：$name" }
            edges.getValue(name).forEach(::visit)
            visiting.remove(name); done.add(name)
        }
        names.forEach(::visit)
        root.optJSONObject("dns2")?.optJSONObject("routing")?.let { routing ->
            for (kind in listOf("request", "response")) routing.optJSONArray(kind)?.objects()?.forEach { rule ->
                rule.optJSONArray("rule-set")?.values()?.forEach { require(it.toString().removePrefix("rule-set:") in providers) { "DNS2 引用了不存在的规则提供者：$it" } }
            }
        }
    }
}

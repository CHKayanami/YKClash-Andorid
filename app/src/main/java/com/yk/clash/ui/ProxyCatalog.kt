package com.yk.clash.ui

import org.json.JSONArray
import org.json.JSONObject

internal fun orderedGroupNames(configured: List<String>, available: Collection<String>): List<String> {
    val names = available.toSet()
    val ordered = configured.filter { it in names }.distinct()
    return ordered + names.filter { it !in ordered && it != "GLOBAL" }.sorted() +
        if ("GLOBAL" in names && "GLOBAL" !in ordered) listOf("GLOBAL") else emptyList()
}

internal fun JSONArray.stringValues(): List<String> = (0 until length()).map { getString(it) }
internal data class ProxyNode(val name: String, val type: String, val delay: Long?, val failed: Boolean)
internal data class ProxyGroup(val name: String, val type: String, val selected: String, val members: List<String>, val testUrl: String)
internal data class ProxyCatalog(val groups: List<ProxyGroup>, val nodes: Map<String, ProxyNode>) {
    companion object {
        fun parse(proxies: JSONObject, configured: List<String>): ProxyCatalog {
            val nodes = linkedMapOf<String, ProxyNode>()
            val groups = linkedMapOf<String, ProxyGroup>()
            proxies.keys().forEach { name ->
                val item = proxies.getJSONObject(name)
                val history = item.optJSONArray("history")
                val latest = if (history != null && history.length() > 0) history.optJSONObject(history.length() - 1) else null
                val delay = latest?.optLong("delay", -1)?.takeIf { it > 0 }
                nodes[name] = ProxyNode(name, item.optString("type", "Proxy"), delay, latest != null && delay == null)
                item.optJSONArray("all")?.let { members ->
                    groups[name] = ProxyGroup(name, item.optString("type"), item.optString("now"), members.stringValues(),
                        item.optString("testUrl", item.optString("test-url", "https://www.gstatic.com/generate_204")))
                }
            }
            return ProxyCatalog(orderedGroupNames(configured, groups.keys).map { groups.getValue(it) }, nodes)
        }
    }
}

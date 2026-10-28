package com.yk.clash.nodes

import com.yk.clash.editor.*
import org.json.JSONObject

/** Inspect references without interpreting or validating unrelated subscription fields. */
object NodeReferences {
    fun used(root: JSONObject): Set<String> = buildSet {
        root.optJSONArray("proxy-groups")?.objects()?.forEach { group -> group.optJSONArray("proxies")?.values()?.forEach { add(it.toString()) }; add(group.optString("empty-fallback")) }
        root.optJSONArray("rules")?.values()?.forEach { raw -> runCatching { RoutingRule.parse(raw.toString()) }.getOrNull()?.let { add(it.target) } }
        root.optJSONObject("dns2")?.optJSONArray("upstreams")?.objects()?.forEach { for (key in listOf("proxy", "detour")) add(it.optString(key)) }
        for (key in listOf("rule-providers", "proxy-providers")) root.optJSONObject(key)?.let { providers ->
            providers.namesList().forEach { providers.optJSONObject(it)?.let { add(it.optString("proxy")) } }
        }
        root.optJSONArray("proxies")?.objects()?.forEach { node ->
            for (key in listOf("connect-via", "dialer-proxy")) add(node.optString(key))
        }
        remove("")
    }
}

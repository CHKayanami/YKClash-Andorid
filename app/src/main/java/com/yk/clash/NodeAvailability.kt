package com.yk.clash

import org.json.JSONObject

/** Inspect the merged runtime document, including global nodes and provider sources. */
object NodeAvailability {
    fun hasNodes(root: JSONObject): Boolean {
        val proxies = root.optJSONArray("proxies")
        if (proxies != null && (0 until proxies.length()).any { index ->
            val node = proxies.optJSONObject(index)
            node != null && node.optString("type").isNotBlank() &&
                node.optString("type").lowercase() !in setOf("direct", "reject", "reject-drop", "pass", "dns")
        }) return true
        // Provider nodes can be fetched by the core; absence of inline proxies is valid.
        val providers = root.optJSONObject("proxy-providers") ?: return false
        return providers.keys().asSequence().any { providers.optJSONObject(it) != null }
    }
}

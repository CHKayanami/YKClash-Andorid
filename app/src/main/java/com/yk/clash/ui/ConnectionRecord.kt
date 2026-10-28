package com.yk.clash.ui

import org.json.JSONObject

internal data class MatchedRule(val label: String, val value: String)
internal fun matchedRule(type: String, payload: String): MatchedRule = when (type.replace("-", "").lowercase()) {
    "ruleset" -> MatchedRule("规则集", payload.ifBlank { "未提供名称" })
    "match", "final" -> MatchedRule("兜底规则", "MATCH")
    "" -> MatchedRule("规则", "模式路由")
    else -> MatchedRule(type, payload.ifBlank { "—" })
}

internal fun outboundChain(hops: List<String>) = hops.asReversed().joinToString(" → ")

internal data class ConnectionRecord(
    val id: String, val host: String, val port: String, val ip: String, val network: String,
    val source: String, val rule: MatchedRule, val chains: String, val upload: Long, val download: Long,
) {
    companion object {
        fun parse(item: JSONObject): ConnectionRecord {
            val meta = item.optJSONObject("metadata") ?: JSONObject()
            val destination = meta.optString("destinationIP")
            val chains = item.optJSONArray("chains")?.stringValues().orEmpty()
            return ConnectionRecord(item.optString("id"), meta.optString("host").ifBlank { destination.ifBlank { "未知目标" } },
                meta.optString("destinationPort"), destination, meta.optString("network").uppercase(),
                "${meta.optString("sourceIP")}:${meta.optString("sourcePort")}",
                matchedRule(item.optString("rule"), item.optString("rulePayload", item.optString("rule_payload"))),
                outboundChain(chains), item.optLong("upload"), item.optLong("download"))
        }
    }
}

internal fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 * 1024 -> "%.1f GB".format(value / 1073741824.0)
    value >= 1024 * 1024 -> "%.1f MB".format(value / 1048576.0)
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

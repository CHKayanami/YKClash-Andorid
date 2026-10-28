package com.yk.clash.subscription

import com.yk.clash.ui.formatBytes
import org.json.JSONObject
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date

data class SubscriptionInfo(val upload: Long = 0, val download: Long = 0, val total: Long = 0, val expire: Long = 0) {
    val used get() = if (Long.MAX_VALUE - upload < download) Long.MAX_VALUE else upload + download
    fun toJson() = JSONObject().put("upload", upload).put("download", download).put("total", total).put("expire", expire)
    fun summary(): String = buildList {
        add("已用 ${formatBytes(used)}" + if (total > 0) " / ${formatBytes(total)}" else " · 未提供总量")
        if (expire > 0 && expire <= Long.MAX_VALUE / 1000) {
            val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(expire * 1000))
            add(if (expire < System.currentTimeMillis() / 1000) "已到期 · $date" else "到期 $date")
        }
    }.joinToString(" · ")

    companion object {
        fun fromHeader(header: String?): SubscriptionInfo? {
            if (header.isNullOrBlank()) return null
            val values = header.split(';').mapNotNull { part ->
                val key = part.substringBefore('=', "").trim().lowercase()
                val number = runCatching { BigDecimal(part.substringAfter('=', "").trim()).toBigInteger().longValueExact() }.getOrNull()
                if (key.isBlank() || number == null || number < 0) null else key to number
            }.toMap()
            if (values.isEmpty()) return null
            return SubscriptionInfo(values["upload"] ?: 0, values["download"] ?: 0, values["total"] ?: 0, values["expire"] ?: 0)
        }
        fun fromJson(value: JSONObject?) = value?.let { SubscriptionInfo(it.optLong("upload"), it.optLong("download"), it.optLong("total"), it.optLong("expire")) }
    }
}

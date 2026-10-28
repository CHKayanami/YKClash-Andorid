package com.yk.clash.nodes

import android.net.Uri
import com.yk.clash.editor.FieldKind
import com.yk.clash.editor.FormField
import org.json.JSONObject

/** ShadowQUIC-specific fields use the same names and defaults as the core. */
object ShadowQuicNode {
    private val controls = listOf("bbr", "bbr3", "cubic", "new-reno", "brutal")
    private val scalarKeys = listOf("server-name", "initial-mtu", "min-mtu", "zero-rtt", "over-stream", "keep-alive-interval", "gso", "mtu-discovery")
    val mappedKeys = scalarKeys + "congestion-control"
    private val brutalDefaults = linkedMapOf<String, Any>(
        "bandwidth" to "10000000", "min-window" to 16384, "cwnd-gain" to 1.1,
        "min-ack-rate" to 0.8, "min-sample-count" to 50, "ack-compensate" to false,
    )
    val queryKeys = mappedKeys + listOf("sni", "servername") + brutalDefaults.keys.map { "brutal-$it" }

    fun fields(): List<FormField> {
        fun field(key: String, label: String, kind: FieldKind, default: Any? = null, required: Boolean = false) =
            FormField(key, label, kind, default = default, required = required,
                show = { it.optString("type") == "shadowquic" }, section = "ShadowQUIC 设置")
        return listOf(
            field("server-name", "服务器名称（与服务端 JLS 上游域名一致）", FieldKind.TEXT, required = true),
            field("_sqControl", "拥塞控制", FieldKind.CHOICE, "bbr").copy(choices = controls),
            field("initial-mtu", "初始 MTU", FieldKind.NUMBER, 1300),
            field("min-mtu", "最小 MTU（至少 1200）", FieldKind.NUMBER, 1290),
            field("zero-rtt", "启用 0-RTT", FieldKind.BOOL, true),
            field("over-stream", "通过 QUIC Stream 传输 UDP", FieldKind.BOOL, false),
            field("keep-alive-interval", "保活间隔（毫秒，0 关闭，建议小于 30000）", FieldKind.NUMBER, 0),
            field("gso", "启用 GSO", FieldKind.BOOL, true),
            field("mtu-discovery", "启用 MTU 探测", FieldKind.BOOL, true),
        ) + brutalDefaults.map { (key, default) ->
            val (label, kind) = when (key) {
                "bandwidth" -> "带宽（bit/s，可带 K、M、G 后缀）" to FieldKind.TEXT
                "min-window" -> "最小窗口（字节）" to FieldKind.NUMBER
                "cwnd-gain" -> "拥塞窗口增益" to FieldKind.DECIMAL
                "min-ack-rate" -> "最小 ACK 比率（0–1，不含 0）" to FieldKind.DECIMAL
                "min-sample-count" -> "最少采样数量" to FieldKind.NUMBER
                else -> "ACK 补偿" to FieldKind.BOOL
            }
            field("_sq_$key", label, kind, default).copy(section = "Brutal 参数", show = {
                it.optString("type") == "shadowquic" && it.optString("_sqControl", "bbr") == "brutal"
            })
        }
    }

    fun prepare(value: JSONObject) {
        val control = value.opt("congestion-control")
        value.put("_sqControl", if (control is JSONObject) "brutal" else (control as? String ?: "bbr"))
        val brutal = (control as? JSONObject)?.optJSONObject("brutal")
        brutalDefaults.forEach { (key, default) -> value.put("_sq_$key", brutal?.opt(key) ?: default) }
    }

    fun apply(node: JSONObject, draft: JSONObject) {
        scalarKeys.forEach { key -> if (draft.has(key)) node.put(key, draft.get(key)) }
        val control = draft.optString("_sqControl", "bbr")
        node.put("congestion-control", if (control == "brutal") JSONObject().put("brutal", JSONObject().apply {
            brutalDefaults.forEach { (key, default) -> put(key, draft.opt("_sq_$key") ?: default) }
        }) else control)
    }

    fun parseOptions(node: JSONObject, uri: Uri) {
        fun query(key: String) = uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }
        (query("server-name") ?: query("sni") ?: query("servername"))?.let { node.put("server-name", it) }
        for (key in listOf("initial-mtu", "min-mtu", "keep-alive-interval")) query(key)?.let {
            node.put(key, it.toLongOrNull() ?: error("ShadowQUIC $key 必须为整数"))
        }
        for (key in listOf("zero-rtt", "over-stream", "gso", "mtu-discovery")) query(key)?.let {
            node.put(key, when (it.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> error("ShadowQUIC $key 必须为 true 或 false")
            })
        }
        query("congestion-control")?.let { control ->
            if (control != "brutal") node.put("congestion-control", control)
            else {
                val draft = JSONObject().put("_sqControl", "brutal")
                brutalDefaults.forEach { (key, default) ->
                    val text = query("brutal-$key")
                    val value: Any = when {
                        text == null -> default
                        key == "bandwidth" -> text
                        default is Boolean -> when (text.lowercase()) {
                            "true", "1" -> true
                            "false", "0" -> false
                            else -> error("Brutal $key 必须为 true 或 false")
                        }
                        default is Double -> text.toDoubleOrNull() ?: error("Brutal $key 必须为数字")
                        else -> text.toLongOrNull() ?: error("Brutal $key 必须为整数")
                    }
                    draft.put("_sq_$key", value)
                }
                apply(node, draft)
            }
        }
    }

    fun validate(node: JSONObject) {
        require(node.optString("username").isNotBlank()) { "请填写 ShadowQUIC 用户名" }
        require(node.optString("password").isNotEmpty()) { "请填写 ShadowQUIC 密码" }
        require(node.optString("server-name").isNotBlank()) { "请填写 ShadowQUIC 服务器名称" }
        val initial = node.optLong("initial-mtu", 1300)
        val minimum = node.optLong("min-mtu", 1290)
        require(minimum in 1200..65535 && initial in minimum..65535) { "ShadowQUIC 需要初始 MTU ≥ 最小 MTU ≥ 1200，且不超过 65535" }
        require(node.optLong("keep-alive-interval", 0) in 0..4294967295L) { "ShadowQUIC 保活间隔超出范围" }
        val control = node.opt("congestion-control") ?: "bbr"
        if (control is JSONObject) {
            require(control.length() == 1 && control.optJSONObject("brutal") != null) { "不支持的 ShadowQUIC 拥塞控制配置" }
            val params = control.getJSONObject("brutal")
            val bandwidth = params.optString("bandwidth", "10000000")
            val suffix = bandwidth.lastOrNull()
            val multiplier = when (suffix?.lowercaseChar()) { 'k' -> 1024.0; 'm' -> 1048576.0; 'g' -> 1073741824.0; else -> 1.0 }
            val amount = bandwidth.trimEnd('k','K','m','M','g','G').toDoubleOrNull()?.times(multiplier)
            require(amount != null && amount.isFinite() && amount >= 0.5 && amount < 18446744073709551616.0) { "Brutal 带宽必须为有效的正数，可使用 K、M、G 后缀" }
            require(params.optLong("min-window", 16384) > 0 && params.optLong("min-sample-count", 50) > 0) { "Brutal 窗口与采样数量必须大于 0" }
            val gain = params.optDouble("cwnd-gain", 1.1)
            val rate = params.optDouble("min-ack-rate", 0.8)
            require(gain.isFinite() && gain > 0 && rate.isFinite() && rate > 0 && rate <= 1) { "Brutal 窗口增益须大于 0，ACK 比率须在 (0,1] 内" }
        } else require(control.toString() in controls - "brutal") { "不支持的 ShadowQUIC 拥塞控制" }
    }
}

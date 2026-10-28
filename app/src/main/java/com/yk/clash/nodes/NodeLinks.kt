package com.yk.clash.nodes

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Share links are decoded locally; no link is opened or downloaded. */
object NodeLinks {
    val types = listOf("ss", "socks5", "http", "anytls", "trojan", "vmess", "vless", "tuic", "hysteria2", "ssh", "shadowquic")
    fun networks(type: String) = when (type) {
        "trojan" -> listOf("tcp", "ws", "grpc")
        "vmess" -> listOf("tcp", "ws", "http", "h2", "grpc")
        "vless" -> listOf("tcp", "raw", "ws", "http", "h2", "grpc", "xhttp")
        else -> emptyList()
    }
    data class Import(val node: JSONObject, val ignored: List<String>)
    fun inspect(raw: String): Import {
        val node = parse(raw)
        if (raw.trim().substringBefore("://").equals("vmess", true)) {
            val fields = JSONObject(decode64(raw.trim().substringAfter("://")))
            val known = setOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "path", "host", "tls", "sni", "alpn", "fp")
            val ignored = fields.keys().asSequence().filter { key -> key !in known && (key != "type" || fields.optString(key) !in listOf("", "none")) }.toList()
            return Import(node, ignored)
        }
        val type = node.getString("type")
        if (type == "shadowquic") {
            val uri = Uri.parse(raw.trim())
            return Import(node, uri.queryParameterNames.filterNot { it in ShadowQuicNode.queryKeys || it == "alpn" })
        }
        val known = mutableSetOf("security", "tls", "sni", "peer", "servername", "allowInsecure", "insecure", "skip-cert-verify")
        if (type !in listOf("ss", "ssh", "http", "socks5")) known += "alpn"
        if (type in listOf("vmess", "vless", "trojan")) known += listOf("type", "path", "host", "serviceName", "fp")
        if (type == "vless") known += listOf("flow", "encryption", "pbk", "sid")
        if (type == "ss") known += "plugin"
        if (type == "hysteria2") known += listOf("obfs", "obfs-password", "mport")
        if (type == "tuic") known += listOf("congestion_control", "udp_relay_mode")
        if (node.optString("network") == "xhttp") known += listOf("mode", "xhttpMode")
        val uri = Uri.parse(raw.trim())
        return Import(node, uri.queryParameterNames.filterNot { it in known })
    }

    private fun decode64(value: String): String = String(Base64.decode(value.trim().replace('-', '+').replace('_', '/'), Base64.NO_WRAP), Charsets.UTF_8)
    private fun decode(value: String) = Uri.decode(value)
    fun parse(raw: String): JSONObject {
        val text = raw.trim()
        val scheme = text.substringBefore("://").lowercase()
        require("://" in text) { "请输入完整节点链接" }
        if (scheme == "vmess") {
            val v = JSONObject(decode64(text.substringAfter("://")))
            val n = JSONObject().put("type", "vmess").put("name", v.optString("ps").ifBlank { "VMess" })
                .put("server", v.getString("add")).put("port", v.getString("port").toInt()).put("uuid", v.getString("id"))
                .put("alterId", v.optString("aid", "0").toInt()).put("cipher", v.optString("scy", "auto"))
            n.put("tls", v.optString("tls") in listOf("tls", "true", "1"))
            if (v.optString("alpn").isNotBlank()) n.put("alpn", JSONArray(v.getString("alpn").split(',')))
            if (v.optString("fp").isNotBlank()) n.put("client-fingerprint", v.getString("fp"))
            if (v.optString("sni").isNotBlank()) n.put("servername", v.getString("sni"))
            val net = v.optString("net", "tcp")
            transport(n, net, v.optString("path"), v.optString("host"), v.optString("path"))
            return validate(n)
        }
        var body = text.substringAfter("://")
        if (scheme == "ss" && '@' !in body.substringBefore('#').substringBefore('?')) {
            val fragment = body.substringAfter('#', "")
            body = decode64(body.substringBefore('#').substringBefore('?')) + if (fragment.isBlank()) "" else "#$fragment"
        }
        val uri = Uri.parse("$scheme://$body")
        val type = when (scheme) { "sq" -> "shadowquic"; "hy2" -> "hysteria2"; "https" -> "http"; "socks", "socks5h" -> "socks5"; else -> scheme }
        require(type in types) { "当前内核不支持该链接协议" }
        val n = JSONObject().put("type", type).put("server", uri.host?.removePrefix("[")?.removeSuffix("]") ?: error("缺少服务器地址"))
            .put("port", if (uri.port > 0) uri.port else when (scheme) { "http" -> 80; "socks", "socks5", "socks5h" -> 1080; else -> 443 })
            .put("name", uri.fragment?.takeIf { it.isNotBlank() } ?: "$type · ${uri.host}")
        val auth = uri.encodedUserInfo.orEmpty()
        fun q(vararg keys: String): String? = keys.firstNotNullOfOrNull { uri.getQueryParameter(it)?.takeIf(String::isNotBlank) }
        when (type) {
            "ss" -> {
                val credentials = if (':' in auth) decode(auth) else decode64(decode(auth))
                require(':' in credentials) { "SS 链接缺少加密方式或密码" }
                n.put("cipher", credentials.substringBefore(':')).put("password", credentials.substringAfter(':'))
                q("plugin")?.let { plugin ->
                    val parts = plugin.split(';'); n.put("plugin", parts.first())
                    val opts = JSONObject()
                    parts.drop(1).forEach { opts.put(it.substringBefore('='), if ('=' in it) it.substringAfter('=') else true) }
                    n.put("plugin-opts", opts)
                }
            }
            "vmess", "vless" -> n.put("uuid", decode(auth))
            "trojan", "anytls", "hysteria2" -> n.put("password", decode(auth))
            "tuic" -> n.put("uuid", decode(auth.substringBefore(':'))).put("password", decode(auth.substringAfter(':', "")))
            else -> if (auth.isNotBlank()) n.put("username", decode(auth.substringBefore(':'))).put("password", decode(auth.substringAfter(':', "")))
        }
        if (type == "shadowquic") {
            ShadowQuicNode.parseOptions(n, uri)
            q("alpn")?.let { n.put("alpn", JSONArray(it.split(',').map(String::trim).filter(String::isNotEmpty))) }
            return validate(n)
        }
        val security = q("security")
        val tls = scheme == "https" || security in listOf("tls", "reality") || q("tls") == "1"
        if (type in listOf("http", "socks5", "vmess", "vless")) n.put("tls", tls)
        q("sni", "peer", "servername")?.let { n.put(if (type in listOf("vmess", "vless")) "servername" else "sni", it) }
        q("allowInsecure", "insecure", "skip-cert-verify")?.let { n.put("skip-cert-verify", it in listOf("1", "true")) }
        q("alpn")?.let { n.put("alpn", JSONArray(it.split(','))) }
        q("flow")?.let { n.put("flow", it) }
        q("encryption")?.let { n.put("encryption", it) }
        q("fp")?.let { n.put("client-fingerprint", it) }
        if (security == "reality") n.put("reality-opts", JSONObject().put("public-key", q("pbk") ?: error("Reality 缺少公钥")).apply { q("sid")?.let { put("short-id", it) } })
        if (type in listOf("vless", "trojan", "vmess")) transport(n, q("type") ?: "tcp", q("path").orEmpty(), q("host").orEmpty(), q("serviceName").orEmpty(), q("mode", "xhttpMode"))
        if (type == "hysteria2") {
            q("obfs")?.let { n.put("obfs", it) }; q("obfs-password")?.let { n.put("obfs-password", it) }
            q("mport")?.let { n.put("ports", it) }
        }
        if (type == "tuic") {
            q("congestion_control")?.let { n.put("congestion-controller", it) }
            q("udp_relay_mode")?.let { n.put("udp-relay-mode", it) }
        }
        return validate(n)
    }
    private fun transport(n: JSONObject, network: String, path: String, host: String, service: String, mode: String? = null) {
        require(network in networks(n.getString("type"))) { "不支持的传输方式：$network" }
        if (network == "tcp") return
        n.put("network", network)
        when (network) {
            "ws" -> n.put("ws-opts", JSONObject().put("path", path.ifBlank { "/" }).apply { if (host.isNotBlank()) put("headers", JSONObject().put("Host", host)) })
            "grpc" -> n.put("grpc-opts", JSONObject().put("grpc-service-name", service))
            "h2" -> n.put("h2-opts", JSONObject().put("path", path).put("host", JSONArray(host.split(',').filter(String::isNotBlank))))
            "http" -> n.put("http-opts", JSONObject().put("path", JSONArray(listOf(path.ifBlank { "/" }))).apply { if (host.isNotBlank()) put("headers", JSONObject().put("Host", JSONArray(listOf(host)))) })
            "xhttp" -> n.put("xhttp-opts", JSONObject().put("path", path).put("host", host).apply { mode?.let { put("mode", it) } })
        }
    }
    fun validate(n: JSONObject): JSONObject {
        require(n.optString("type") in types) { "不支持的出站协议" }
        require(n.optString("name").isNotBlank() && n.optString("server").isNotBlank()) { "请填写节点名称和服务器" }
        require(n.optInt("port") in 1..65535) { "端口必须为 1–65535" }
        when (n.getString("type")) {
            "ss" -> require(n.optString("cipher").isNotBlank() && n.has("password")) { "请填写加密方式和密码" }
            "vmess", "vless", "tuic" -> java.util.UUID.fromString(n.getString("uuid"))
            "trojan", "anytls", "hysteria2" -> require(n.optString("password").isNotBlank()) { "请填写密码" }
            "shadowquic" -> ShadowQuicNode.validate(n)
            "ssh" -> require(n.optString("username").isNotBlank()) { "请填写 SSH 用户名" }
        }
        val type = n.getString("type")
        if (type in listOf("vmess", "vless", "trojan")) {
            val network = n.optString("network", "tcp")
            require(network in networks(type)) { "$type 不支持 $network 传输" }
            if (network !in listOf("tcp", "raw")) require(n.optJSONObject("$network-opts") != null) { "请填写 $network 传输的高级参数" }
            if (network == "xhttp") require(n.getJSONObject("xhttp-opts").optString("mode", "auto") in listOf("", "auto", "packet-up", "stream-up", "stream-one")) { "不支持的 XHTTP 模式" }
        }
        if (type == "tuic") require(n.optString("password").isNotEmpty()) { "请填写 TUIC 密码" }
        if (type == "vless" && n.optString("flow").isNotEmpty()) {
            require(n.optString("flow") in listOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")) { "不支持的 VLESS Flow" }
            require(n.optBoolean("tls") || n.has("reality-opts")) { "Vision Flow 需要开启 TLS 或 Reality" }
            val encrypted = n.optString("encryption").let { it.isNotBlank() && it != "none" }
            require(n.optString("network", "tcp") in listOf("tcp", "raw") || encrypted) { "非 TCP 传输使用 Vision 时需要 VLESS Encryption" }
        }
        return n
    }
}

package com.yk.clash.editor

import org.json.JSONObject

object EditorSchemas {
    private fun text(key: String, label: String, required: Boolean = false, default: Any? = null) = FormField(key, label, required = required, default = default)
    private fun lines(key: String, label: String, required: Boolean = false) = FormField(key, label, FieldKind.LINES, required = required)
    private fun number(key: String, label: String, default: Any? = null) = FormField(key, label, FieldKind.NUMBER, default = default)
    private fun flag(key: String, label: String, default: Any? = null) = FormField(key, label, FieldKind.BOOL, default = default)
    private fun choice(key: String, label: String, values: List<String>, default: Any? = null, required: Boolean = false) = FormField(key, label, FieldKind.CHOICE, values, default, required)

    val dnsBasic = listOf(
        flag("use-hosts", "使用 Hosts", true),
        lines("default-nameserver", "Bootstrap DNS（使用 IP 地址）"),
        lines("proxy-server-nameserver", "代理服务器域名解析 DNS"), lines("hosts-files", "额外 Hosts 文件（应用私有目录相对路径）"),
        number("optimistic-cache-ttl", "乐观缓存 TTL（秒，0 跟随上游）", 0),
        number("stale-cache-retention", "过期缓存保留（秒）", 3600), number("cache-capacity", "缓存容量", 4096),
    )

    fun upstream(targets: List<String>) = listOf(
        text("tag", "上游标识", true), choice("type", "解析方式", listOf("remote", "fakeip", "local"), "remote", true),
        lines("servers", "DNS 服务器（每行一项）", true).copy(show = { it.optString("type", "remote") == "remote" }),
        choice("proxy", "出站（未设置为直连）", targets).copy(show = { it.optString("type", "remote") == "remote" }),
        text("client-subnet", "EDNS Client Subnet（IP 或 CIDR）").copy(show = { it.optString("type", "remote") == "remote" }),
        text("inet4-range", "Fake-IP IPv4 网段", true, "198.18.0.1/16").copy(show = { it.optString("type") == "fakeip" }),
        text("inet6-range", "Fake-IP IPv6 网段", true, "fc00::/18").copy(show = { it.optString("type") == "fakeip" }),
        number("ttl", "Fake-IP TTL（秒）", 1).copy(show = { it.optString("type") == "fakeip" }),
    )

    fun dnsRule(tags: List<String>, response: Boolean): List<FormField> = buildList {
        add(choice("_mode", "规则用途", listOf("匹配规则", "未匹配时兜底"), "匹配规则", true))
        val matching: (JSONObject) -> Boolean = { it.optString("_mode") != "未匹配时兜底" }
        if (response) add(choice("from-upstream", "仅匹配来自指定上游的响应", tags).copy(show = matching))
        add(lines("domain", "域名匹配（example.com 精确，+.example.com 包含子域）").copy(show = matching))
        add(lines("rule-set", "规则集名称").copy(show = matching))
        add(lines("query-type", "查询类型（A、AAAA、HTTPS 等）").copy(show = matching))
        add(lines(if (response) "ip-cidr" else "source-ip-cidr", if (response) "响应 IP / CIDR" else "来源 IP / CIDR").copy(show = matching))
        add(flag("invert", "反向匹配", false).copy(show = matching))
        add(choice("_target", "处理方式", tags + if (response) listOf("接受", "拒绝") else listOf("拒绝"), tags.firstOrNull(), true))
        if (!response) add(choice("reject-code", "拒绝响应", listOf("nxdomain", "nodata", "refused"), "nxdomain").copy(show = { it.optString("_target") == "拒绝" }))
    }

    fun group(targets: List<String>) = listOf(
        text("name", "代理组名称", true), choice("type", "代理组类型", listOf("select", "url-test", "fallback", "load-balance", "smart"), "select", true),
        lines("proxies", "成员名称（节点或代理组，每行一项）"), lines("use", "节点提供者名称（每行一项）"),
        flag("include-all", "包含全部节点"), text("filter", "成员筛选正则"), choice("empty-fallback", "无可用成员时出站", targets),
        text("url", "测速 URL").copy(show = { it.optString("type") != "select" }),
        number("interval", "测速间隔（秒）", 300).copy(show = { it.optString("type") in listOf("url-test", "fallback", "load-balance") }),
        number("tolerance", "切换容差（毫秒）", 50).copy(show = { it.optString("type") == "url-test" }),
        flag("lazy", "按需测速", true).copy(show = { it.optString("type") != "select" }),
        choice("strategy", "负载均衡策略", listOf("consistent-hashing", "round-robin", "sticky-session"), "consistent-hashing").copy(show = { it.optString("type") == "load-balance" }),
        number("max-retries", "最大重试次数", 3).copy(show = { it.optString("type") == "smart" }),
        FormField("site-stickiness", "站点粘性（0–1）", FieldKind.DECIMAL, default = 0.8, show = { it.optString("type") == "smart" }),
        FormField("bandwidth-weight", "带宽权重", FieldKind.DECIMAL, default = 0.0, show = { it.optString("type") == "smart" }),
        flag("udp", "允许 UDP").copy(show = { it.optString("type") in listOf("select", "smart") }), text("icon", "图标地址"),
    )

    fun rule(targets: List<String>) = listOf(
        choice("type", "匹配类型", listOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "GEOSITE", "GEOIP", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "DST-PORT", "SRC-PORT", "NETWORK", "RULE-SET", "MATCH", "FINAL", "AND", "OR", "NOT"), "DOMAIN-SUFFIX", true),
        text("matcher", "匹配内容（规则集填名称）", true).copy(show = { it.optString("type") !in listOf("MATCH", "FINAL") }),
        choice("target", "出站节点或代理组", targets + "GLOBAL", "DIRECT", true),
        flag("no-resolve", "不触发 DNS 解析", false).copy(show = { it.optString("type") in listOf("GEOIP", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "RULE-SET") }),
    )

    fun provider(targets: List<String>) = listOf(
        text("_name", "规则提供者名称", true), choice("type", "来源类型", listOf("http", "file", "inline"), "http", true),
        text("url", "下载 URL", true).copy(show = { it.optString("type", "http") == "http" }),
        text("path", "缓存 / 文件路径（应用私有目录相对路径）").copy(show = { it.optString("type") != "inline" }),
        choice("format", "文件格式", listOf("yaml", "text", "mrs"), "yaml").copy(show = { it.optString("type") != "inline" }),
        choice("behavior", "规则内容类型", listOf("domain", "ipcidr", "classical"), "domain", true),
        number("interval", "更新间隔（秒，0 禁用）", 86400).copy(show = { it.optString("type") != "inline" }),
        choice("proxy", "下载使用的出站", targets).copy(show = { it.optString("type") == "http" }),
        lines("payload", "内置规则（每行一项）").copy(show = { it.optString("type") == "inline" }),
    )

    val sniffer = listOf(flag("enable", "启用嗅探", true), flag("force-dns-mapping", "嗅探真实 IP 的 DNS 映射", true),
        flag("parse-pure-ip", "嗅探纯 IP 连接", true), flag("override-destination", "使用嗅探域名覆盖目标", false),
        lines("force-domain", "强制嗅探域名"), lines("skip-domain", "跳过嗅探域名"))
    val sniffProtocol = listOf(lines("ports", "嗅探端口（单端口或范围，如 8080-8880）"), flag("override-destination", "覆盖目标地址", false))

    val geodata = listOf(
        text("mmdb", "GeoIP 文件路径", default = "Country.mmdb"),
        text("mmdb-download-url", "GeoIP 更新链接", default = "https://cdn.jsdelivr.net/gh/DustinWin/ruleset_geodata@mihomo-geodata/Country.mmdb"),
        text("geosite", "GeoSite 文件路径", default = "geosite.dat"),
        text("geosite-download-url", "GeoSite 更新链接", default = "https://cdn.jsdelivr.net/gh/DustinWin/ruleset_geodata@mihomo-geodata/geosite-lite.dat"),
        text("asn-mmdb", "ASN 文件路径", default = "Country-ASN.mmdb"),
        text("asn-mmdb-download-url", "ASN 更新链接", default = "https://cdn.jsdelivr.net/gh/DustinWin/ruleset_geodata@mihomo-geodata/Country-ASN.mmdb"),
    )
}

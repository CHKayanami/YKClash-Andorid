package com.yk.clash.download

import com.yk.clash.ClashVpnService
import com.yk.clash.CoreApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

/** Explicit proxying is required because the app UID is excluded from VPN routing. */
object DownloadRouting {
    fun select(mode: String = "auto"): Proxy {
        require(mode in listOf("auto", "direct", "proxy")) { "无效的下载方式" }
        if (mode == "direct") return Proxy.NO_PROXY
        if (ClashVpnService.state != "已连接") {
            check(mode != "proxy") { "代理下载需要先连接代理" }
            return Proxy.NO_PROXY
        }
        val config = runCatching { CoreApi.request("/configs") }.getOrNull()
        val http = config?.optInt("mixed-port")?.takeIf { it in 1..65535 }
            ?: config?.optInt("port")?.takeIf { it in 1..65535 }
        val socks = config?.optInt("socks-port")?.takeIf { it in 1..65535 }
        if (http != null) return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", http))
        if (socks != null) return Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks))
        check(mode != "proxy") { "当前内核没有可用的本地 HTTP / SOCKS 代理端口" }
        return Proxy.NO_PROXY
    }

    fun <T> run(mode: String = "auto", cancelled: () -> Boolean = { false }, attempt: (Proxy) -> T): T {
        check(!cancelled()) { "下载已取消" }
        val proxy = select(mode)
        try {
            return attempt(proxy)
        } catch (proxied: IOException) {
            if (mode != "auto" || proxy == Proxy.NO_PROXY || cancelled()) throw proxied
            try {
                return attempt(Proxy.NO_PROXY)
            } catch (direct: Exception) {
                direct.addSuppressed(proxied)
                throw direct
            }
        }
    }
}

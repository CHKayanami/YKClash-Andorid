package com.yk.clash

import android.net.IpPrefix
import android.net.InetAddresses
import com.yk.clash.editor.objects
import java.security.MessageDigest
import org.json.JSONObject
import java.net.InetAddress

/** Exclusions belong to VpnService, so bypassed traffic never enters the core. */
object VpnRoutePolicy {
    // VpnService rejects loopback route destinations, including exclusions.
    // Loopback stays on the system local route and must not be submitted here.
    private val defaultIpv4 = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
    )
    private val defaultIpv6 = listOf("::/128", "fc00::/7", "fe80::/10", "ff00::/8")
    private fun prefix(raw: String): RoutePrefix {
        val parts = raw.split('/')
        require(parts.size == 2) { "无效的 VPN 网段：$raw" }
        val parsed = IpPrefix(InetAddresses.parseNumericAddress(parts[0]), parts[1].toInt())
        return RoutePrefix(parsed.address, parsed.prefixLength)
    }

    private val cache = object : LinkedHashMap<String, List<RoutePrefix>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RoutePrefix>>?) = size > 4
    }
    fun exclusions(dns2: JSONObject?, ipv6: Boolean): List<RoutePrefix> {
        val protected = mutableListOf(prefix("172.19.0.0/30"))
        if (ipv6) protected += prefix("fdfe:dcba:9876::/126")
        // Use the effective DNS2 ranges, including custom ranges and multiple Fake-IP upstreams.
        dns2?.optJSONArray("upstreams")?.objects()?.forEach { upstream ->
            if (upstream.optString("type") == "fakeip") {
                protected += prefix(upstream.optString("inet4-range", "198.18.0.1/16"))
                if (ipv6) protected += prefix(upstream.optString("inet6-range", "fc00::/18"))
            }
        }
        val defaults = (defaultIpv4 + if (ipv6) defaultIpv6 else emptyList()).map(::prefix)
        // Carve protected networks out of exclusions instead of relying on route insertion order.
        val digest = MessageDigest.getInstance("SHA-256")
        fun hash(routes: List<RoutePrefix>) { routes.forEach { digest.update(it.address.address.size.toByte()); digest.update(it.address.address); digest.update(it.length.toByte()) } }
        digest.update(if (ipv6) 1.toByte() else 0.toByte()); hash(protected)
        val key = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        synchronized(cache) { cache[key]?.let { return it } }
        val compact = RouteNetworks.compact(defaults)
        val result = RouteNetworks.compact(protected.fold(compact) { routes, keep -> routes.flatMap { subtract(it, keep) } })
            .filterNot { it.address.isLoopbackAddress }
        synchronized(cache) { cache[key] = result }
        return result
    }

    private fun subtract(route: RoutePrefix, keep: RoutePrefix): List<RoutePrefix> {
        if (route.address.address.size != keep.address.address.size) return listOf(route)
        if (keep.length <= route.length) return if (keep.contains(route.address)) emptyList() else listOf(route)
        if (!route.contains(keep.address)) return listOf(route)
        val childLength = route.length + 1
        val left = RoutePrefix(route.address, childLength)
        val rightBytes = route.address.address.copyOf()
        val index = route.length / 8
        rightBytes[index] = (rightBytes[index].toInt() or (1 shl (7 - route.length % 8))).toByte()
        val right = RoutePrefix(InetAddress.getByAddress(rightBytes), childLength)
        return subtract(left, keep) + subtract(right, keep)
    }
}

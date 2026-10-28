package com.yk.clash

import java.net.InetAddress

data class RoutePrefix(val address: InetAddress, val length: Int) {
    fun contains(other: InetAddress): Boolean {
        val network = address.address
        val candidate = other.address
        if (network.size != candidate.size) return false
        return network.indices.all { index ->
            val bits = (length - index * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            (network[index].toInt() and mask) == (candidate[index].toInt() and mask)
        }
    }
}

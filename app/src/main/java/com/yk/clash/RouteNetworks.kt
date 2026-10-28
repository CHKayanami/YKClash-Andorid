package com.yk.clash

import java.net.InetAddress

/** A prefix trie removes contained networks and combines adjacent sibling prefixes. */
internal object RouteNetworks {
    private class Node(var covered: Boolean = false, var zero: Node? = null, var one: Node? = null)
    fun compact(routes: List<RoutePrefix>): List<RoutePrefix> {
        val roots = mapOf(4 to Node(), 16 to Node())
        for (route in routes) {
            val bytes = route.address.address
            var node = roots.getValue(bytes.size)
            for (depth in 0 until route.length) {
                if (node.covered) break
                val bit = (bytes[depth / 8].toInt() ushr (7 - depth % 8)) and 1
                node = if (bit == 0) node.zero ?: Node().also { node.zero = it } else node.one ?: Node().also { node.one = it }
            }
            node.covered = true; node.zero = null; node.one = null
        }
        fun collapse(node: Node): Boolean {
            if (node.covered) return true
            val zero = node.zero?.let(::collapse) ?: false
            val one = node.one?.let(::collapse) ?: false
            if (zero && one) { node.covered = true; node.zero = null; node.one = null }
            return node.covered
        }
        val result = mutableListOf<RoutePrefix>()
        fun emit(node: Node, bytes: ByteArray, depth: Int) {
            if (node.covered) { result += RoutePrefix(InetAddress.getByAddress(bytes), depth); return }
            node.zero?.let { emit(it, bytes, depth + 1) }
            node.one?.let {
                val right = bytes.copyOf()
                right[depth / 8] = (right[depth / 8].toInt() or (1 shl (7 - depth % 8))).toByte()
                emit(it, right, depth + 1)
            }
        }
        roots.forEach { (size, node) -> collapse(node); emit(node, ByteArray(size), 0) }
        return result
    }
}

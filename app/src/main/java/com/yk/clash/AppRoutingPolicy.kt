package com.yk.clash

data class AppRoutingPolicy(val mode: String, val selected: Set<String>) {
    data class Plan(val allowed: Set<String>, val disallowed: Set<String>)

    fun plan(self: String, available: Set<String>): Plan {
        val packages = (selected intersect available) - self
        return when (mode) {
            "all" -> Plan(emptySet(), setOf(self))
            "include" -> {
                require(packages.isNotEmpty()) { "请为“仅代理所选应用”选择至少一个已安装应用" }
                Plan(packages, emptySet())
            }
            "exclude" -> Plan(emptySet(), packages + self)
            else -> error("无效的应用分流模式")
        }
    }

    companion object {
        val modes = listOf("all", "include", "exclude")
        val labels = arrayOf("全部应用", "仅代理所选应用", "所选应用绕过")
    }
}

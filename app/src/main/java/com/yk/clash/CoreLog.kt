package com.yk.clash

data class CoreLog(val time: Long, val level: String, val message: String) {
    companion object {
        fun normalizeLevel(value: String) = when (val level = value.lowercase()) {
            "warning" -> "warn"
            else -> level
        }
    }
}

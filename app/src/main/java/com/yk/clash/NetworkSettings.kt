package com.yk.clash

import android.content.Context

object NetworkSettings {
    const val DEFAULT_MTU = 9000
    const val MIN_MTU = 1280
    const val MAX_MTU = 65535

    fun mtu(context: Context): Int = context.getSharedPreferences("network", Context.MODE_PRIVATE)
        .getInt("mtu", DEFAULT_MTU).coerceIn(MIN_MTU, MAX_MTU)
}

package com.yk.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                com.yk.clash.subscription.SubscriptionScheduler.reconcile(context)
                val enabled = context.getSharedPreferences("network", Context.MODE_PRIVATE).getBoolean("startOnBoot", false)
                if (enabled && ProfileStore(context).selected() != null && VpnService.prepare(context) == null) {
                    context.startForegroundService(Intent(context, ClashVpnService::class.java).setAction(ClashVpnService.BOOT))
                }
            } catch (failure: Exception) {
                ClashVpnService.error = "开机连接失败：${failure.message ?: "请打开应用检查 VPN 授权"}"
            } finally { pending.finish() }
        }.start()
    }
}

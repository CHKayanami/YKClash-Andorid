package com.yk.clash

import com.yk.clash.editor.GlobalOverrideStore
import android.content.pm.PackageManager
import android.os.SystemClock
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.net.IpPrefix
import android.os.Handler
import android.os.Looper
import java.util.UUID
import org.json.JSONObject
import java.util.concurrent.Executors

class ClashVpnService : VpnService() {
    companion object {
        @Volatile var state = "已断开"
        @Volatile var error = ""
        @Volatile var ipv6Active = false
        @Volatile var alwaysOnActive = false
        const val STOP = "com.yk.clash.STOP"
        const val START = "com.yk.clash.START"
        const val BOOT = "com.yk.clash.BOOT"
        private val worker = Executors.newSingleThreadExecutor()
    }
    private val runState = VpnRunState()
    @Volatile private var latestStartId = 0
    @Volatile private var recoverFailures = false
    private var retryDelay = 2000L
    private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var connectionTicket = 0L
    private val monitor = object : Runnable {
        override fun run() {
            if (state == "已连接") {
                val ticket = connectionTicket
                worker.execute {
                    if (!NativeCore.alive()) main.post {
                        if (!destroyed && runState.current(ticket) && state == "已连接") recover("代理内核已退出：${NativeCore.lastError()}")
                    }
                }
                main.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        alwaysOnActive = isAlwaysOn
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("vpn", "代理服务", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        alwaysOnActive = isAlwaysOn
        val prefs = getSharedPreferences("network", MODE_PRIVATE)
        if (intent?.action == STOP) {
            if (alwaysOnActive) {
                error = "请在系统 VPN 设置中关闭“始终开启 VPN”后再断开"
                return START_STICKY
            }
            val version = runState.stop()
            NativeCore.requestStop()
            prefs.edit().putBoolean("resumeVpn", false).apply()
            main.removeCallbacksAndMessages(null)
            state = "正在断开"
            worker.execute { cleanup(); main.post { if (runState.sameVersion(version)) stopSelfResult(latestStartId) } }
            return START_NOT_STICKY
        } else {
            if (intent == null && !alwaysOnActive && !prefs.getBoolean("resumeVpn", false)) {
                stopSelfResult(startId); return START_NOT_STICKY
            }
            recoverFailures = recoverFailures || alwaysOnActive || intent == null || intent.action == BOOT
            notifyStatus(if (state == "已连接") "代理运行中" else "正在连接")
            val version = runState.start() ?: return START_STICKY
            prefs.edit().putBoolean("resumeVpn", true).apply()
            beginConnection(version)
        }
        return START_STICKY
    }

    private fun beginConnection(version: Long) {
        if (!runState.current(version) || destroyed) return
        notifyStatus("正在连接")
        state = "正在连接"
        worker.execute {
            if (active || !runState.current(version)) return@execute
            state = "正在连接"
            error = ""
            try {
                check(prepare(this) == null) { "VPN 授权已失效，请打开应用重新授权" }
                val store = ProfileStore(this)
                val profile = store.selected() ?: throw IllegalStateException("请先导入配置")
                val networkPrefs = getSharedPreferences("network", MODE_PRIVATE)
                val mode = networkPrefs.getString("ipv6", "profile") ?: "profile"
                val overrides = GlobalOverrideStore(this)
                val merged = overrides.resolve(store.read(profile))
                overrides.installBundledRules(merged)
                val network = JSONObject(NativeCore.networkConfig(merged, mode))
                val ipv6 = network.getBoolean("ipv6")
                val config = network.getString("config")
                val routingMode = networkPrefs.getString("appRoutingMode", "all") ?: "all"
                val selectedApps = networkPrefs.getStringSet("appRouting_$routingMode", emptySet())!!.toSet()
                val availableApps = selectedApps.filter { name ->
                    try {
                        packageManager.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0)).uid != applicationInfo.uid
                    } catch (_: PackageManager.NameNotFoundException) { false }
                }.toSet()
                val appRouting = AppRoutingPolicy(routingMode, selectedApps).plan(packageName, availableApps)
                val exclusions = VpnRoutePolicy.exclusions(network.optJSONObject("dns2"), ipv6)
                val secret = UUID.randomUUID().toString()
                CoreApi.secret = secret
                CoreLogStore.get(this).append(CoreLog(System.currentTimeMillis(), "info", "开始启动代理内核"))
                val result = NativeCore.start(config, filesDir.absolutePath, secret)
                check(result.isEmpty()) { result }
                active = true
                if (!runState.current(version)) NativeCore.requestStop()
                val deadline = SystemClock.elapsedRealtime() + 120_000
                var ready = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!runState.current(version)) break
                    check(NativeCore.alive()) { NativeCore.lastError().ifBlank { "内核启动失败，请检查配置" } }
                    if (runCatching { CoreApi.request("/version") }.isSuccess) { ready = true; break }
                    Thread.sleep(150)
                }
                if (!runState.current(version)) { cleanup(); return@execute }
                check(ready) { "内核准备超时，请检查配置和规则下载地址" }
                CoreLogCollector.start(applicationContext, secret)
                // Activate VPN routing only after core resources and its API are ready.
                val builder = Builder().setSession("YKClash · ${profile.name}")
                    .setMtu(1500).addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
                    .addDnsServer("172.19.0.2")
                    .setBlocking(false)
                appRouting.allowed.forEach { builder.addAllowedApplication(it) }
                appRouting.disallowed.forEach { builder.addDisallowedApplication(it) }
                if (ipv6) {
                    builder.addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
                        .addDnsServer("fdfe:dcba:9876::2")
                }
                exclusions.forEach { route ->
                    try { builder.excludeRoute(IpPrefix(route.address, route.length)) }
                    catch (failure: IllegalArgumentException) {
                        throw IllegalArgumentException("无法排除 VPN 网段 ${route.address.hostAddress}/${route.length}：${failure.message}", failure)
                    }
                }
                val tun = builder.establish() ?: throw IllegalStateException("无法创建 VPN 接口")
                val payload = tun.use { NativeCore.attachTun(config, it.detachFd(), secret) }
                CoreApi.request("/configs", "PUT", JSONObject().put("payload", payload))
                if (!runState.current(version)) { cleanup(); return@execute }
                check(NativeCore.alive()) { NativeCore.lastError().ifBlank { "VPN 接入失败" } }
                runCatching { RuntimeYamlStore(this).save(payload, profile.name) }
                    .onFailure { error = "运行配置已生效，但 YAML 快照保存失败：${it.message}" }
                ipv6Active = ipv6
                main.post {
                    if (runState.current(version) && !destroyed) {
                        state = "已连接"; retryDelay = 2000; recoverFailures = true
                        connectionTicket = version
                        notifyStatus("${profile.name} · 代理运行中")
                        main.removeCallbacks(monitor); main.post(monitor)
                    }
                }
            } catch (failure: Throwable) {
                CoreLogStore.get(this).append(CoreLog(System.currentTimeMillis(), "error", "代理启动失败：${failure.message ?: "未知错误"}"))
                cleanup()
                main.post { if (runState.current(version) && !destroyed) recover(failure.message ?: "启动失败") }
            }
        }
    }

    private fun recover(message: String) {
        error = message
        val ticket = runState.stop()
        main.removeCallbacksAndMessages(null)
        state = if (recoverFailures) "等待重连" else "已断开"
        worker.execute {
            cleanup()
            main.post {
                if (destroyed || !runState.sameVersion(ticket)) return@post
                if (recoverFailures && prepare(this) == null) {
                    state = "等待重连"
                    notifyStatus("连接中断，${retryDelay / 1000} 秒后重试")
                    val delay = retryDelay; retryDelay = (retryDelay * 2).coerceAtMost(60_000)
                    val next = runState.start() ?: return@post
                    main.postDelayed({ beginConnection(next) }, delay)
                } else {
                    getSharedPreferences("network", MODE_PRIVATE).edit().putBoolean("resumeVpn", false).apply()
                    stopSelfResult(latestStartId)
                }
            }
        }
    }

    private fun notifyStatus(text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ClashVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "vpn").setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("YKClash").setContentText(text).setContentIntent(open).setOngoing(true)
        if (!isAlwaysOn) notification.addAction(Notification.Action.Builder(null, "断开", stop).build())
        startForeground(1, notification.build())
    }

    private fun cleanup() {
        CoreLogCollector.stop()
        runCatching { NativeCore.stop() }
        if (active) CoreLogStore.get(this).append(CoreLog(System.currentTimeMillis(), "info", "代理内核已停止"))
        active = false
        ipv6Active = false
        state = "已断开"
        main.post { main.removeCallbacks(monitor) }
    }

    override fun onRevoke() {
        runState.stop()
        NativeCore.requestStop()
        getSharedPreferences("network", MODE_PRIVATE).edit().putBoolean("resumeVpn", false).apply()
        main.post { main.removeCallbacksAndMessages(null); stopSelf() }
    }
    override fun onDestroy() {
        destroyed = true
        runState.stop()
        NativeCore.requestStop()
        main.removeCallbacksAndMessages(null)
        worker.execute { cleanup() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        alwaysOnActive = false
        super.onDestroy()
    }
}

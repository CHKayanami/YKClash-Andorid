package com.yk.clash

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Collection follows the core lifetime, independently of the log screen. */
object CoreLogCollector {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var running = false
    private var secret = ""
    private lateinit var store: CoreLogStore
    @Volatile var state = "内核未运行 · 可查询历史日志"
        private set
    private val reconnect = Runnable { connect() }

    fun start(context: Context, token: String) {
        val history = CoreLogStore.get(context)
        main.post {
            stopNow()
            store = history; secret = token; running = true
            connect()
        }
    }

    fun stop() { main.post { stopNow() } }
    private fun stopNow() {
        running = false; generation++; secret = ""
        main.removeCallbacks(reconnect); socket?.cancel(); socket = null
        state = "内核未运行 · 可查询历史日志"
    }

    private fun connect() {
        if (!running) return
        val attempt = ++generation
        state = "正在连接日志"
        val request = Request.Builder().url("ws://127.0.0.1:19090/logs")
            .header("Authorization", "Bearer $secret").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            private fun current() = running && generation == attempt
            private fun retry() { main.post {
                if (current()) {
                    state = "日志连接中断 · 正在重试"
                    main.removeCallbacks(reconnect); main.postDelayed(reconnect, 2000)
                }
            } }
            override fun onOpen(webSocket: WebSocket, response: Response) { main.post {
                if (current()) state = "后台持续记录中" else webSocket.cancel()
            } }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // WebSocket callbacks write on the reader thread, never on the UI thread.
                if (!current()) return
                val entry = runCatching {
                    val json = JSONObject(text)
                    CoreLog(System.currentTimeMillis(), CoreLog.normalizeLevel(json.optString("type", "info")), json.optString("payload", text))
                }.getOrElse { CoreLog(System.currentTimeMillis(), "info", text) }
                store.append(entry)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry()
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()
        })
    }
}

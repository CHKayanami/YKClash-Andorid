package com.yk.clash.download

import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Derived clients share pools and TLS setup; OkHttp separates routes by proxy. */
object DownloadClients {
    private val base = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun forRoute(proxy: Proxy, timeoutSeconds: Long): OkHttpClient = base.newBuilder()
        .proxy(proxy).callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
}

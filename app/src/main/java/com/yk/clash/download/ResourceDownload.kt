package com.yk.clash.download

import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL

/** Stream resources through the core, with one fresh direct attempt on transport/HTTP failure. */
object ResourceDownload {
    fun <T> get(rawUrl: String, maxBytes: Long, timeoutSeconds: Long, consume: (InputStream) -> T): T {
        validate(rawUrl)
        return DownloadRouting.run { proxy ->
            val client = DownloadClients.forRoute(proxy, timeoutSeconds)
            var address = rawUrl
            var result: T? = null
            var complete = false
            for (redirect in 0..5) {
                validate(address)
                client.newCall(Request.Builder().url(address).build()).execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        check(redirect < 5) { "资源重定向次数过多" }
                        val location = response.header("Location") ?: error("资源重定向缺少地址")
                        address = URL(URL(address), location).toString()
                    } else {
                        if (!response.isSuccessful) throw IOException("资源下载失败：HTTP ${response.code}")
                        val body = response.body ?: throw IOException("资源下载为空")
                        require(body.contentLength() <= maxBytes) { "资源超过大小限制" }
                        body.byteStream().use { input -> result = consume(LimitedInput(input, maxBytes)) }
                        complete = true
                    }
                }
                if (complete) {
                    @Suppress("UNCHECKED_CAST")
                    return@run result as T
                }
            }
            error("资源重定向次数过多")
        }
    }
    private fun validate(raw: String) {
        val url = URL(raw)
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "请输入有效的 HTTPS 资源链接" }
    }
    private class LimitedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            val result = `in`.read()
            if (result >= 0) record(1)
            return result
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = `in`.read(buffer, offset, length)
            if (count > 0) record(count)
            return count
        }
        private fun record(count: Int) { total += count; require(total <= limit) { "资源超过大小限制" } }
    }
}

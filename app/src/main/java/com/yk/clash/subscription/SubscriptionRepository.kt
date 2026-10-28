package com.yk.clash.subscription

import android.content.Context
import com.yk.clash.download.DownloadRouting
import com.yk.clash.download.DownloadClients
import com.yk.clash.ProfileStore
import okhttp3.Request
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

object SubscriptionRepository {
    val updating: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private const val MAX_BYTES = 8 * 1024 * 1024
    data class Download(val yaml: String?, val info: SubscriptionInfo?, val website: String, val etag: String, val modified: String, val name: String)
    data class Summary(val success: Int, val failed: Int)

    fun validate(profile: ProfileStore.Profile) {
        val url = runCatching { URL(profile.url) }.getOrElse { error("订阅链接无效") }
        require(url.protocol in listOf("http", "https") && url.host.isNotBlank()) { "请输入有效的 HTTP 或 HTTPS 订阅链接" }
        require(profile.intervalMinutes in 15..525600) { "自动更新间隔应为 15–525600 分钟" }
        require(profile.updateMode in listOf("auto", "direct", "proxy")) { "无效的订阅更新方式" }
        require(profile.userAgent.isNotBlank() && '\r' !in profile.userAgent && '\n' !in profile.userAgent) { "User-Agent 无效" }
    }

    fun update(context: Context, expected: ProfileStore.Profile, create: Boolean = false, cancelled: () -> Boolean = { false }): ProfileStore.Profile {
        check(updating.add(expected.id)) { "此订阅正在更新" }
        val store = ProfileStore(context)
        try {
            validate(expected)
            check(!cancelled()) { "更新已取消" }
            val result = DownloadRouting.run(expected.updateMode, cancelled) { proxy ->
                download(context, expected, proxy, cancelled)
            }
            check(!cancelled()) { "更新已取消" }
            return store.saveDownload(expected, result.yaml, result.info, result.website, result.etag, result.modified, result.name, create)
        } catch (failure: Exception) {
            val message = when (failure) {
                is IOException -> "订阅下载失败，请检查网络或切换更新方式"
                else -> failure.message ?: "订阅更新失败"
            }
            if (!create && !cancelled()) store.recordFailure(expected, message)
            throw IllegalStateException(message, failure)
        } finally { updating.remove(expected.id) }
    }

    fun updateAll(context: Context, dueOnly: Boolean = false, cancelled: () -> Boolean = { false }): Summary {
        var success = 0; var failed = 0
        for (profile in ProfileStore(context).list().filter { it.url.isNotBlank() }) {
            if (cancelled()) break
            val waitMinutes = if (profile.lastError.isNotEmpty()) profile.intervalMinutes.coerceAtMost(30) else profile.intervalMinutes
            if (dueOnly && (!profile.autoUpdate || System.currentTimeMillis() - profile.checkedAt < waitMinutes * 60_000)) continue
            if (profile.id in updating) continue
            runCatching { update(context, profile, cancelled = cancelled) }.onSuccess { success++ }.onFailure { failed++ }
        }
        return Summary(success, failed)
    }

    private fun download(context: Context, profile: ProfileStore.Profile, proxy: Proxy, cancelled: () -> Boolean): Download {
        val client = DownloadClients.forRoute(proxy, 90)
        var address = profile.url
        for (attempt in 0..5) {
            check(!cancelled()) { "更新已取消" }
            val target = address.toHttpUrl()
            val request = Request.Builder().url(target.newBuilder().username("").password("").build()).header("User-Agent", profile.userAgent)
            if (target.username.isNotEmpty()) request.header("Authorization", Credentials.basic(target.username, target.password, Charsets.UTF_8))
            if (java.io.File(context.filesDir, "${profile.id}.yaml").isFile) {
                if (profile.etag.isNotBlank()) request.header("If-None-Match", profile.etag)
                if (profile.lastModified.isNotBlank()) request.header("If-Modified-Since", profile.lastModified)
            }
            client.newCall(request.build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    check(attempt < 5) { "订阅重定向次数过多" }
                    val next = URL(URL(address), response.header("Location") ?: error("订阅重定向缺少地址"))
                    require(next.protocol in listOf("http", "https") && (URL(address).protocol != "https" || next.protocol == "https")) { "订阅重定向协议无效" }
                    address = next.toString()
                } else {
                    check(response.isSuccessful || response.code == 304) { "订阅下载失败：HTTP ${response.code}" }
                    val info = SubscriptionInfo.fromHeader(response.header("Subscription-Userinfo"))
                    val website = response.header("Profile-Web-Page-Url").orEmpty().takeIf {
                        runCatching { URL(it).protocol in listOf("http", "https") }.getOrDefault(false)
                    }.orEmpty()
                    val yaml = if (response.code == 304) null else {
                        val body = response.body ?: error("订阅内容为空")
                        require(body.contentLength() <= MAX_BYTES) { "订阅超过 8 MB" }
                        val bytes = body.byteStream().use { boundedRead(it, cancelled) }
                        val gzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
                        val zlib = bytes.size >= 2 && bytes[0] == 0x78.toByte() && ((0x78 shl 8) or (bytes[1].toInt() and 0xff)) % 31 == 0
                        val content = if (gzip || zlib) runCatching {
                            val input = if (gzip) GZIPInputStream(ByteArrayInputStream(bytes)) else InflaterInputStream(ByteArrayInputStream(bytes))
                            input.use { boundedRead(it, cancelled) }
                        }.getOrElse { error("订阅压缩数据无效或超过大小限制") } else bytes
                        content.toString(Charsets.UTF_8).also { require(it.isNotBlank()) { "订阅内容为空" } }
                    }
                    return Download(yaml, info, website, response.header("ETag").orEmpty(), response.header("Last-Modified").orEmpty(), filename(response.header("Content-Disposition")))
                }
            }
        }
        error("订阅重定向次数过多")
    }

    private fun boundedRead(input: java.io.InputStream, cancelled: () -> Boolean): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
        while (true) {
            check(!cancelled()) { "更新已取消" }
            val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "订阅超过 8 MB" }; output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun filename(header: String?): String {
        if (header == null) return ""
        val encoded = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        val value = if (encoded != null) runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrDefault("") else plain.orEmpty()
        return value.trim().substringAfterLast('/').substringAfterLast('\\').removeSuffix(".yaml").removeSuffix(".yml").take(120)
    }
}

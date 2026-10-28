package com.yk.clash

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CoreApi {
    @Volatile var secret = ""
    fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = URL("http://127.0.0.1:19090$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1200
            connection.readTimeout = when { method == "PUT" && path.startsWith("/providers/") -> 60_000; method == "PUT" && path == "/configs" -> 15000; path.contains("/delay?") -> 7000; else -> 2000 }
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $secret")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(connection.responseCode in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(4096) }.orEmpty()
                "内核 API：${connection.responseCode} $detail"
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank() || (method == "DELETE" && !text.trimStart().startsWith("{"))) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
}

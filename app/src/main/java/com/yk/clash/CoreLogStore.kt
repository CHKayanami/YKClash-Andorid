package com.yk.clash

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Private, bounded history; all readers and writers share the same lock. */
class CoreLogStore private constructor(context: Context) {
    private val directory = File(context.filesDir, "core-logs").apply { mkdirs() }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile var revision = 0L
        private set
    @Volatile var error = ""
        private set
    data class Result(val entries: List<CoreLog>, val total: Int, val matches: Int)
    private fun file(index: Int) = File(directory, "logs-$index.jsonl")

    @Synchronized fun append(entry: CoreLog) {
        runCatching {
            val data = JSONObject().put("time", entry.time).put("level", entry.level)
                .put("message", entry.message.take(8192)).toString() + "\n"
            val bytes = data.toByteArray(Charsets.UTF_8)
            if (file(0).length() + bytes.size > FILE_LIMIT) {
                file(3).takeIf { it.exists() }?.let { check(it.delete()) { "无法清理旧日志" } }
                for (index in 2 downTo 0) if (file(index).exists()) {
                    check(file(index).renameTo(file(index + 1))) { "无法轮转日志" }
                }
            }
            file(0).appendBytes(bytes)
        }.onSuccess { error = ""; revision++ }
            .onFailure { error = "日志保存失败：${it.message}" }
    }

    fun read(level: String, query: String, done: (Result) -> Unit) = worker.execute {
        val result = runCatching { synchronized(this) {
            val entries = ArrayDeque<CoreLog>()
            var total = 0
            var matches = 0
            for (index in 3 downTo 0) if (file(index).exists()) {
                file(index).bufferedReader().useLines { lines -> lines.forEach { line ->
                    // A killed process can leave an incomplete final record.
                    val entry = runCatching {
                        val json = JSONObject(line)
                        CoreLog(json.getLong("time"), json.getString("level"), json.getString("message"))
                    }.getOrNull() ?: return@forEach
                    total++
                    if ((level == "all" || entry.level == level) && entry.message.contains(query, true)) {
                        matches++
                        if (entries.size == DISPLAY_LIMIT) entries.removeFirst()
                        entries.addLast(entry)
                    }
                } }
            }
            Result(entries.toList(), total, matches)
        } }.getOrElse {
            error = "日志读取失败：${it.message}"
            Result(emptyList(), 0, 0)
        }
        done(result)
    }

    fun clear(done: () -> Unit) = worker.execute {
        synchronized(this) {
            runCatching {
                for (index in 0..3) check(!file(index).exists() || file(index).delete()) { "无法删除日志文件" }
            }.onSuccess { error = "" }.onFailure { error = "清空日志失败：${it.message}" }
            revision++
        }
        done()
    }

    companion object {
        private const val FILE_LIMIT = 2 * 1024 * 1024L
        const val DISPLAY_LIMIT = 2000
        @Volatile private var instance: CoreLogStore? = null
        fun get(context: Context): CoreLogStore = instance ?: synchronized(this) {
            instance ?: CoreLogStore(context.applicationContext).also { instance = it }
        }
    }
}

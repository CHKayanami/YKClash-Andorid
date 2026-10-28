package com.yk.clash

import android.content.Context
import com.yk.clash.subscription.SubscriptionInfo
import com.yk.clash.subscription.SubscriptionScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class ProfileStore(private val context: Context) {
    data class Profile(
        val id: String, val name: String, val url: String,
        val userAgent: String = "clash.meta", val autoUpdate: Boolean = url.isNotBlank(),
        val intervalMinutes: Long = 1440, val updateMode: String = "auto",
        val updatedAt: Long = 0, val checkedAt: Long = 0, val lastError: String = "",
        val info: SubscriptionInfo? = null, val website: String = "",
        val etag: String = "", val lastModified: String = "", val revision: Long = 0, val contentRevision: Long = revision,
    ) {
        fun toJson() = JSONObject().put("id", id).put("name", name).put("url", url)
            .put("userAgent", userAgent).put("autoUpdate", autoUpdate).put("intervalMinutes", intervalMinutes).put("updateMode", updateMode)
            .put("updatedAt", updatedAt).put("checkedAt", checkedAt).put("lastError", lastError).put("info", info?.toJson())
            .put("website", website).put("etag", etag).put("lastModified", lastModified).put("revision", revision).put("contentRevision", contentRevision)
    }
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    fun list(): List<Profile> = synchronized(lock) {
        val array = JSONArray(prefs.getString("list", "[]"))
        (0 until array.length()).map {
            val p = array.getJSONObject(it)
            val url = p.optString("url")
            Profile(p.getString("id"), p.getString("name"), url,
                p.optString("userAgent", "clash.meta"), p.optBoolean("autoUpdate", url.isNotBlank()),
                p.optLong("intervalMinutes", 1440).coerceAtLeast(15), p.optString("updateMode", "auto"),
                p.optLong("updatedAt"), p.optLong("checkedAt"), p.optString("lastError"),
                SubscriptionInfo.fromJson(p.optJSONObject("info")), p.optString("website"),
                p.optString("etag"), p.optString("lastModified"), p.optLong("revision"), p.optLong("contentRevision", p.optLong("revision")))
        }
    }
    fun ensureDefault() = synchronized(lock) {
        if (!prefs.getBoolean("defaultInstalled", false)) {
            save("默认配置", "", context.assets.open("default.yaml").bufferedReader().use { it.readText() })
            prefs.edit().putBoolean("defaultInstalled", true).apply()
        }
    }
    fun selected(): Profile? = list().find { it.id == prefs.getString("selected", "") }
    fun select(profile: Profile) { prefs.edit().putString("selected", profile.id).apply() }
    fun read(profile: Profile): String = File(context.filesDir, "${profile.id}.yaml").readText()
    private fun writeYaml(id: String, text: String) {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "配置为空或超过 8 MB" }
        val temporary = File.createTempFile("profile-", ".tmp", context.filesDir)
        try {
            temporary.outputStream().use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(temporary.toPath(), File(context.filesDir, "$id.yaml").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun persist(profile: Profile) {
        val records = list().toMutableList()
        val index = records.indexOfFirst { it.id == profile.id }
        if (index >= 0) records[index] = profile else records.add(profile)
        prefs.edit().putString("list", JSONArray(records.map { it.toJson() }).toString()).apply()
        if (selected() == null) select(profile)
        changes.incrementAndGet()
    }
    fun save(name: String, url: String, text: String, existing: Profile? = null): Profile = synchronized(lock) {
        val previous = existing?.let { old -> list().find { it.id == old.id } ?: error("配置已删除") }
        check(previous == null || previous.contentRevision == existing?.contentRevision) { "配置已被更新，请重新打开编辑" }
        val editedName = if (existing != null && name == existing.name) previous!!.name else name.ifBlank { "未命名配置" }
        val editedUrl = if (existing != null && url == existing.url) previous!!.url else url
        val result = (previous ?: Profile(UUID.randomUUID().toString(), editedName, editedUrl)).copy(
            name = editedName, url = editedUrl, revision = (previous?.revision ?: 0) + 1, contentRevision = (previous?.contentRevision ?: 0) + 1)
        writeYaml(result.id, text); persist(result); SubscriptionScheduler.reconcile(context); result
    }
    fun saveSettings(expected: Profile, settings: Profile): Profile = synchronized(lock) {
        val current = list().find { it.id == expected.id } ?: error("订阅已删除")
        check(current.revision == expected.revision) { "订阅已被更新，请重新打开设置" }
        val changedUrl = current.url != settings.url || current.userAgent != settings.userAgent
        val result = current.copy(name = settings.name.ifBlank { current.name }, url = settings.url, userAgent = settings.userAgent,
            autoUpdate = settings.autoUpdate, intervalMinutes = settings.intervalMinutes, updateMode = settings.updateMode,
            etag = if (changedUrl) "" else current.etag, lastModified = if (changedUrl) "" else current.lastModified,
            lastError = "", revision = current.revision + 1)
        persist(result); SubscriptionScheduler.reconcile(context); result
    }
    fun saveDownload(expected: Profile, yaml: String?, info: SubscriptionInfo?, website: String, etag: String, modified: String, suggestedName: String, allowCreate: Boolean = false): Profile = synchronized(lock) {
        val current = list().find { it.id == expected.id }
        check(current == null && allowCreate && expected.revision == 0L || current != null && current.revision == expected.revision) { "订阅在下载期间被修改或删除，未覆盖文件" }
        if (current == null) check(yaml != null) { "新订阅没有返回配置内容" }
        val contentChanged = yaml != null && (current == null || read(current) != yaml)
        if (contentChanged) writeYaml(expected.id, yaml!!)
        val now = System.currentTimeMillis()
        val result = (current ?: expected).copy(name = expected.name.ifBlank { suggestedName.ifBlank { "远程订阅" } },
            updatedAt = if (yaml != null) now else current?.updatedAt ?: now, checkedAt = now, lastError = "",
            info = if (yaml != null) info else info ?: current?.info, website = website.ifBlank { current?.website.orEmpty() },
            etag = if (yaml != null) etag else etag.ifBlank { current?.etag.orEmpty() },
            lastModified = if (yaml != null) modified else modified.ifBlank { current?.lastModified.orEmpty() },
            revision = (current?.revision ?: 0) + 1, contentRevision = (current?.contentRevision ?: 0) + if (contentChanged) 1 else 0)
        persist(result); SubscriptionScheduler.reconcile(context); result
    }
    fun recordFailure(expected: Profile, message: String) = synchronized(lock) {
        list().find { it.id == expected.id && it.revision == expected.revision }?.let {
            persist(it.copy(checkedAt = System.currentTimeMillis(), lastError = message, revision = it.revision + 1))
        }
    }
    fun delete(profile: Profile) = synchronized(lock) {
        val selectedId = selected()?.id
        val remaining = list().filterNot { it.id == profile.id }
        val next = if (selectedId == profile.id) remaining.firstOrNull()?.id.orEmpty() else selectedId.orEmpty()
        prefs.edit().putString("list", JSONArray(remaining.map { it.toJson() }).toString()).putString("selected", next).apply()
        File(context.filesDir, "${profile.id}.yaml").delete()
        changes.incrementAndGet(); SubscriptionScheduler.reconcile(context)
    }
    companion object { private val lock = Any(); val changes = AtomicLong() }
}

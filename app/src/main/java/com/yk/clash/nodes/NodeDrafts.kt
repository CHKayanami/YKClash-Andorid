package com.yk.clash.nodes

import android.content.Context
import com.yk.clash.ProfileStore
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/** One serial worker owns draft documents; Bundles carry only an opaque draft id. */
internal object NodeDrafts {
    val worker = Executors.newSingleThreadExecutor()
    class Session(
        var profile: ProfileStore.Profile?, var root: JSONObject, var original: String,
        var dirty: Boolean = false, @Volatile var form: JSONObject? = null,
    )
    private val live = mutableMapOf<String, Session>()
    private fun file(context: Context, id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(context.cacheDir, "node-draft-$id.json")
    }
    fun get(context: Context, id: String): Session? {
        live[id]?.let { return it }
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        context.cacheDir.listFiles()?.filter {
            it.name.matches(Regex("node-draft-[a-f0-9-]{36}\\.json")) && it.name != "node-draft-$id.json" && it.lastModified() < cutoff
        }?.forEach { it.delete() }
        val file = file(context, id)
        if (!file.exists()) return null
        val data = JSONObject(file.readText())
        val saved = data.optJSONObject("profile")
        val profile = saved?.let { p ->
            val current = ProfileStore(context).list().find { it.id == p.getString("id") } ?: error("配置已删除")
            current.copy(revision = p.getLong("revision"), contentRevision = p.optLong("contentRevision", p.getLong("revision")))
        }
        return Session(profile, data.getJSONObject("root"), data.optString("original", "[]"), data.optBoolean("dirty"), data.optJSONObject("form"))
            .also { live[id] = it }
    }
    fun put(context: Context, id: String, session: Session) {
        live[id] = session
        val data = JSONObject().put("root", session.root).put("original", session.original).put("dirty", session.dirty)
            .put("profile", session.profile?.let { JSONObject().put("id", it.id).put("revision", it.revision).put("contentRevision", it.contentRevision) })
            .put("form", session.form)
        val temporary = File.createTempFile("node-draft-", ".tmp", context.cacheDir)
        try {
            temporary.writeText(data.toString())
            Files.move(temporary.toPath(), file(context, id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun remove(context: Context, id: String) { live.remove(id); file(context, id).delete() }
}

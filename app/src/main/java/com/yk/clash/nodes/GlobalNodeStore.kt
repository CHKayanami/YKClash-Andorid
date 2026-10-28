package com.yk.clash.nodes

import android.content.Context
import com.yk.clash.editor.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Global nodes are added independently of the module override switch. */
class GlobalNodeStore(private val context: Context) {
    private val file get() = File(context.filesDir, "global-nodes.json")
    fun read(): String = synchronized(lock) { if (file.exists()) file.readText() else "[]" }
    fun save(expected: String, nodes: JSONArray): String = synchronized(lock) {
        check(read() == expected) { "全局节点已被其他操作修改，请重新打开" }
        val removed = JSONArray(expected).objects().map { it.getString("name") }.toSet() - nodes.objects().map { it.getString("name") }.toSet()
        if (removed.isNotEmpty()) {
            val overrides = GlobalOverrideStore(context)
            val state = overrides.load()
            val moduleReferences = NodeReferences.used(state.editorRoot(JSONObject()))
            val referenced = removed.intersect(moduleReferences)
            require(referenced.isEmpty()) { "全局覆写仍引用节点 ${referenced.joinToString()}，请先修改引用后再改名或删除" }
            val profiles = com.yk.clash.ProfileStore(context)
            profiles.list().forEach { profile ->
                val references = runCatching { NodeReferences.used(JSONObject(com.yk.clash.NativeCore.convertYamlMapping(profiles.read(profile), false))) }
                    .getOrElse { error("无法检查配置 ${profile.name} 的节点引用，请先修正该配置后再改名或删除") }
                val used = removed.intersect(references)
                require(used.isEmpty()) { "配置 ${profile.name} 仍引用节点 ${used.joinToString()}，请先修改引用后再改名或删除" }
            }
        }
        val data = nodes.toString(2)
        val temporary = File.createTempFile("nodes-", ".tmp", context.filesDir)
        try {
            temporary.outputStream().use { it.write(data.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        data
    }
    fun merge(root: JSONObject, checkCollisions: Boolean = true, snapshot: String = read()): JSONObject {
        val global = JSONArray(snapshot).objects()
        if (global.isEmpty()) return root
        val groupNames = root.optJSONArray("proxy-groups")?.objects().orEmpty().map { it.optString("name") }.toSet()
        require(!checkCollisions || global.none { it.optString("name") in groupNames }) { "全局节点与配置中的代理组重名，请修改节点名称" }
        val names = global.map { it.getString("name") }.toSet()
        val merged = JSONArray()
        root.optJSONArray("proxies")?.objects().orEmpty().filterNot { it.optString("name") in names }.forEach { merged.put(it) }
        global.forEach { merged.put(it) }
        root.put("proxies", merged)
        return root
    }
    companion object { private val lock = Any() }
}

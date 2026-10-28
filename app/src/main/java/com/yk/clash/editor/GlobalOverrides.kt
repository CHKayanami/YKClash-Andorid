package com.yk.clash.editor

import java.security.MessageDigest
import android.content.Context
import com.yk.clash.nodes.GlobalNodeStore
import com.yk.clash.NativeCore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class GlobalOverrides(private val defaults: JSONObject, saved: JSONObject) {
    var masterEnabled = saved.optBoolean("masterEnabled", true)
    val enabled = saved.optJSONArray("enabled")?.values()?.map(Any::toString)?.toMutableSet() ?: modules.filter { it != "geodata" }.toMutableSet()
    private val values = saved.optJSONObject("values")?.copyObject() ?: JSONObject()

    init {
        require(enabled.all { it in modules }) { "全局覆写包含未知模块" }
        val providers = values.optJSONObject("rule-providers")?.optJSONObject("rule-providers")
        val cn = providers?.optJSONObject("geosite-cn")
        if (cn?.optString("url") == "https://raw.githubusercontent.com/juewuy/ShellCrash/update/bin/geodata/mrs_geosite_cn.mrs") {
            val current = defaults.optJSONObject("rule-providers")?.optJSONObject("geosite-cn")?.optString("url")
            if (!current.isNullOrBlank()) cn.put("url", current)
        }
        if (saved.optInt("version", 1) < 3 && providers != null && !providers.has("fakeipfilter")) {
            defaults.optJSONObject("rule-providers")?.optJSONObject("fakeipfilter")?.let {
                providers.put("fakeipfilter", it.copyObject())
            }
        }
        // Upgrade existing module snapshots once; later user deletions remain intentional.
        if (saved.optInt("version", 1) < 2) {
            val cnip = defaults.optJSONObject("rule-providers")?.optJSONObject("cnip")
            if (providers != null && !providers.has("cnip") && cnip != null) {
                providers.put("cnip", cnip.copyObject())
            }
            val rulesModule = values.optJSONObject("rules")
            val rules = rulesModule?.optJSONArray("rules")
            if (rules != null && rules.values().none {
                val parts = it.toString().split(',').map(String::trim)
                parts.size >= 2 && parts[0].equals("RULE-SET", true) && parts[1] == "cnip"
            }) {
                val updated = JSONArray()
                var inserted = false
                rules.values().forEach { rule ->
                    if (!inserted && rule.toString().substringBefore(',').trim().equals("MATCH", true)) {
                        updated.put("RULE-SET,cnip,DIRECT"); inserted = true
                    }
                    updated.put(rule)
                }
                if (!inserted) updated.put("RULE-SET,cnip,DIRECT")
                rulesModule.put("rules", updated)
            }
        }
    }

    private fun value(id: String): JSONObject = values.optJSONObject(id) ?: JSONObject().apply {
        keysFor(id).forEach { key -> if (defaults.has(key)) put(key, defaults.get(key)) }
    }

    private fun applyModule(root: JSONObject, id: String) {
        val data = value(id)
        keysFor(id).forEach { key ->
            root.remove(key)
            if (data.has(key)) root.put(key, data.get(key))
        }
    }

    fun effective(base: JSONObject): JSONObject = base.copyObject().also { result ->
        if (masterEnabled) enabled.forEach { applyModule(result, it) }
    }.copyObject()

    fun editorRoot(base: JSONObject): JSONObject = base.copyObject().also { result -> modules.forEach { applyModule(result, it) } }.copyObject()

    fun capture(root: JSONObject) {
        for (id in modules) values.put(id, JSONObject().apply {
            keysFor(id).forEach { key -> if (root.has(key)) put(key, root.get(key)) }
        })
        // Detach edited arrays/maps from the live document before persistence.
        val detached = values.copyObject()
        values.namesList().forEach { values.put(it, detached.get(it)) }
    }

    fun reset(id: String) { values.remove(id) }
    fun serialize(): String = JSONObject().put("version", 3).put("enabled", JSONArray(modules.filter { it in enabled }))
        .put("masterEnabled", masterEnabled).put("values", values).toString()

    companion object {
        val modules = listOf("dns2", "rules", "proxy-groups", "sniffer", "rule-providers", "geodata")
        val labels = listOf("DNS2", "规则", "代理组", "嗅探", "规则提供者", "地理库")
        private val geoKeys = listOf("mmdb", "mmdb-download-url", "geosite", "geosite-download-url", "asn-mmdb", "asn-mmdb-download-url")
        private fun keysFor(id: String): List<String> = if (id == "geodata") geoKeys else listOf(id)
    }
}

class GlobalOverrideStore(private val context: Context) {
    private val file get() = File(context.filesDir, "global-overrides.json")
    fun read(): String = if (file.exists()) file.readText() else "{}"
    fun defaults(): JSONObject = context.assets.open("defaults/overrides.yaml").bufferedReader().use {
        JSONObject(NativeCore.configDocument(it.readText()))
    }
    fun load(raw: String = read()) = GlobalOverrides(defaults(), JSONObject(raw))
    fun isEnabled(): Boolean = JSONObject(read()).optBoolean("masterEnabled", true)
    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        val original = read()
        val data = JSONObject(original).put("masterEnabled", enabled).toString()
        write(data)
    }
    fun compose(base: JSONObject, state: GlobalOverrides, forEditor: Boolean = false, nodeSnapshot: String = GlobalNodeStore(context).read()): JSONObject {
        val effective = if (forEditor) state.editorRoot(base) else state.effective(base)
        GlobalNodeStore(context).merge(effective, checkCollisions = !forEditor, snapshot = nodeSnapshot)
        val network = context.getSharedPreferences("network", Context.MODE_PRIVATE)
        if (!forEditor && network.contains("quic")) {
            effective.remove("allow-quic"); effective.remove("allow_quic")
            effective.put("quic", network.getBoolean("quic", true))
        }
        return effective
    }
    fun resolve(profile: String): String {
        val state = load()
        val snapshot = GlobalNodeStore(context).read()
        val network = context.getSharedPreferences("network", Context.MODE_PRIVATE)
        if ((!state.masterEnabled || state.enabled.isEmpty()) && JSONArray(snapshot).length() == 0 && !network.contains("quic")) return profile
        val effective = compose(JSONObject(NativeCore.configDocument(profile)), state, nodeSnapshot = snapshot)
        if (state.masterEnabled && state.enabled.isNotEmpty()) EditorDocument(effective).validateReferences()
        // Core startup performs compatibility validation; merging only serializes the mapping.
        return NativeCore.convertYamlMapping(effective.toString(), true)
    }
    fun installBundledRules(effectiveYaml: String) {
        val shipped = defaults().optJSONObject("rule-providers") ?: return
        val active = JSONObject(NativeCore.convertYamlMapping(effectiveYaml, false)).optJSONObject("rule-providers") ?: return
        val index = context.assets.open("defaults/rules/index.json").bufferedReader().use { JSONObject(it.readText()) }
        val installedFile = File(context.filesDir, "bundled-rule-digests.json")
        val installed = runCatching { JSONObject(installedFile.readText()) }.getOrDefault(JSONObject())
        for (name in shipped.namesList()) {
            val bundled = shipped.getJSONObject(name)
            val current = active.optJSONObject(name) ?: continue
            if (listOf("type", "url", "format", "behavior").any { current.optString(it) != bundled.optString(it) }) continue
            // Older default profiles used rules/; seed that exact cache too.
            val path = current.optString("path").removePrefix("./")
            if (path !in listOf("rule_set/$name.mrs", "rules/$name.mrs")) continue
            val target = File(context.filesDir, path)
            val metadata = index.optJSONObject(name) ?: continue
            val digest = metadata.getString("sha256")
            if (target.exists()) {
                val currentDigest = digestOf(target)
                if (currentDigest == digest) { installed.put(name, digest); continue }
                val previous = metadata.optJSONArray("previous")?.values().orEmpty()
                if (currentDigest != installed.optString(name) && currentDigest !in previous) continue
            }
            val asset = "defaults/rules/$name.mrs"
            target.parentFile!!.mkdirs()
            val temporary = File.createTempFile("rule-", ".tmp", target.parentFile)
            try {
                context.assets.open(asset).use { input -> temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                check(digestOf(temporary) == digest) { "内置规则 $name 与索引不一致，请重新打包资源" }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                installed.put(name, digest)
            } finally { temporary.delete() }
        }
        val tracking = File.createTempFile("rule-index-", ".tmp", context.filesDir)
        try {
            tracking.writeText(installed.toString())
            Files.move(tracking.toPath(), installedFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tracking.delete() }
    }
    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    fun save(expected: String, state: GlobalOverrides): String = synchronized(lock) {
        val current = read()
        val currentContent = JSONObject(current).apply { remove("masterEnabled") }
        val originalContent = JSONObject(expected).apply { remove("masterEnabled") }
        check(currentContent.toString() == originalContent.toString()) { "全局覆写已被其他操作修改，请重新打开" }
        state.masterEnabled = JSONObject(current).optBoolean("masterEnabled", true)
        val data = state.serialize()
        write(data)
        data
    }
    private fun write(data: String) {
        val temporary = File.createTempFile("overrides-", ".tmp", context.filesDir)
        try {
            temporary.outputStream().use { output -> output.write(data.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    companion object { private val lock = Any() }
}

package com.yk.clash.editor

import com.yk.clash.NativeCore
import com.yk.clash.download.ResourceDownload
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class GeodataPage(private val host: ConfigEditorActivity) {
    fun rows(): List<EditorRow> {
        val root = host.document.root
        val rows = mutableListOf(EditorRow("地理库路径与更新链接", "GeoIP / ASN 使用 MMDB，GeoSite 使用 DAT。更新优先走已连接代理，网络失败时重试直连。", {
            val value = org.json.JSONObject()
            val fields = mutableListOf<FormField>()
            for ((kind, label) in listOf("mmdb" to "GeoIP", "geosite" to "GeoSite", "asn-mmdb" to "ASN")) {
                value.put("_enable_$kind", root.optString(kind).isNotBlank())
                fields += FormField("_enable_$kind", "启用 $label 数据库", FieldKind.BOOL)
                EditorSchemas.geodata.filter { it.key == kind || it.key == "$kind-download-url" }.forEach { field ->
                    if (root.has(field.key)) value.put(field.key, root.get(field.key))
                    fields += field.copy(show = { it.optBoolean("_enable_$kind") })
                }
            }
            FormDialog.show(host, host.ui, "地理库设置", value, fields) { result ->
                for (kind in listOf("mmdb", "geosite", "asn-mmdb")) {
                    for (key in listOf(kind, "$kind-download-url")) {
                        if (result.optBoolean("_enable_$kind") && result.has(key)) root.put(key, result.get(key)) else root.remove(key)
                    }
                }
                host.changed()
            }
        }))
        for ((kind, title) in listOf("mmdb" to "GeoIP", "geosite" to "GeoSite", "asn-mmdb" to "ASN")) {
            val pathDefault = EditorSchemas.geodata.first { it.key == kind }.default.toString()
            val urlDefault = EditorSchemas.geodata.first { it.key == "$kind-download-url" }.default.toString()
            val path = root.optString(kind, pathDefault)
            val url = root.optString("$kind-download-url", urlDefault)
            rows += EditorRow("立即更新 $title", if (root.optString(kind).isBlank()) "未启用，请先设置并保存路径与链接。" else "$path · 更新后重新连接加载；失败保留原文件。", {
                if (root.optString(kind).isBlank()) host.toast("请先启用并保存 $title 设置") else host.perform("正在下载并校验 $title") {
                    val size = GeodataDownload.update(host.filesDir, path, url, kind)
                    "$title 已更新（${size / 1024} KB），重新连接后生效"
                }
            })
        }
        return rows
    }
}

object GeodataDownload {
    fun update(directory: File, path: String, rawUrl: String, kind: String): Long {
        val target = File(directory, path).canonicalFile
        require(target.toPath().startsWith(directory.canonicalFile.toPath()) && target != directory.canonicalFile) { "请使用应用私有目录内的相对文件路径" }
        target.parentFile!!.mkdirs()
        val temporary = File.createTempFile("geodata-", ".download", target.parentFile)
        try {
            ResourceDownload.get(rawUrl, 256L * 1024 * 1024, 180) { input ->
                // Every attempt truncates the temporary file; a failed proxy
                // transfer can never be appended to the direct retry.
                temporary.outputStream().use { output -> input.copyTo(output, 64 * 1024); output.fd.sync() }
            }
            check(temporary.length() > 0) { "地理库下载为空" }
            val error = NativeCore.validateGeodata(temporary.absolutePath, kind)
            check(error.isEmpty()) { error }
            val size = temporary.length()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return size
        } finally { temporary.delete() }
    }
}

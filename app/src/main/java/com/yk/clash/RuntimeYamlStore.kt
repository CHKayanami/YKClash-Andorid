package com.yk.clash

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class RuntimeYamlStore(private val context: Context) {
    private val file get() = File(context.filesDir, "last-runtime.yaml")
    fun read(): String? = if (file.exists()) file.readText() else null

    fun save(yaml: String, profileName: String) {
        val temporary = File.createTempFile("runtime-", ".tmp", context.filesDir)
        try {
            val label = profileName.replace('\n', ' ').replace('\r', ' ')
            val header = "# 配置：$label\n# 生成时间：${java.time.OffsetDateTime.now()}\n"
            temporary.outputStream().use { output ->
                output.write((header + yaml).toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

package com.yk.clash

import org.json.JSONObject

/** Format legacy JSON documents without validating or changing YAML source files. */
object YamlDisplay {
    fun format(source: String): String {
        if (!source.trimStart().startsWith("{")) return source
        return runCatching {
            val mapping = JSONObject(source)
            NativeCore.convertYamlMapping(mapping.toString(), true)
        }.getOrDefault(source)
    }
}

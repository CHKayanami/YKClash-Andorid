package com.yk.clash.editor

import org.json.JSONArray
import org.json.JSONObject

object FormInput {
    fun capture(draft: JSONObject, field: FormField, text: String, validate: Boolean) {
        require(!validate || !field.required || text.isNotEmpty()) { "请填写${field.label}" }
        if (text.isEmpty()) { draft.remove(field.key); return }
        val value = runCatching {
            when (field.kind) {
                FieldKind.LINES -> JSONArray(text.lines().map(String::trim).filter(String::isNotEmpty))
                FieldKind.NUMBER -> text.toLongOrNull()?.also { require(it >= 0) { "${field.label}不能为负数" } } ?: error("${field.label}必须为整数")
                FieldKind.DECIMAL -> text.toDoubleOrNull()?.also { require(it.isFinite() && it >= 0) { "${field.label}无效" } } ?: error("${field.label}必须为数字")
                else -> text
            }
        }
        if (validate) draft.put(field.key, value.getOrThrow()) else value.onSuccess { draft.put(field.key, it) }
    }
}

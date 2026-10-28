package com.yk.clash.editor

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.MotionEvent
import android.widget.*
import com.yk.clash.ui.AppUi
import org.json.JSONArray
import org.json.JSONObject

enum class FieldKind { TEXT, MULTILINE, LINES, NUMBER, DECIMAL, BOOL, CHOICE }
data class FormField(
    val key: String, val label: String, val kind: FieldKind = FieldKind.TEXT,
    val choices: List<String> = emptyList(), val default: Any? = null, val required: Boolean = false,
    val show: (JSONObject) -> Boolean = { true },
    val section: String? = null,
    val trim: Boolean = true,
    val choicesFor: ((JSONObject) -> List<String>)? = null,
)

object FormDialog {
    fun show(activity: Activity, ui: AppUi, title: String, value: JSONObject, fields: List<FormField>, onDraft: ((JSONObject?) -> Unit)? = null, save: (JSONObject) -> Unit) {
        val draft = value.copyObject()
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12)) }
        val readers = mutableListOf<(Boolean) -> Unit>()
        val textDrafts = mutableMapOf<String, String>()
        value.optJSONObject("_formText")?.let { saved -> saved.namesList().forEach { textDrafts[it] = saved.getString(it) } }
        draft.remove("_formText")
        fun collect(validate: Boolean) { readers.forEach { it(validate) } }
        fun notifyDraft() {
            if (onDraft == null) return
            collect(false)
            onDraft?.invoke(draft.copyObject().put("_formText", JSONObject(textDrafts.toMap())))
        }
        fun render() {
            body.removeAllViews(); readers.clear()
            var currentSection: String? = null
            fields.filter { it.show(draft) }.forEach { field ->
                if (field.section != null && field.section != currentSection) {
                    currentSection = field.section
                    body.addView(ui.gap(8))
                    body.addView(ui.text(field.section, 16f, ui.primary, true))
                    body.addView(ui.gap(14))
                }
                val current = if (draft.has(field.key) && !draft.isNull(field.key)) draft.get(field.key) else field.default
                body.addView(ui.text(field.label, 13f, ui.muted)); body.addView(ui.gap(7))
                if (field.kind in listOf(FieldKind.BOOL, FieldKind.CHOICE)) {
                    val display = if (field.kind == FieldKind.BOOL) {
                        if (current == true) "已开启" else if (current == false) "已关闭" else "跟随默认"
                    } else current?.toString() ?: "未设置"
                    val button = ui.button(display, field.kind == FieldKind.BOOL && current == true) {}.apply {
                        if (field.kind == FieldKind.BOOL) stateDescription = display
                    }
                    button.setOnClickListener {
                        runCatching { collect(false) }.onSuccess {
                            val choices: List<Any?> = if (field.kind == FieldKind.BOOL) listOf(null, true, false) else listOf(null) + (field.choicesFor?.invoke(draft) ?: (field.choices + listOfNotNull(current?.toString())).distinct())
                            val labels = choices.map { when (it) { null -> "跟随默认"; true -> "开启"; false -> "关闭"; else -> it.toString() } }.toTypedArray()
                            ui.choiceDialog(field.label, labels.toList(), emptyList(), choices.indexOf(current)) { index ->
                                if (choices[index] == null) draft.remove(field.key) else draft.put(field.key, choices[index])
                                render(); notifyDraft()
                            }
                        }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
                    }
                    body.addView(button)
                    readers += { validate ->
                        if (!draft.has(field.key) && field.default != null) draft.put(field.key, field.default)
                        require(!validate || !field.required || draft.has(field.key)) { "请设置${field.label}" }
                    }
                } else {
                    val editor = EditText(activity).apply {
                        setText(textDrafts[field.key] ?: if (current is JSONArray) current.values().joinToString("\n") else current?.toString().orEmpty())
                        textSize = 14f; setTextColor(ui.ink); setHintTextColor(ui.muted)
                        background = ui.surface(ui.block, 16); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                        if (field.kind in listOf(FieldKind.LINES, FieldKind.MULTILINE)) {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            minLines = 2; maxLines = 7; isVerticalScrollBarEnabled = true
                            setHorizontallyScrolling(false)
                            if (field.kind == FieldKind.LINES) hint = "每行一项"
                            var lastTouchY = 0f
                            setOnTouchListener { view, event ->
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> { lastTouchY = event.y; view.parent.requestDisallowInterceptTouchEvent(true) }
                                    MotionEvent.ACTION_MOVE -> {
                                        val direction = if (event.y < lastTouchY) 1 else -1
                                        view.parent.requestDisallowInterceptTouchEvent(view.canScrollVertically(direction))
                                        lastTouchY = event.y
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
                                }
                                false
                            }
                        }
                        else { isSingleLine = true; if (field.kind == FieldKind.NUMBER || field.kind == FieldKind.DECIMAL) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
                    }
                    ui.styleInput(editor)
                    body.addView(editor, LinearLayout.LayoutParams(-1, -2))
                    readers += { validate ->
                        val rawText = editor.text.toString()
                        val text = if (field.trim) rawText.trim() else rawText
                        textDrafts[field.key] = text
                        FormInput.capture(draft, field, text, validate)
                    }
                    if (onDraft != null) editor.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { notifyDraft() }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                }
                body.addView(ui.gap(16))
            }
        }
        render()
        val scroll = ScrollView(activity).apply { addView(body) }
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnCancelListener { onDraft?.invoke(null) }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { collect(true); save(draft.copyObject()) }.onSuccess { dialog.dismiss() }
                .onFailure { Toast.makeText(activity, it.message ?: "设置无效", Toast.LENGTH_LONG).show() }
        } }
        dialog.show(); ui.styleDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { onDraft?.invoke(null); dialog.dismiss() }
    }
}

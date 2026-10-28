package com.yk.clash

import com.yk.clash.editor.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FormInputTest {
    @Test fun choosingTypeOrOutboundDoesNotRequireMatcherFirst() {
        val draft = JSONObject()
        val matcher = FormField("matcher", "匹配内容", required = true)
        FormInput.capture(draft, matcher, "", false)
        draft.put("type", "MATCH"); draft.put("target", "DIRECT")
        assertEquals("MATCH", draft.getString("type"))
        assertThrows(IllegalArgumentException::class.java) { FormInput.capture(draft, matcher, "", true) }
    }
    @Test fun incompleteNumberDoesNotBlockChoicesOrDestroyPreviousTypedValue() {
        val draft = JSONObject().put("interval", 300)
        val number = FormField("interval", "间隔", FieldKind.NUMBER)
        FormInput.capture(draft, number, ".", false)
        assertEquals(300, draft.getInt("interval"))
        assertThrows(IllegalStateException::class.java) { FormInput.capture(draft, number, ".", true) }
        FormInput.capture(draft, number, "600", true)
        assertEquals(600, draft.getInt("interval"))
    }
}

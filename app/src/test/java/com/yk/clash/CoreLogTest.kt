package com.yk.clash

import org.junit.Assert.*
import org.junit.Test

class CoreLogTest {
    @Test fun normalizesCoreWarningLevelForFiltering() {
        assertEquals("warn", CoreLog.normalizeLevel("warning"))
        assertEquals("warn", CoreLog.normalizeLevel("WARN"))
        assertEquals("info", CoreLog.normalizeLevel("info"))
    }
}

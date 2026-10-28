package com.yk.clash

import org.junit.Assert.*
import org.junit.Test

class AppRoutingPolicyTest {
    @Test fun defaultAndBypassAlwaysExcludeSelfAndNeverMixLists() {
        val all = AppRoutingPolicy("all", setOf("browser")).plan("self", setOf("browser"))
        assertTrue(all.allowed.isEmpty()); assertEquals(setOf("self"), all.disallowed)
        val bypass = AppRoutingPolicy("exclude", setOf("browser", "removed", "self")).plan("self", setOf("browser"))
        assertTrue(bypass.allowed.isEmpty()); assertEquals(setOf("self", "browser"), bypass.disallowed)
    }
    @Test fun allowOnlyInstalledSelectedAppsWithoutSelf() {
        val plan = AppRoutingPolicy("include", setOf("browser", "removed", "self")).plan("self", setOf("browser", "self"))
        assertEquals(setOf("browser"), plan.allowed); assertTrue(plan.disallowed.isEmpty())
    }
    @Test fun emptyOrUninstalledAllowListCannotAccidentallyCaptureAllApps() {
        for (selected in listOf(emptySet(), setOf("removed"), setOf("self"))) {
            assertThrows(IllegalArgumentException::class.java) { AppRoutingPolicy("include", selected).plan("self", setOf("self")) }
        }
    }
}

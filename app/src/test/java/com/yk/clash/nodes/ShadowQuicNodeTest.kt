package com.yk.clash.nodes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShadowQuicNodeTest {
    private fun node() = JSONObject().put("name", "Test").put("type", "shadowquic")
        .put("server", "proxy.example").put("port", 443).put("username", "user")
        .put("password", "password").put("server-name", "jls.example")

    @Test fun brutalSettingsSurviveFormRoundTrip() {
        val source = node().put("zero-rtt", false).put("over-stream", true)
            .put("congestion-control", JSONObject().put("brutal", JSONObject()
                .put("bandwidth", "20M").put("min-window", 32768).put("ack-compensate", true)))
        val draft = JSONObject(source.toString())
        ShadowQuicNode.prepare(draft)
        assertEquals("brutal", draft.getString("_sqControl"))
        val saved = node()
        ShadowQuicNode.apply(saved, draft)
        NodeLinks.validate(saved)
        assertFalse(saved.getBoolean("zero-rtt"))
        assertTrue(saved.getBoolean("over-stream"))
        val brutal = saved.getJSONObject("congestion-control").getJSONObject("brutal")
        assertEquals("20M", brutal.getString("bandwidth"))
        assertEquals(32768, brutal.getInt("min-window"))
        assertTrue(brutal.getBoolean("ack-compensate"))
        assertEquals(1.1, brutal.getDouble("cwnd-gain"), 0.0)
    }

    @Test fun credentialsAndMtuAreValidatedBeforeSaving() {
        NodeLinks.validate(node())
        for (key in listOf("username", "password", "server-name")) {
            val invalid = node().put(key, "")
            assertThrows(IllegalArgumentException::class.java) { NodeLinks.validate(invalid) }
        }
        for ((initial, minimum) in listOf(1300 to 1199, 1200 to 1290, 65536 to 1290)) {
            assertThrows(IllegalArgumentException::class.java) {
                NodeLinks.validate(node().put("initial-mtu", initial).put("min-mtu", minimum))
            }
        }
    }
}

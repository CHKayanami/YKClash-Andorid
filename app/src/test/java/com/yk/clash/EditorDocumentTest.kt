package com.yk.clash

import com.yk.clash.editor.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorDocumentTest {
    private fun fixture() = EditorDocument(JSONObject("""{
        "proxy-groups":[{"name":"First","type":"select","proxies":["DIRECT"]},{"name":"Second","type":"select","proxies":["First"]}],
        "rules":["RULE-SET,cn,First,no-resolve","MATCH,Second"],
        "rule-providers":{"cn":{"type":"http","proxy":"First","url":"https://example.com/cn.mrs","behavior":"domain"}},
        "dns2":{"upstreams":[{"tag":"direct","type":"remote","proxy":"First","servers":["223.5.5.5"]}],"routing":{"request":[{"rule-set":["cn"],"upstream":"direct"}]}},
        "unrelated":{"keep":[1,2,3]}
    }"""))

    @Test fun renameGroupUpdatesMembershipRoutingAndDownloadsWithoutChangingOrder() {
        val doc = fixture()
        doc.root.getJSONArray("proxy-groups").getJSONObject(0).put("name", "Renamed")
        doc.renameGroup("First", "Renamed")
        doc.validateReferences()
        assertEquals("Renamed", doc.root.getJSONArray("proxy-groups").getJSONObject(1).getJSONArray("proxies").getString(0))
        assertEquals("RULE-SET,cn,Renamed,no-resolve", doc.root.getJSONArray("rules").getString(0))
        assertEquals("Renamed", doc.root.getJSONObject("dns2").getJSONArray("upstreams").getJSONObject(0).getString("proxy"))
        assertEquals(3, doc.root.getJSONObject("unrelated").getJSONArray("keep").length())
    }

    @Test fun providerRenameUpdatesRoutingAndDns2References() {
        val doc = fixture()
        val providers = doc.root.getJSONObject("rule-providers")
        providers.put("new-cn", providers.remove("cn"))
        doc.renameProvider("cn", "new-cn"); doc.validateReferences()
        assertEquals("new-cn", doc.root.getJSONObject("dns2").getJSONObject("routing").getJSONArray("request").getJSONObject(0).getJSONArray("rule-set").getString(0))
        assertEquals("RULE-SET,new-cn,First,no-resolve", doc.root.getJSONArray("rules").getString(0))
    }

    @Test fun cyclesAndDanglingReferencesAreRejected() {
        val cycle = fixture()
        cycle.root.getJSONArray("proxy-groups").getJSONObject(0).getJSONArray("proxies").put(0, "Second")
        assertThrows(IllegalArgumentException::class.java) { cycle.validateReferences() }
        val missing = fixture(); missing.root.getJSONObject("rule-providers").remove("cn")
        assertThrows(IllegalArgumentException::class.java) { missing.validateReferences() }
    }

    @Test fun rulesPreserveRegexCommasAndLogicalExpressions() {
        for (text in listOf("DOMAIN-REGEX,^foo,bar$,First", "AND,((DOMAIN,example.com),(NETWORK,TCP)),First", "IP-CIDR,1.0.1.0/24,First,no-resolve", "MATCH,Second", "DOMAIN,example.com,no-resolve")) {
            assertEquals(text, RoutingRule.parse(text).encode())
        }
        assertEquals("no-resolve", RoutingRule.parse("DOMAIN,example.com,no-resolve").target)
    }
    @Test fun finalAndExtendedParametersKeepTheirOutbound() {
        for (raw in listOf(
            "FINAL,DIRECT",
            "AND,((DOMAIN,example.com),(OR,((NETWORK,TCP),(DST-PORT,443)))),DIRECT,no-resolve",
            "PROCESS-NAME,example.app,DIRECT,extended",
            "GEOIP,CN,DIRECT,extended,no-resolve",
        )) {
            val rule = RoutingRule.parse(raw)
            assertEquals("DIRECT", rule.target)
            assertEquals(raw, rule.encode())
            val doc = fixture()
            doc.root.getJSONArray("rules").put(0, raw)
            doc.validateReferences()
        }
    }

}

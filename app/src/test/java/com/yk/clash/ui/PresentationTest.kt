package com.yk.clash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationTest {
    @Test fun groupsFollowProfileOrderInsteadOfApiMapOrder() {
        val configured = listOf("🚀 节点选择", "♻️ 自动选择", "AI")
        val api = listOf("GLOBAL", "AI", "♻️ 自动选择", "🚀 节点选择")
        assertEquals(configured + "GLOBAL", orderedGroupNames(configured, api))
    }
    @Test fun missingAndGeneratedGroupsRemainDeterministic() {
        assertEquals(listOf("AI", "provider-a", "provider-z", "GLOBAL"), orderedGroupNames(
            listOf("removed", "AI", "AI"), listOf("provider-z", "GLOBAL", "AI", "provider-a")))
    }
    @Test fun rulesetNameIsKeptVisibleAndMatchIsDistinct() {
        assertEquals(MatchedRule("规则集", "geosite-cn"), matchedRule("RuleSet", "geosite-cn"))
        assertEquals(MatchedRule("规则集", "google-lite"), matchedRule("RULE-SET", "google-lite"))
        assertEquals(MatchedRule("兜底规则", "MATCH"), matchedRule("Match", ""))
    }
    @Test fun chainRunsFromPolicyGroupToFinalOutbound() {
        assertEquals("🎯 全球直连 → DIRECT", outboundChain(listOf("DIRECT", "🎯 全球直连")))
    }

}

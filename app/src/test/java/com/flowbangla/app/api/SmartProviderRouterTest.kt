package com.flowbangla.app.api

import com.flowbangla.app.data.ProviderId
import com.flowbangla.app.data.ProviderRoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartProviderRouterTest {

    @Test
    fun testSmartAutoShortDurationPrefersGroqFirst() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 12, // Short <= 20s
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = ""
        )

        assertEquals(2, order.size)
        assertEquals(ProviderId.GROQ, order[0])
        assertEquals(ProviderId.GEMINI, order[1])
    }

    @Test
    fun testSmartAutoLongDurationPrefersGeminiFirst() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 45, // Long > 20s
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = ""
        )

        assertEquals(2, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
        assertEquals(ProviderId.GROQ, order[1])
    }

    @Test
    fun testOnlyGroqEnabledWorksWithoutGeminiKey() {
        // Reproduces the exact user bug report: Gemini key removed / disabled, only Groq is active
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 10,
            isGeminiEnabled = false,
            geminiApiKey = "",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = ""
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.GROQ, order[0])
    }

    @Test
    fun testOnlyGeminiEnabledWorksWithoutGroqKey() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 10,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = false,
            groqApiKey = "",
            isOpenRouterEnabled = false,
            openRouterApiKey = ""
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
    }

    @Test
    fun testOpenRouterAudioIncludedWhenConfigured() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 5,
            isGeminiEnabled = false,
            geminiApiKey = "",
            isGroqEnabled = false,
            groqApiKey = "",
            isOpenRouterEnabled = true,
            openRouterApiKey = "sk-or-v1-OpenRouterKey"
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.OPENROUTER, order[0])
    }

    @Test
    fun testManualPriorityPreservesActiveOrder() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.MANUAL_PRIORITY,
            audioDurationSeconds = 60, // > 20s, but MANUAL_PRIORITY keeps Groq then Gemini
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = true,
            openRouterApiKey = "sk-or-Key"
        )

        assertEquals(3, order.size)
        assertEquals(ProviderId.GROQ, order[0])
        assertEquals(ProviderId.GEMINI, order[1])
        assertEquals(ProviderId.OPENROUTER, order[2])
    }

    @Test
    fun testFormattingProviderPriority() {
        val order = SmartProviderRouter.computeFormattingProviderOrder(
            isGroqEnabled = true,
            groqApiKey = "gsk_Groq",
            isCerebrasEnabled = true,
            cerebrasApiKey = "csk_Cerebras",
            isGeminiEnabled = true,
            geminiApiKey = "AIza_Gemini",
            isOpenRouterEnabled = true,
            openRouterApiKey = "sk-or_Key"
        )

        assertEquals(4, order.size)
        assertEquals(ProviderId.GROQ, order[0])
        assertEquals(ProviderId.CEREBRAS, order[1])
        assertEquals(ProviderId.GEMINI, order[2])
        assertEquals(ProviderId.OPENROUTER, order[3])
    }

    @Test
    fun testDisabledTogglesAreExcludedFromRouting() {
        val order = SmartProviderRouter.computeFormattingProviderOrder(
            isGroqEnabled = false, // Disabled by user toggle
            groqApiKey = "gsk_Groq",
            isCerebrasEnabled = true,
            cerebrasApiKey = "csk_Cerebras",
            isGeminiEnabled = false, // Disabled by user toggle
            geminiApiKey = "AIza_Gemini",
            isOpenRouterEnabled = true,
            openRouterApiKey = "sk-or_Key"
        )

        assertEquals(2, order.size)
        assertEquals(ProviderId.CEREBRAS, order[0])
        assertEquals(ProviderId.OPENROUTER, order[1])
    }

    @Test
    fun testNoActiveProvidersReturnsEmptyOrder() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 10,
            isGeminiEnabled = false,
            geminiApiKey = "",
            isGroqEnabled = false,
            groqApiKey = "",
            isOpenRouterEnabled = false,
            openRouterApiKey = ""
        )

        assertTrue(order.isEmpty())
    }
}

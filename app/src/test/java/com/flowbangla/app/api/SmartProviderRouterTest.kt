package com.flowbangla.app.api

import com.flowbangla.app.data.ProviderId
import com.flowbangla.app.data.ProviderRoutingMode
import org.junit.Assert.*
import org.junit.Test

class SmartProviderRouterTest {

    @Test
    fun testSmartAutoWithGeminiAndGroqPrioritizesGeminiForAsrAccuracy() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 12,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = ""
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
        assertFalse("Groq Whisper must NEVER be in Bangla/Mixed order!", order.contains(ProviderId.GROQ))
    }

    @Test
    fun testSmartAutoLongDurationPreservesGeminiFirst() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 45,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = ""
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
        assertFalse("Groq Whisper must NEVER be in Bangla/Mixed order!", order.contains(ProviderId.GROQ))
    }

    @Test
    fun testOnlyGroqEnabledWorksInEnglishMode() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 10,
            isGeminiEnabled = false,
            geminiApiKey = "",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = "",
            isBanglaPreferred = false
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

    @Test
    fun testLanguageAwareSmartAutoEnglishUsesGroqFirst() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 10,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = "",
            isBanglaPreferred = false
        )

        assertEquals(2, order.size)
        assertEquals(ProviderId.GROQ, order[0])
        assertEquals(ProviderId.GEMINI, order[1])
    }

    @Test
    fun testLanguageAwareSmartAutoBanglaUsesGeminiFirst() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 10,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = "",
            isBanglaPreferred = true
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
        assertFalse("Groq Whisper must NEVER be in Bangla order!", order.contains(ProviderId.GROQ))
    }

    @Test
    fun testElevenLabsAndDeepgramIntegrationInSmartAuto() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 15,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = "",
            isElevenLabsEnabled = true,
            elevenLabsApiKey = "xi_key",
            isDeepgramEnabled = true,
            deepgramApiKey = "dg_key",
            isBanglaPreferred = true
        )

        assertEquals(3, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
        assertEquals(ProviderId.ELEVENLABS, order[1])
        assertEquals(ProviderId.DEEPGRAM, order[2])
        assertFalse("Groq Whisper must NEVER be in Bangla order!", order.contains(ProviderId.GROQ))
    }

    @Test
    fun testSarvamAndSonioxIntegrationInBanglaSmartAuto() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 15,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isOpenRouterEnabled = false,
            openRouterApiKey = "",
            isSarvamEnabled = true,
            sarvamApiKey = "sarvam_key",
            isSonioxEnabled = true,
            sonioxApiKey = "soniox_key",
            isElevenLabsEnabled = true,
            elevenLabsApiKey = "xi_key",
            isDeepgramEnabled = true,
            deepgramApiKey = "dg_key",
            isBanglaPreferred = true
        )

        assertEquals(5, order.size)
        assertEquals(ProviderId.SARVAM, order[0])
        assertEquals(ProviderId.SONIOX, order[1])
        assertEquals(ProviderId.GEMINI, order[2])
        assertEquals(ProviderId.ELEVENLABS, order[3])
        assertEquals(ProviderId.DEEPGRAM, order[4])
        assertFalse("Groq Whisper must NEVER be in Bangla order!", order.contains(ProviderId.GROQ))
    }
}

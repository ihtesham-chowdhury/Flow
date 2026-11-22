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
            groqApiKey = "gsk_GroqKey"
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
            groqApiKey = "gsk_GroqKey"
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
            groqApiKey = ""
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
    }

    @Test
    fun testFormattingProviderPriority() {
        val order = SmartProviderRouter.computeFormattingProviderOrder(
            isGroqEnabled = true,
            groqApiKey = "gsk_Groq",
            isGeminiEnabled = true,
            geminiApiKey = "AIza_Gemini"
        )

        assertEquals(2, order.size)
        assertEquals(ProviderId.GROQ, order[0])
        assertEquals(ProviderId.GEMINI, order[1])
    }

    @Test
    fun testDisabledTogglesAreExcludedFromRouting() {
        val order = SmartProviderRouter.computeFormattingProviderOrder(
            isGroqEnabled = false, // Disabled by user toggle
            groqApiKey = "gsk_Groq",
            isGeminiEnabled = true,
            geminiApiKey = "AIza_Gemini"
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
    }

    @Test
    fun testNoActiveProvidersReturnsEmptyOrder() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 10,
            isGeminiEnabled = false,
            geminiApiKey = "",
            isGroqEnabled = false,
            groqApiKey = ""
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
            isBanglaPreferred = true
        )

        assertEquals(1, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
        assertFalse("Groq Whisper must NEVER be in Bangla order!", order.contains(ProviderId.GROQ))
    }

    @Test
    fun testDeepgramIntegrationInSmartAuto() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 15,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isDeepgramEnabled = true,
            deepgramApiKey = "dg_key",
            isBanglaPreferred = true
        )

        assertEquals(2, order.size)
        assertEquals(ProviderId.GEMINI, order[0])
        assertEquals(ProviderId.DEEPGRAM, order[1])
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
            isSarvamEnabled = true,
            sarvamApiKey = "sarvam_key",
            isSonioxEnabled = true,
            sonioxApiKey = "soniox_key",
            isDeepgramEnabled = true,
            deepgramApiKey = "dg_key",
            isBanglaPreferred = true
        )

        // Strict Invariant: Sarvam -> Soniox -> Gemini -> Deepgram (NEVER Groq)
        assertEquals(4, order.size)
        assertEquals(ProviderId.SARVAM, order[0])
        assertEquals(ProviderId.SONIOX, order[1])
        assertEquals(ProviderId.GEMINI, order[2])
        assertEquals(ProviderId.DEEPGRAM, order[3])
        assertFalse("Groq Whisper must NEVER be in Bangla order!", order.contains(ProviderId.GROQ))
    }

    @Test
    fun testEnglishSmartAuto5ProviderOrder() {
        val order = SmartProviderRouter.computeAsrProviderOrder(
            routingMode = ProviderRoutingMode.SMART_AUTO,
            audioDurationSeconds = 15,
            isGeminiEnabled = true,
            geminiApiKey = "AIzaSyGeminiKey",
            isGroqEnabled = true,
            groqApiKey = "gsk_GroqKey",
            isSarvamEnabled = true,
            sarvamApiKey = "sarvam_key",
            isSonioxEnabled = true,
            sonioxApiKey = "soniox_key",
            isDeepgramEnabled = true,
            deepgramApiKey = "dg_key",
            isBanglaPreferred = false
        )

        // English Order: Groq -> Deepgram -> Soniox -> Gemini -> Sarvam
        assertEquals(5, order.size)
        assertEquals(ProviderId.GROQ, order[0])
        assertEquals(ProviderId.DEEPGRAM, order[1])
        assertEquals(ProviderId.SONIOX, order[2])
        assertEquals(ProviderId.GEMINI, order[3])
        assertEquals(ProviderId.SARVAM, order[4])
    }

    @Test
    fun testGroqVerboseJsonReroutesBengaliToGemini() {
        // Groq flags language as "bengali" -> must reroute
        val result = GroqVerboseResult(
            text = "Kemon achen bhai",
            detectedLanguage = "bengali",
            avgLogProb = -0.30
        )
        assertTrue(SmartProviderRouter.shouldRerouteToGemini(result))
    }

    @Test
    fun testGroqVerboseJsonReroutesLowConfidenceToGemini() {
        // Groq hallucinating or low acoustic confidence (avgLogProb < -0.68) -> must reroute
        val result = GroqVerboseResult(
            text = "something mumbled here",
            detectedLanguage = "english",
            avgLogProb = -0.85
        )
        assertTrue(SmartProviderRouter.shouldRerouteToGemini(result))
    }

    @Test
    fun testGroqVerboseJsonReroutesBengaliScriptToGemini() {
        // Bengali script in text -> must reroute to Gemini
        val result = GroqVerboseResult(
            text = "আমার সোনার বাংলা",
            detectedLanguage = "english", // Even if misclassified as english
            avgLogProb = -0.20
        )
        assertTrue(SmartProviderRouter.shouldRerouteToGemini(result))
    }

    @Test
    fun testGroqVerboseJsonKeepsCleanEnglishOnGroq() {
        // High confidence clean English -> keep on Groq
        val result = GroqVerboseResult(
            text = "Hello, this is a clean English dictation.",
            detectedLanguage = "english",
            avgLogProb = -0.15
        )
        assertFalse(SmartProviderRouter.shouldRerouteToGemini(result))
    }
}

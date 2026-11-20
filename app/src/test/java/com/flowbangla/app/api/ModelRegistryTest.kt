package com.flowbangla.app.api

import com.flowbangla.app.data.ProviderId
import com.flowbangla.app.model.CloudPolishProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun testKnownRetiredModelsAreIdentified() {
        val retired = ModelRegistry.getKnownRetiredModels()
        assertTrue(retired.contains("llama-3.3-70b-versatile"))
        assertTrue(retired.contains("llama-3.1-8b-instant"))
        assertTrue(retired.contains("gemini-1.5-flash"))
        assertTrue(retired.contains("gemini-2.0-flash"))
        assertTrue(retired.contains("scribe_v1"))
    }

    @Test
    fun testCloudPolishProvidersContainNoRetiredModels() {
        val retired = ModelRegistry.getKnownRetiredModels()
        for (provider in CloudPolishProvider.entries) {
            val model = provider.defaultModel.lowercase()
            assertFalse(
                "Active CloudPolishProvider ${provider.name} uses retired model: $model",
                retired.contains(model)
            )
        }
    }

    @Test
    fun testModelCatalogContainsNoRetiredModels() {
        val retired = ModelRegistry.getKnownRetiredModels()
        for (spec in ModelCatalog.specs) {
            val model = spec.id.lowercase()
            assertFalse(
                "ModelCatalog spec uses retired model: $model",
                retired.contains(model)
            )
        }
    }

    @Test
    fun testRegistryActiveProvidersContainNoRetiredModels() {
        val json = JSONObject(ModelRegistry.BUNDLED_JSON)
        val retired = ModelRegistry.getKnownRetiredModels()
        val providersObj = json.getJSONObject("providers")
        val keys = providersObj.keys()
        while (keys.hasNext()) {
            val provKey = keys.next()
            val prov = providersObj.getJSONObject(provKey)
            for (task in listOf("asr", "polish")) {
                val list = prov.optJSONArray(task) ?: continue
                for (i in 0 until list.length()) {
                    val m = list.getJSONObject(i).getString("id").lowercase()
                    assertFalse(
                        "Provider $provKey task $task uses retired model: $m",
                        retired.contains(m)
                    )
                }
            }
        }
    }

    @Test
    fun testProviderHealthTrackerCircuitBreaker() {
        val tracker = ProviderHealthTracker()
        assertTrue(tracker.isProviderAvailable("gemini"))

        // Record 3 failures
        tracker.recordHttpError("gemini", "gemini-3.5-transcribe", 500, null, "Server Error")
        tracker.recordHttpError("gemini", "gemini-3.5-transcribe", 500, null, "Server Error")
        tracker.recordHttpError("gemini", "gemini-3.5-transcribe", 500, null, "Server Error")

        // Circuit breaker should now be OPEN
        assertFalse(tracker.isProviderAvailable("gemini"))
        assertEquals(ProviderHealthStatus.CIRCUIT_OPEN, tracker.getStatus("gemini"))
    }

    @Test
    fun testProviderHealthTrackerRateLimitCooldown() {
        val tracker = ProviderHealthTracker()
        tracker.recordHttpError("groq", "whisper-large-v3-turbo", 429, 30L, "Rate limit reached")
        assertFalse(tracker.isProviderAvailable("groq"))
        assertEquals(ProviderHealthStatus.COOLDOWN_429, tracker.getStatus("groq"))
    }

    @Test
    fun testBanglaAndMixedNeverIncludeGroqWhisper() {
        val json = JSONObject(ModelRegistry.BUNDLED_JSON)
        val groqAsr = json.getJSONObject("providers")
            .getJSONObject("groq")
            .getJSONArray("asr")
            .getJSONObject(0)

        val langsArr = groqAsr.getJSONArray("langs")
        val langs = mutableListOf<String>()
        for (i in 0 until langsArr.length()) {
            langs.add(langsArr.getString(i))
        }

        // Groq Whisper must ONLY be tagged for English!
        assertEquals(listOf("en"), langs)
        assertFalse("Groq ASR must NEVER support Bengali!", langs.contains("bn"))
        assertFalse("Groq ASR must NEVER support Mixed code-switching!", langs.contains("mixed"))
    }

    @Test
    fun testOnly5ActiveProvidersInRegistryAndProviderId() {
        val json = JSONObject(ModelRegistry.BUNDLED_JSON)
        val providersObj = json.getJSONObject("providers")
        val providerKeys = mutableSetOf<String>()
        val it = providersObj.keys()
        while (it.hasNext()) {
            providerKeys.add(it.next().lowercase())
        }

        val expectedProviders = setOf("gemini", "groq", "deepgram", "sarvam", "soniox")
        assertEquals(expectedProviders, providerKeys)

        val enumProviders = ProviderId.entries.map { it.name.lowercase() }.toSet()
        assertEquals(expectedProviders, enumProviders)
    }

    @Test
    fun testDeepgramNovaClientLanguageMapping() {
        assertEquals("language=en", DeepgramNovaClient.buildLanguageQuery(SpeechLang.ENGLISH))
        assertEquals("language=bn", DeepgramNovaClient.buildLanguageQuery(SpeechLang.BANGLA))
        assertEquals("language=multi", DeepgramNovaClient.buildLanguageQuery(SpeechLang.MIXED))
    }
}

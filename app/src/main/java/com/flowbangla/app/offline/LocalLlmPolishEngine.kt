package com.flowbangla.app.offline

import android.content.Context
import com.flowbangla.app.api.CloudTextPolishClient
import com.flowbangla.app.data.PreferencesManager
import com.flowbangla.app.polish.LocalFillerCleaner
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LocalLlmPolishEngine(
    private val context: Context,
    private val prefs: PreferencesManager
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: Engine? = null
    private var idleUnloadJob: Job? = null

    companion object {
        private const val IDLE_UNLOAD_TIMEOUT_MS = 120_000L // Unload from RAM after 2 minutes idle
    }

    suspend fun polishOfflineWithLlm(rawText: String): String = withContext(Dispatchers.IO) {
        val modelPath = prefs.downloadedLocalModelPath
        if (modelPath.isNullOrBlank() || !File(modelPath).exists()) {
            return@withContext LocalFillerCleaner.cleanAndPunctuate(rawText)
        }

        return@withContext mutex.withLock {
            try {
                val activeEngine = getOrInitEngine(modelPath)
                resetIdleTimer()

                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(CloudTextPolishClient.TEXT_POLISH_SYSTEM_PROMPT.trim()),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.15)
                )

                activeEngine.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(rawText)
                    val polished = response.toString().trim()
                    if (polished.isNotEmpty()) polished else LocalFillerCleaner.cleanAndPunctuate(rawText)
                }
            } catch (_: Throwable) {
                // Fallback to lightweight regex cleaner if device runs out of memory
                unloadEngineInternal()
                LocalFillerCleaner.cleanAndPunctuate(rawText)
            }
        }
    }

    private fun getOrInitEngine(modelPath: String): Engine {
        engine?.let { return it }
        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(), // CPU XNNPACK 4-thread backend is safest across all Xiaomi/MediaTek/Snapdragon devices
            cacheDir = context.cacheDir.absolutePath
        )
        return Engine(config).also {
            it.initialize()
            engine = it
        }
    }

    private fun resetIdleTimer() {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            delay(IDLE_UNLOAD_TIMEOUT_MS)
            mutex.withLock {
                unloadEngineInternal()
            }
        }
    }

    private fun unloadEngineInternal() {
        try {
            engine?.close()
        } catch (_: Throwable) {
        } finally {
            engine = null
        }
    }

    fun release() {
        idleUnloadJob?.cancel()
        unloadEngineInternal()
    }
}

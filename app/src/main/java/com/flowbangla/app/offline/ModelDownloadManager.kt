package com.flowbangla.app.offline

import android.content.Context
import com.flowbangla.app.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    object NotDownloaded : ModelDownloadState()
    data class Downloading(val progressPercent: Int, val downloadedMb: Int, val totalMb: Int) : ModelDownloadState()
    data class Downloaded(val filePath: String, val sizeMb: Int) : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

class ModelDownloadManager(
    private val context: Context,
    private val prefs: PreferencesManager
) {
    companion object {
        // Quantized 4-bit Gemma 3 1B Instruction-Tuned LiteRT-LM package from Hugging Face LiteRT Community
        const val DEFAULT_MODEL_FILENAME = "gemma3-1b-it-int4.litertlm"
        const val DEFAULT_MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm?download=true"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloadState = MutableStateFlow<ModelDownloadState>( getInitialState() )
    val downloadState: StateFlow<ModelDownloadState> = _downloadState

    @Volatile
    private var isCancelled = false

    private fun getModelDir(): File = File(context.filesDir, "litert_models").apply { mkdirs() }
    fun getModelFile(): File = File(getModelDir(), DEFAULT_MODEL_FILENAME)

    private fun getInitialState(): ModelDownloadState {
        val file = getModelFile()
        return if (file.exists() && file.length() > 100 * 1024 * 1024L) {
            prefs.downloadedLocalModelPath = file.absolutePath
            ModelDownloadState.Downloaded(file.absolutePath, (file.length() / (1024 * 1024)).toInt())
        } else {
            ModelDownloadState.NotDownloaded
        }
    }

    suspend fun downloadModel(customUrl: String = DEFAULT_MODEL_DOWNLOAD_URL, hfToken: String? = null) =
        withContext(Dispatchers.IO) {
            isCancelled = false
            val targetFile = getModelFile()
            val tempFile = File(getModelDir(), "$DEFAULT_MODEL_FILENAME.part")

            try {
                val requestBuilder = Request.Builder().url(customUrl)
                if (!hfToken.isNullOrBlank()) {
                    requestBuilder.addHeader("Authorization", "Bearer ${hfToken.trim()}")
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        _downloadState.value = ModelDownloadState.Error("HTTP ${response.code}: Check URL or HuggingFace token.")
                        return@withContext
                    }

                    val body = response.body ?: run {
                        _downloadState.value = ModelDownloadState.Error("Empty response body")
                        return@withContext
                    }

                    val totalBytes = body.contentLength()
                    val totalMb = if (totalBytes > 0) (totalBytes / (1024 * 1024)).toInt() else 750

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloadedBytes = 0L
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (isCancelled) {
                                    tempFile.delete()
                                    _downloadState.value = ModelDownloadState.NotDownloaded
                                    return@withContext
                                }
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val downloadedMb = (downloadedBytes / (1024 * 1024)).toInt()
                                val pct = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                                _downloadState.value = ModelDownloadState.Downloading(pct, downloadedMb, totalMb)
                            }
                            output.flush()
                        }
                    }

                    if (tempFile.renameTo(targetFile)) {
                        prefs.downloadedLocalModelPath = targetFile.absolutePath
                        val sizeMb = (targetFile.length() / (1024 * 1024)).toInt()
                        _downloadState.value = ModelDownloadState.Downloaded(targetFile.absolutePath, sizeMb)
                    } else {
                        _downloadState.value = ModelDownloadState.Error("Failed to finalize model file on storage.")
                    }
                }
            } catch (e: Exception) {
                tempFile.delete()
                _downloadState.value = ModelDownloadState.Error(e.localizedMessage ?: "Download failed")
            }
        }

    fun cancelDownload() {
        isCancelled = true
    }

    fun deleteDownloadedModel() {
        val file = getModelFile()
        if (file.exists()) file.delete()
        prefs.downloadedLocalModelPath = null
        _downloadState.value = ModelDownloadState.NotDownloaded
    }
}

package com.hippo.ehviewer.util

import android.graphics.Bitmap
import android.util.Base64
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class AiApiFormat(val raw: String) {
    GEMINI("gemini"),
    OPENAI("openai"),
    ;

    companion object {
        fun fromRaw(raw: String?): AiApiFormat = values().firstOrNull { it.raw == raw } ?: GEMINI
    }
}

data class AiConfig(
    val format: AiApiFormat,
    val baseUrl: String?,
    val apiKey: String?,
    val model: String?,
)

private const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash-exp"
private const val DEFAULT_OPENAI_MODEL = "gpt-image-1"
private const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
private const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com"

object AiManagers {
    fun currentConfig(): AiConfig {
        val format = AiApiFormat.fromRaw(Settings.aiApiFormat.value)
        return AiConfig(
            format = format,
            baseUrl = Settings.aiBaseUrl.value,
            apiKey = Settings.aiApiKey.value,
            model = Settings.aiDefaultModel.value,
        )
    }

    suspend fun processBitmap(bitmap: Bitmap, prompt: String, config: AiConfig = currentConfig()): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            val baseUrl = config.baseUrl ?: when (config.format) {
                AiApiFormat.GEMINI -> DEFAULT_GEMINI_BASE_URL
                AiApiFormat.OPENAI -> DEFAULT_OPENAI_BASE_URL
            }
            val model = config.model ?: when (config.format) {
                AiApiFormat.GEMINI -> DEFAULT_GEMINI_MODEL
                AiApiFormat.OPENAI -> DEFAULT_OPENAI_MODEL
            }

            GeminiManager.generateImageContent(
                format = config.format,
                baseUrl = baseUrl,
                apiKey = config.apiKey.orEmpty(),
                originalImageBase64 = bitmap.toBase64(),
                prompt = prompt,
                modelId = model,
            )
        }

    private fun Bitmap.toBase64(quality: Int = 90): String {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

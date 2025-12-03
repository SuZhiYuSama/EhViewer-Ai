package com.hippo.ehviewer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object GeminiManager {

    private const val TAG = "GeminiManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    const val PROMPT_COLORIZE = "请根据彩图对漫画进行上色。注意保持文字框为白底。"
    const val PROMPT_TRANSLATE = "请将漫画汉化为中文"

    fun generateImageContent(
        format: AiApiFormat,
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String,
    ): Result<Bitmap> {
        if (apiKey.isBlank()) return Result.failure(Exception("请先在设置中配置 AI API Key"))

        return when (format) {
            AiApiFormat.GEMINI -> requestGemini(baseUrl, apiKey, originalImageBase64, prompt, modelId)
            AiApiFormat.OPENAI -> requestOpenAi(baseUrl, apiKey, originalImageBase64, prompt, modelId)
        }
    }

    private fun requestGemini(
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String,
    ): Result<Bitmap> {
        val cleanBaseUrl = baseUrl.trim().removeSuffix("/")
        val apiUrl = "$cleanBaseUrl/v1beta/models/$modelId:generateContent?key=$apiKey"

        Log.d(TAG, "Requesting Gemini AI: $modelId")

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", originalImageBase64)
                    }))
                })
            }))
            put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("IMAGE")))
        }

        val request = Request.Builder()
            .url(apiUrl)
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val msg = if (response.code == 429) "API 请求过于频繁 (429)" else "HTTP ${response.code}"
                    Log.e(TAG, "API Error: $responseBody")
                    return@use Result.failure(Exception(msg))
                }

                val root = JSONObject(responseBody)
                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@use Result.failure(Exception("模型未返回结果"))
                }

                val part = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.getJSONObject(0)
                val inlineData = part?.optJSONObject("inlineData")

                if (inlineData != null) {
                    val base64Data = inlineData.getString("data")
                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val resultBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    return@use Result.success(resultBitmap)
                } else {
                    return@use Result.failure(Exception("模型返回了文本而非图片"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network Exception", e)
            Result.failure(e)
        }
    }

    private fun requestOpenAi(
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String,
    ): Result<Bitmap> {
        val cleanBaseUrl = baseUrl.trim().removeSuffix("/")
        val apiUrl = "$cleanBaseUrl/v1/images/edits"

        return runCatching {
            val tempFile = File.createTempFile("openai-image", ".png")
            val decoded = Base64.decode(originalImageBase64, Base64.DEFAULT)
            FileOutputStream(tempFile).use { it.write(decoded) }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", modelId)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart(
                    "image",
                    tempFile.name,
                    tempFile.asRequestBody("image/png".toMediaType()),
                )
                .addFormDataPart("response_format", "b64_json")
                .build()

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: $responseBody")
                }
                val root = JSONObject(responseBody)
                val dataArr = root.optJSONArray("data") ?: JSONArray()
                if (dataArr.length() == 0) error("模型未返回图片数据")
                val base64 = dataArr.getJSONObject(0).optString("b64_json")
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("无法解码返回的图片数据")
            }
        }
    }
}

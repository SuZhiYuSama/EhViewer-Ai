package com.hippo.ehviewer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object GeminiManager {

    private const val TAG = "GeminiManager"

    // 增加超时时间到 180秒，因为图片生成/处理可能很慢
    private val client = OkHttpClient.Builder()
        .connectTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    const val PROMPT_COLORIZE = "请将这张黑白漫画图片上色，直接返回上色后的图片，不要返回任何文字描述。"
    const val PROMPT_TRANSLATE = "请将图片中的文字汉化为中文，保持原图排版，直接返回处理后的图片。"

    fun generateImageContent(
        format: AiApiFormat,
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String,
    ): Result<Bitmap> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("未配置 AI API Key，请在设置中填写。"))

        return when (format) {
            AiApiFormat.GEMINI -> requestGemini(baseUrl, apiKey, originalImageBase64, prompt, modelId)
            AiApiFormat.OPENAI -> requestOpenAiChat(baseUrl, apiKey, originalImageBase64, prompt, modelId)
        }
    }

    private fun requestGemini(
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String,
    ): Result<Bitmap> {
        var cleanBaseUrl = baseUrl.trim().removeSuffix("/")

        // 修复 Gemini 404 问题：如果用户填写的 URL 已经包含了 /models，则不再拼接 /v1beta
        val apiUrl = if (cleanBaseUrl.contains("/models")) {
            "$cleanBaseUrl/$modelId:generateContent?key=$apiKey"
        } else {
            // 移除可能存在的末尾版本号，防止重复拼接 (如 /v1/v1beta)
            if (cleanBaseUrl.endsWith("/v1") || cleanBaseUrl.endsWith("/v1beta")) {
                cleanBaseUrl = cleanBaseUrl.substringBeforeLast("/")
            }
            "$cleanBaseUrl/v1beta/models/$modelId:generateContent?key=$apiKey"
        }

        Log.d(TAG, "Requesting Gemini AI: $modelId at $apiUrl")

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
            // 尝试请求图片响应，如果模型不支持可能会忽略
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
                    val msg = "Gemini Error ${response.code}: $responseBody"
                    Log.e(TAG, msg)
                    return@use Result.failure(Exception(msg))
                }

                val root = JSONObject(responseBody)
                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val feedback = root.optJSONObject("promptFeedback")
                    val blockReason = feedback?.optString("blockReason")
                    val msg = if (blockReason != null) "Gemini拦截: $blockReason" else "Gemini未返回结果"
                    return@use Result.failure(Exception(msg))
                }

                val part = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")?.getJSONObject(0)
                val inlineData = part?.optJSONObject("inlineData")

                if (inlineData != null) {
                    val base64Data = inlineData.getString("data")
                    decodeBase64(base64Data)
                } else {
                    // 只有文本？可能是提供了 URL 或者是拒绝了请求
                    val textData = part?.optString("text")
                    parseImageFromText(textData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network Exception", e)
            Result.failure(e)
        }
    }

    // 使用 Chat Completion 接口 (GPT-4V 格式) 替代原来的 Image Edit 接口
    private fun requestOpenAiChat(
        baseUrl: String,
        apiKey: String,
        originalImageBase64: String,
        prompt: String,
        modelId: String,
    ): Result<Bitmap> {
        var cleanBaseUrl = baseUrl.trim().removeSuffix("/")
        if (cleanBaseUrl.endsWith("/v1")) {
            cleanBaseUrl = cleanBaseUrl.substring(0, cleanBaseUrl.length - 3)
        }

        // 按照您的提供商要求，使用 chat/completions 接口
        val apiUrl = "$cleanBaseUrl/v1/chat/completions"
        Log.d(TAG, "Requesting OpenAI Chat: $modelId at $apiUrl")

        val jsonBody = JSONObject().apply {
            put("model", modelId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        // 文本提示
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        // 图片内容 (GPT-4V 格式)
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$originalImageBase64")
                            })
                        })
                    })
                })
            })
            // 防止回复过长截断，虽主要期望图片
            put("max_tokens", 4096)
        }

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = "OpenAI Error ${response.code}: $responseBody"
                    throw IllegalStateException(msg)
                }

                val root = JSONObject(responseBody)
                val choices = root.optJSONArray("choices")
                if (choices == null || choices.length() == 0) throw Exception("模型未返回任何内容")

                val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content") ?: ""

                // 尝试从文本回复中解析图片（URL 或 Markdown 图片）
                val result = parseImageFromText(content)
                result.getOrThrow()
            }
        }.onFailure {
            Log.e(TAG, "OpenAI Request Failed", it)
        }
    }

    private fun parseImageFromText(text: String?): Result<Bitmap> {
        if (text.isNullOrBlank()) return Result.failure(Exception("模型返回内容为空"))

        // 1. 尝试匹配 Markdown 图片链接 ![...](url)
        val mdPattern = Pattern.compile("!\\[.*?\\]\\((.*?)\\)")
        val mdMatcher = mdPattern.matcher(text)
        if (mdMatcher.find()) {
            val url = mdMatcher.group(1)
            return downloadImage(url)
        }

        // 2. 尝试匹配纯 URL (http/https 开头, 图片格式结尾)
        val urlPattern = Pattern.compile("(https?://\\S+\\.(?:png|jpg|jpeg|webp))", Pattern.CASE_INSENSITIVE)
        val urlMatcher = urlPattern.matcher(text)
        if (urlMatcher.find()) {
            val url = urlMatcher.group(1)
            return downloadImage(url)
        }

        // 3. 尝试匹配 Base64 (如果有些中转商直接返回 Base64 文本)
        // 简单的启发式检查：是否长得像 base64 且没有空格
        if (text.length > 100 && !text.contains(" ") && (text.startsWith("/9j") || text.startsWith("iVBOR"))) {
            return decodeBase64(text)
        }

        return Result.failure(Exception("模型返回了文本，但无法从中解析出图片。返回内容预览: ${text.take(100)}..."))
    }

    private fun downloadImage(url: String?): Result<Bitmap> {
        if (url.isNullOrBlank()) return Result.failure(Exception("解析到的图片 URL 为空"))
        Log.d(TAG, "Downloading image from URL: $url")
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载图片失败: ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException("图片流为空")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw Exception("无法解码下载的图片")
                Result.success(bitmap)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decodeBase64(base64: String): Result<Bitmap> {
        return try {
            val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
            val resultBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            if (resultBitmap == null) {
                Result.failure(Exception("无法解码 Base64 图片数据"))
            } else {
                Result.success(resultBitmap)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
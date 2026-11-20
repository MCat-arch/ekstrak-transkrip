package com.ekstraktranskrip.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GeminiException(
    val statusCode: Int,
    override val message: String,
) : Exception(message) {

    val isAuthProblem: Boolean
        get() = statusCode == 400 || statusCode == 401 || statusCode == 403

    val isQuotaProblem: Boolean
        get() = statusCode == 429
}

/**
 * Minimal Gemini REST client.
 *
 * HttpURLConnection, not OkHttp: one endpoint, no interceptor needs, no extra
 * dependency to keep in sync. Streaming uses the SSE variant.
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String,
) {

    data class Message(val role: String, val text: String) {
        companion object {
            const val USER = "user"
            const val MODEL = "model"
        }
    }

    suspend fun generate(
        history: List<Message>,
        systemInstruction: String? = null,
        temperature: Double = 0.2,
        jsonSchema: JSONObject? = null,
    ): String = withContext(Dispatchers.IO) {
        val connection = openConnection(stream = false)
        try {
            connection.outputStream.use { it.write(buildBody(history, systemInstruction, temperature, jsonSchema).toString().toByteArray()) }
            val code = connection.responseCode
            val body = readBody(connection, code)
            if (code !in 200..299) throw parseError(code, body)
            extractText(body) ?: throw GeminiException(code, emptyResponseMessage(body))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams text deltas. Callers append them; the final UI text is the
     * concatenation of everything emitted.
     */
    fun stream(
        history: List<Message>,
        systemInstruction: String? = null,
        temperature: Double = 0.3,
    ): Flow<String> = flow {
        val connection = openConnection(stream = true)
        try {
            connection.outputStream.use {
                it.write(buildBody(history, systemInstruction, temperature, null).toString().toByteArray())
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw parseError(code, readBody(connection, code))
            }
            val reader: BufferedReader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            reader.useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val delta = extractText(payload)
                    if (!delta.isNullOrEmpty()) emit(delta)
                }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Model ids that can call generateContent, for the settings screen. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/models?pageSize=100")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 40_000
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            val code = connection.responseCode
            val body = readBody(connection, code)
            if (code !in 200..299) throw parseError(code, body)

            val root = JSONObject(body)
            val models = root.optJSONArray("models") ?: JSONArray()
            buildList {
                for (i in 0 until models.length()) {
                    val model = models.optJSONObject(i) ?: continue
                    val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                    val supportsGenerate = (0 until methods.length())
                        .any { methods.optString(it) == "generateContent" }
                    if (!supportsGenerate) continue
                    val name = model.optString("name").removePrefix("models/")
                    if (name.isNotBlank()) add(name)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // ------------------------------------------------------------------ http

    private fun openConnection(stream: Boolean): HttpURLConnection {
        val method = if (stream) "streamGenerateContent" else "generateContent"
        val query = if (stream) "?alt=sse" else ""
        val safeModel = URLEncoder.encode(model, "UTF-8")
        val url = URL("$BASE_URL/models/$safeModel:$method$query")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = if (stream) 120_000 else 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
    }

    private fun buildBody(
        history: List<Message>,
        systemInstruction: String?,
        temperature: Double,
        jsonSchema: JSONObject?,
    ): JSONObject {
        val contents = JSONArray()
        for (message in history) {
            contents.put(
                JSONObject().apply {
                    put("role", message.role)
                    put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", message.text)),
                    )
                },
            )
        }

        return JSONObject().apply {
            put("contents", contents)
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", temperature)
                    put("maxOutputTokens", 8192)
                    if (jsonSchema != null) {
                        put("responseMimeType", "application/json")
                        put("responseSchema", jsonSchema)
                    }
                },
            )
            if (!systemInstruction.isNullOrBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemInstruction)),
                    ),
                )
            }
        }
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun parseError(code: Int, body: String): GeminiException {
        val message = runCatching {
            val error = JSONObject(body).optJSONObject("error") ?: return@runCatching null
            val status = error.optString("status")
            val detail = error.optString("message")
            listOfNotNull(status.takeIf { it.isNotBlank() }, detail.takeIf { it.isNotBlank() })
                .joinToString(": ")
        }.getOrNull()

        return GeminiException(
            code,
            message?.takeIf { it.isNotBlank() } ?: "HTTP $code dari Gemini.",
        )
    }

    private fun extractText(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val candidate = candidates.optJSONObject(0) ?: return null
        val content = candidate.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null

        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text").orEmpty()
            builder.append(text)
        }
        return builder.toString()
    }

    private fun emptyResponseMessage(body: String): String {
        val reason = runCatching {
            val root = JSONObject(body)
            root.optJSONObject("promptFeedback")?.optString("blockReason")
                ?: root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optString("finishReason")
        }.getOrNull()

        return when (reason) {
            "SAFETY" -> "Gemini menolak memproses karena filter keamanan. Coba ringkas transkrip yang lebih pendek."
            "RECITATION" -> "Gemini menolak karena potensi kutipan. Coba lagi atau pendekkan transkrip."
            null, "" -> "Gemini mengembalikan respons kosong."
            else -> "Gemini berhenti tanpa hasil ($reason)."
        }
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}

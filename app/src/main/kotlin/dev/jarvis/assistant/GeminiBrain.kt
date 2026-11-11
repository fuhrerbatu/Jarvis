package dev.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Gemini (Interactions API) ile çalışan sağlayıcı. Google AI Studio'nun
 * ücretsiz katmanıyla, kart bilgisi girmeden kullanılabilir.
 *
 * Durumsuz çalışır: her istekte tüm geçmiş gönderilir ve Google tarafında saklanmaz.
 */
class GeminiBrain(
    private val tools: PhoneTools,
    private val apiKey: String,
    private val model: String,
) : Brain {

    override suspend fun run(command: String, onStatus: (String) -> Unit): String {
        val history = JSONArray().put(
            JSONObject().put("type", "user_input").put("content", tools.firstMessage(command))
        )
        var steps = 0
        while (true) {
            onStatus(if (steps == 0) "Düşünüyorum…" else "Çalışıyorum…")
            val response = call(history)
            val modelSteps = response.optJSONArray("steps") ?: JSONArray()
            val calls = mutableListOf<JSONObject>()
            val text = StringBuilder()
            for (i in 0 until modelSteps.length()) {
                val step = modelSteps.getJSONObject(i)
                // Modelin adımları (düşünce imzaları dahil) aynen geri gönderilmelidir.
                history.put(step)
                when (step.optString("type")) {
                    "function_call" -> calls += step
                    "model_output" -> {
                        val content = step.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            val part = content.getJSONObject(j)
                            if (part.optString("type") == "text") text.append(part.optString("text"))
                        }
                    }
                }
            }
            if (calls.isEmpty()) {
                return when (response.optString("status")) {
                    "completed", "" -> text.toString().trim().ifBlank { "Tamamlandı." }
                    "incomplete" -> text.toString().trim() + "\n\n(Yanıt yarım kalmış olabilir.)"
                    else -> text.toString().trim().ifBlank { "Bu isteği yerine getiremedim (${response.optString("status")})." }
                }
            }
            for (call in calls) {
                steps++
                val output = if (steps > PhoneTools.MAX_STEPS) PhoneTools.STEP_LIMIT else {
                    tools.executeSafely(call.optString("name"), toMap(call.optJSONObject("arguments")), onStatus)
                }
                val result = JSONArray().put(JSONObject().put("type", "text").put("text", output.text))
                if (output.imageBase64 != null) {
                    result.put(
                        JSONObject().put("type", "image").put("mime_type", "image/jpeg").put("data", output.imageBase64)
                    )
                }
                history.put(
                    JSONObject()
                        .put("type", "function_result")
                        .put("name", call.optString("name"))
                        .put("call_id", call.optString("id"))
                        .put("result", result)
                )
            }
        }
    }

    private suspend fun call(history: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("system_instruction", SYSTEM_PROMPT)
            .put("tools", TOOLS)
            .put("input", history)
            .toString()
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("x-goog-api-key", apiKey)
                .post(body.toRequestBody(JSON))
                .build()
            val (code, payload) = try {
                runInterruptible(Dispatchers.IO) {
                    http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
                }
            } catch (e: IOException) {
                throw IllegalStateException("İnternet bağlantısı kurulamadı.", e)
            }
            if (code in 200..299) return JSONObject(payload)

            // Hata gövdesi bazen tek öğeli bir dizi içinde gelir.
            val message = runCatching {
                val trimmed = payload.trim()
                val root = if (trimmed.startsWith("[")) JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
                root.getJSONObject("error").optString("message")
            }.getOrNull().orEmpty()
            // Ücretsiz katmanda dakikalık sınır sık aşılır; kısa bir bekleyişle kendiliğinden düzelir.
            if ((code == 429 || code == 503) && attempt < 2 && "per day" !in message.lowercase()) {
                attempt++
                delay(if (code == 429) 20_000L else 4_000L)
                continue
            }
            throw IllegalStateException(
                when (code) {
                    400 -> if ("API key" in message) "Gemini API anahtarı geçersiz. Jarvis uygulamasından kontrol et."
                    else "Gemini isteği reddetti: $message"
                    401, 403 -> "Gemini API anahtarı geçersiz ya da bu modele erişimi yok."
                    404 -> "\"$model\" adında bir Gemini modeli bulunamadı."
                    429 -> "Gemini'nin ücretsiz kullanım sınırı doldu. Biraz sonra (günlük sınırsa yarın) tekrar dene."
                    else -> "Gemini API hatası ($code): $message"
                }
            )
        }
    }

    private fun toMap(json: JSONObject?): Map<String, Any?> {
        if (json == null) return emptyMap()
        return json.keys().asSequence().associateWith { key -> json.opt(key).takeUnless { it == JSONObject.NULL } }
    }

    companion object {
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        private val SYSTEM_PROMPT = PhoneTools.systemPrompt(webSearch = false)

        private val TOOLS: JSONArray = JSONArray().apply {
            for (spec in PhoneTools.SPECS) {
                put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", spec.name)
                        .put("description", spec.description)
                        .put(
                            "parameters",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject(spec.properties.mapValues { JSONObject(it.value) }))
                                .put("required", JSONArray(spec.required))
                        )
                )
            }
        }
    }
}

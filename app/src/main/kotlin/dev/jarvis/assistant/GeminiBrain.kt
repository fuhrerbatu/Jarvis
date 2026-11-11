package dev.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            val response = call(history, onStatus)
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

    private suspend fun call(history: JSONArray, onStatus: (String) -> Unit): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("system_instruction", SYSTEM_PROMPT)
            .put("tools", TOOLS)
            // Telefon kullanımı kısa adımlardan oluşur; düşük düşünme düzeyi yanıtı belirgin hızlandırır.
            .put("generation_config", JSONObject().put("thinking_level", "low"))
            .put("input", history)
            .toString()
        var attempt = 0
        while (true) {
            val started = System.currentTimeMillis()
            val (code, payload) = post(apiKey, body)
            DebugLog.add("Gemini HTTP $code (${System.currentTimeMillis() - started} ms)")
            if (code in 200..299) return JSONObject(payload)

            val message = errorMessage(payload)
            DebugLog.add("Gemini hata: $message")
            // Ücretsiz katmanda dakikalık sınır sık aşılır; bir kez, görünür şekilde bekleyip tekrar denenir.
            if ((code == 429 || code == 503) && attempt < 1 && "per day" !in message.lowercase()) {
                attempt++
                val wait = if (code == 429) 15 else 4
                onStatus("Gemini meşgul, $wait sn sonra tekrar deniyorum…")
                delay(wait * 1000L)
                onStatus("Düşünüyorum…")
                continue
            }
            throw IllegalStateException(
                when (code) {
                    400 -> if ("API key" in message) "Gemini API anahtarı geçersiz. Jarvis uygulamasından kontrol et."
                    else "Gemini isteği reddetti: $message"
                    401, 403 -> "Gemini API anahtarı geçersiz ya da bu modele erişimi yok: $message"
                    404 -> "\"$model\" adında bir Gemini modeli bulunamadı."
                    429 -> "Gemini'nin ücretsiz kullanım sınırı doldu. Biraz sonra (günlük sınırsa yarın) tekrar dene.\n\n$message"
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
        private const val CALL_TIMEOUT_S = 75L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            // Bazı mobil ağlarda HTTP/2 bağlantıları sessizce takılabiliyor; HTTP/1.1 daha öngörülebilir.
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .eventListenerFactory { Timeline() }
            .build()

        /** İsteği gönderir; HTTP kodunu ve gövdeyi döndürür. Bağlantı aşamaları kayda yazılır. */
        private suspend fun post(apiKey: String, body: String): Pair<Int, String> {
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("x-goog-api-key", apiKey)
                .post(body.toRequestBody(JSON))
                .build()
            val started = System.currentTimeMillis()
            return try {
                runInterruptible(Dispatchers.IO) {
                    http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
                }
            } catch (e: IOException) {
                // Kullanıcı işi iptal ettiyse bu bir hata değildir.
                currentCoroutineContext().ensureActive()
                DebugLog.add("Gemini bağlantı hatası (${System.currentTimeMillis() - started} ms): $e")
                throw IllegalStateException(
                    when (e) {
                        is java.net.UnknownHostException -> "Google sunucusunun adresi çözülemedi. İnternet bağlantını kontrol et."
                        is java.io.InterruptedIOException ->
                            "Gemini ${(System.currentTimeMillis() - started) / 1000} saniyede yanıt vermedi (${e.message ?: "zaman aşımı"})."
                        else -> "Gemini'ye bağlanılamadı: ${e.message ?: e.javaClass.simpleName}"
                    }, e
                )
            }
        }

        /** Araçsız, küçük bir istekle anahtarın ve bağlantının çalıştığını sınar. */
        suspend fun ping(apiKey: String, model: String): String {
            DebugLog.add("── bağlantı testi  [$model]")
            val body = JSONObject()
                .put("model", model)
                .put("store", false)
                .put("generation_config", JSONObject().put("thinking_level", "low"))
                .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", "Yalnızca 'hazır' yaz.")))
                .toString()
            val started = System.currentTimeMillis()
            val (code, payload) = post(apiKey, body)
            val elapsed = System.currentTimeMillis() - started
            DebugLog.add("test HTTP $code ($elapsed ms): ${payload.replace(Regex("\\s+"), " ").take(400)}")
            return if (code in 200..299) "Bağlantı çalışıyor ($elapsed ms)." else "Gemini HTTP $code döndürdü: ${errorMessage(payload)}"
        }

        /** Hata gövdesi bazen tek öğeli bir dizi içinde gelir. */
        private fun errorMessage(payload: String): String = runCatching {
            val trimmed = payload.trim()
            val root = if (trimmed.startsWith("[")) JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
            root.getJSONObject("error").optString("message")
        }.getOrNull().orEmpty().ifBlank { payload.take(300) }

        /** DNS, bağlantı, TLS ve yanıt aşamalarının sürelerini kayda yazar. */
        private class Timeline : okhttp3.EventListener() {
            private var start = 0L
            private fun mark(what: String) = DebugLog.add("  ağ +${System.currentTimeMillis() - start} ms: $what")
            override fun callStart(call: okhttp3.Call) { start = System.currentTimeMillis() }
            override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) =
                mark("DNS tamam (${inetAddressList.size} adres)")
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) =
                mark("bağlanıyor ${inetSocketAddress.address?.hostAddress} proxy=${proxy.type()}")
            override fun connectFailed(
                call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy,
                protocol: okhttp3.Protocol?, ioe: IOException,
            ) = mark("bağlantı başarısız: $ioe")
            override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) = mark("TLS tamam")
            override fun requestBodyEnd(call: okhttp3.Call, byteCount: Long) = mark("istek gönderildi ($byteCount bayt)")
            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) =
                mark("yanıt başlığı geldi (${response.code})")
            override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) = mark("yanıt tamam ($byteCount bayt)")
            override fun callFailed(call: okhttp3.Call, ioe: IOException) = mark("çağrı başarısız: $ioe")
        }

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

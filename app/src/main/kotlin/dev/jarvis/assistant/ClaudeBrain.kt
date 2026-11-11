package dev.jarvis.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Claude API ile çalışan (ücretli) sağlayıcı. Telefon araçları burada, web araması
 * Anthropic tarafında çalışır.
 */
class ClaudeBrain(
    private val tools: PhoneTools,
    apiKey: String,
    private val model: String,
) : Brain {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override suspend fun run(command: String, onStatus: (String) -> Unit): String {
        val messages = mutableListOf(MessageParam.ofUser(tools.firstMessage(command)))
        var steps = 0
        while (true) {
            onStatus(if (steps == 0) "Düşünüyorum…" else "Çalışıyorum…")
            val response = call(messages)
            messages += response.toParam()

            when (response.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> {
                    val uses = response.content().mapNotNull { it.toolUse().orElse(null) }
                    val results = uses.map { use ->
                        steps++
                        val output = if (steps > PhoneTools.MAX_STEPS) PhoneTools.STEP_LIMIT else {
                            @Suppress("UNCHECKED_CAST")
                            val input = (use._input().convert(Map::class.java) as? Map<String, Any?>).orEmpty()
                            tools.executeSafely(use.name(), input, onStatus)
                        }
                        ContentBlockParam.ofToolResult(toResult(use.id(), output))
                    }
                    messages += MessageParam.ofUserOfContentBlockParams(results)
                }
                // Sunucu tarafındaki web araması uzun sürdü: aynı konuşmayı tekrar gönderince devam eder.
                StopReason.PAUSE_TURN -> if (++steps > PhoneTools.MAX_STEPS) return finalText(response)
                StopReason.REFUSAL -> return "Bu isteği yerine getiremiyorum."
                StopReason.MAX_TOKENS ->
                    return finalText(response) + "\n\n(Yanıt uzunluk sınırına takıldı, yarım kalmış olabilir.)"
                else -> return finalText(response)
            }
        }
    }

    private fun finalText(response: Message): String =
        response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            .ifBlank { "Tamamlandı." }

    private suspend fun call(messages: List<MessageParam>): Message {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
            // Araçlar + sistem istemi + önceki adımlar önbelleğe alınır; çok adımlı görevler ucuzlar.
            .cacheControl(CacheControlEphemeral.builder().build())
            .messages(messages)
            .apply { TOOLS.forEach { addTool(it) } }
            .addTool(WebSearchTool20260209.builder().maxUses(6L).build())
            .apply {
                if (model in FALLBACK_MODELS) {
                    // Güvenlik sınıflandırıcısı isteği geri çevirirse sunucu başka bir modelle yeniden dener.
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
        return try {
            runInterruptible(Dispatchers.IO) { client.messages().create(params) }
        } catch (e: UnauthorizedException) {
            throw IllegalStateException("Claude API anahtarı geçersiz. Jarvis uygulamasından kontrol et.", e)
        } catch (e: PermissionDeniedException) {
            throw IllegalStateException("Bu API anahtarının \"$model\" modeline erişimi yok.", e)
        } catch (e: RateLimitException) {
            throw IllegalStateException("Claude API kullanım sınırına ulaşıldı, biraz sonra tekrar dene.", e)
        } catch (e: AnthropicServiceException) {
            throw IllegalStateException("Claude API hatası (${e.statusCode()}): ${e.message}", e)
        } catch (e: AnthropicIoException) {
            throw IllegalStateException("İnternet bağlantısı kurulamadı.", e)
        }
    }

    private fun toResult(id: String, output: ToolOutput): ToolResultBlockParam {
        val builder = ToolResultBlockParam.builder().toolUseId(id)
        if (output.isError) builder.isError(true)
        if (output.imageBase64 == null) return builder.content(output.text).build()
        return builder.contentOfBlocks(
            listOf(
                ToolResultBlockParam.Content.Block.ofText(output.text),
                ToolResultBlockParam.Content.Block.ofImage(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .data(output.imageBase64)
                        .build()
                ),
            )
        ).build()
    }

    companion object {
        private val FALLBACK_MODELS = setOf(
            "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-2", "claude-fable-5-1",
        )

        private val SYSTEM_PROMPT = PhoneTools.systemPrompt(webSearch = true)

        private val TOOLS: List<Tool> = PhoneTools.SPECS.map { spec ->
            Tool.builder()
                .name(spec.name)
                .description(spec.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder().apply {
                                spec.properties.forEach { (key, schema) ->
                                    putAdditionalProperty(key, JsonValue.from(schema))
                                }
                            }.build()
                        )
                        .required(spec.required)
                        .build()
                )
                .build()
        }
    }
}

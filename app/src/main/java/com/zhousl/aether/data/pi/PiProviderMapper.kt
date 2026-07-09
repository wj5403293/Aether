package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LlmCustomHeader
import com.zhousl.aether.data.LlmProvider
import com.zhousl.aether.data.LlmTokenUsage
import org.json.JSONObject
import java.util.Locale

private const val DefaultContextWindow = 128_000
private const val DefaultMaxTokens = 16_384

data class PiModelConfig(
    val providerType: String,
    val piProviderId: String,
    val piApi: String,
    val modelId: String,
    val baseUrl: String,
    val apiKey: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val reasoning: Boolean = true,
    val contextWindow: Int = DefaultContextWindow,
    val maxTokens: Int = DefaultMaxTokens,
    val fauxResponse: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("provider_type", providerType)
        put("pi_provider_id", piProviderId)
        put("pi_api", piApi)
        put("model_id", modelId)
        put("base_url", baseUrl)
        put("api_key", apiKey)
        put("custom_headers", JSONObject().apply {
            customHeaders.forEach { (name, value) -> put(name, value) }
        })
        put("reasoning", reasoning)
        put("context_window", contextWindow)
        put("max_tokens", maxTokens)
        if (fauxResponse.isNotBlank()) put("faux_response", fauxResponse)
    }
}

data class PiCompletionResult(
    val assistantText: String,
    val reasoningText: String = "",
    val assistantMessage: JSONObject = JSONObject(),
    val usage: LlmTokenUsage? = null,
    val provider: String = "",
    val model: String = "",
    val responseId: String = "",
    val stopReason: String = "",
    val errorMessage: String = "",
)

fun AppSettings.toPiModelConfig(): PiModelConfig =
    PiModelConfig(
        providerType = provider.storageValue,
        piProviderId = provider.toPiProviderId(baseUrl),
        piApi = provider.toPiApi(),
        modelId = modelId.trim(),
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        customHeaders = customHeaders.toPiHeaderMap(),
        reasoning = true,
    )

fun fauxPiModelConfig(
    modelId: String = "faux-1",
    response: String = "ok",
): PiModelConfig =
    PiModelConfig(
        providerType = "faux",
        piProviderId = "faux",
        piApi = "faux",
        modelId = modelId,
        baseUrl = "http://localhost:0",
        fauxResponse = response,
    )

fun JSONObject.toPiCompletionResult(): PiCompletionResult =
    PiCompletionResult(
        assistantText = optString("assistant_text"),
        reasoningText = optString("reasoning_text"),
        assistantMessage = optJSONObject("assistant_message") ?: JSONObject(),
        usage = optJSONObject("usage")?.toLlmTokenUsage(),
        provider = optString("provider"),
        model = optString("model"),
        responseId = optString("response_id"),
        stopReason = optString("stop_reason"),
        errorMessage = optString("error_message"),
    )

private fun LlmProvider.toPiProviderId(baseUrl: String): String = when (this) {
    LlmProvider.OpenAiResponses -> "openai"
    LlmProvider.AnthropicMessages -> "anthropic"
    LlmProvider.VertexExpress -> "google-vertex"
    LlmProvider.OpenAiCompatible -> "aether-${stableProviderSuffix(baseUrl)}"
}

private fun LlmProvider.toPiApi(): String = when (this) {
    LlmProvider.OpenAiResponses -> "openai-responses"
    LlmProvider.OpenAiCompatible -> "openai-completions"
    LlmProvider.AnthropicMessages -> "anthropic-messages"
    LlmProvider.VertexExpress -> "google-vertex"
}

private fun stableProviderSuffix(baseUrl: String): String =
    baseUrl
        .trim()
        .lowercase(Locale.US)
        .ifBlank { "custom" }
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(48)
        .ifBlank { "custom" }

private fun List<LlmCustomHeader>.toPiHeaderMap(): Map<String, String> =
    mapNotNull { header ->
        val name = header.name.trim()
        if (name.isBlank()) {
            null
        } else {
            name to header.value
        }
    }.toMap()

private fun JSONObject.toLlmTokenUsage(): LlmTokenUsage? {
    val usage = LlmTokenUsage(
        inputTokens = optPositiveLong("input_tokens"),
        outputTokens = optPositiveLong("output_tokens"),
        totalTokens = optPositiveLong("total_tokens"),
        reasoningTokens = optPositiveLong("reasoning_tokens"),
        cachedInputTokens = optPositiveLong("cached_input_tokens"),
    ).withMissingTotalResolved()
    return usage.takeIf {
        it.inputTokens != null ||
            it.outputTokens != null ||
            it.totalTokens != null ||
            it.reasoningTokens != null ||
            it.cachedInputTokens != null
    }
}

private fun JSONObject.optPositiveLong(name: String): Long? =
    if (has(name) && !isNull(name)) {
        optLong(name).takeIf { it >= 0L }
    } else {
        null
    }

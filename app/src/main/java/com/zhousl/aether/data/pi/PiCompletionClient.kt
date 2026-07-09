package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LlmImagePart
import com.zhousl.aether.data.LlmMessage
import com.zhousl.aether.data.LlmTextPart
import org.json.JSONArray
import org.json.JSONObject

class PiCompletionClient(
    private val bridge: PiKernelBridge,
) {
    suspend fun completeOnce(
        settings: AppSettings,
        systemPrompt: String,
        messages: List<LlmMessage>,
        disableReasoning: Boolean = false,
        stream: Boolean = false,
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
    ): Result<PiCompletionResult> = runCatching {
        val payload = JSONObject().apply {
            put("model_config", settings.toPiModelConfig().toJson())
            put("system_prompt", systemPrompt)
            put("messages", messages.toPiJson())
            put("stream", stream)
            if (disableReasoning) put("reasoning", "off")
        }
        bridge.completeOnce(payload, onEvent).toPiCompletionResult()
    }
}

fun List<LlmMessage>.toPiJson(): JSONArray = JSONArray().apply {
    forEach { message ->
        put(message.toPiJson())
    }
}

fun LlmMessage.toPiJson(): JSONObject = JSONObject().apply {
    put("role", role)
    val content = JSONArray()
    contentParts.forEach { part ->
        when (part) {
            is LlmTextPart -> content.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", part.text)
                }
            )
            is LlmImagePart -> content.put(
                JSONObject().apply {
                    put("type", "image")
                    put("mime_type", part.mimeType)
                    put("data", part.base64Data)
                }
            )
        }
    }
    put("content", content)
    providerPayload?.let { put("provider_payload", it) }
}

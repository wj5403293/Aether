package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LlmCustomHeader
import com.zhousl.aether.data.LlmImagePart
import com.zhousl.aether.data.LlmMessage
import com.zhousl.aether.data.LlmProvider
import com.zhousl.aether.data.LlmTextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiProviderMapperTest {
    @Test
    fun openAiResponsesMapsToPiOpenAiResponses() {
        val config = AppSettings(
            provider = LlmProvider.OpenAiResponses,
            apiKey = "sk-test",
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-5.4",
        ).toPiModelConfig()

        assertEquals("openai_responses", config.providerType)
        assertEquals("openai", config.piProviderId)
        assertEquals("openai-responses", config.piApi)
        assertEquals("gpt-5.4", config.modelId)
        assertEquals("sk-test", config.apiKey)
    }

    @Test
    fun anthropicMapsToPiAnthropicMessages() {
        val config = AppSettings(
            provider = LlmProvider.AnthropicMessages,
            apiKey = "anthropic-key",
            baseUrl = "https://api.anthropic.com/v1",
            modelId = "claude-sonnet-4-5",
        ).toPiModelConfig()

        assertEquals("anthropic", config.piProviderId)
        assertEquals("anthropic-messages", config.piApi)
    }

    @Test
    fun vertexMapsToPiGoogleVertex() {
        val config = AppSettings(
            provider = LlmProvider.VertexExpress,
            apiKey = "vertex-key",
            baseUrl = "https://aiplatform.googleapis.com/v1",
            modelId = "gemini-2.5-flash",
        ).toPiModelConfig()

        assertEquals("google-vertex", config.piProviderId)
        assertEquals("google-vertex", config.piApi)
    }

    @Test
    fun openAiCompatibleMapsToCustomOpenAiCompletionsProvider() {
        val config = AppSettings(
            provider = LlmProvider.OpenAiCompatible,
            apiKey = "custom-key",
            baseUrl = "https://example.test/v1",
            modelId = "custom-model",
            customHeaders = listOf(
                LlmCustomHeader("X-Test", "yes"),
                LlmCustomHeader(" ", "ignored"),
            ),
        ).toPiModelConfig()

        assertEquals("openai_compatible", config.providerType)
        assertTrue(config.piProviderId.startsWith("aether-"))
        assertEquals("openai-completions", config.piApi)
        assertEquals(mapOf("X-Test" to "yes"), config.customHeaders)
        assertEquals("custom-model", config.modelId)
    }

    @Test
    fun llmMessagesMapToPiJsonWithTextAndImages() {
        val json = listOf(
            LlmMessage(
                role = "user",
                contentParts = listOf(
                    LlmTextPart("hello"),
                    LlmImagePart("image/png", "abc123"),
                ),
            )
        ).toPiJson()

        val message = json.getJSONObject(0)
        assertEquals("user", message.optString("role"))
        val content = message.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).optString("type"))
        assertEquals("hello", content.getJSONObject(0).optString("text"))
        assertEquals("image", content.getJSONObject(1).optString("type"))
        assertEquals("image/png", content.getJSONObject(1).optString("mime_type"))
        assertEquals("abc123", content.getJSONObject(1).optString("data"))
        assertFalse(message.has("provider_payload"))
    }
}

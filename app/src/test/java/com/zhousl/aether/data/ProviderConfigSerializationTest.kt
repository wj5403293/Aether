package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigSerializationTest {
    @Test
    fun importedProviderConfigBackfillsMissingNameAndBaseUrl() {
        val configs = parseProviderConfigs(
            JSONArray().put(
                JSONObject()
                    .put("providerType", LlmProvider.AnthropicMessages.storageValue)
                    .put("name", "")
                    .put("baseUrl", "")
                    .put("modelId", "claude-test")
            ).toString()
        )

        val config = configs.single()
        assertEquals(LlmProvider.AnthropicMessages.displayName, config.name)
        assertEquals(LlmProvider.AnthropicMessages.defaultBaseUrl, config.baseUrl)

        val option = configs.availableModelOptions().single()
        assertEquals(LlmProvider.AnthropicMessages.defaultBaseUrl, option.baseUrl)
        assertEquals("claude-test", option.modelId)
    }

    @Test
    fun availableModelOptionsSkipsConfigsWithBlankBaseUrl() {
        val options = listOf(
            LlmProviderConfig(
                id = "bad-provider",
                providerId = "bad",
                name = "Bad",
                providerType = LlmProvider.OpenAiCompatible,
                apiKey = "",
                baseUrl = "",
                modelId = "model-a",
            )
        ).availableModelOptions()

        assertTrue(options.isEmpty())
    }

    @Test
    fun availableModelOptionsSkipsDisabledProvidersAndModels() {
        val options = listOf(
            LlmProviderConfig(
                id = "enabled-provider",
                providerId = "enabled",
                name = "Enabled",
                providerType = LlmProvider.OpenAiCompatible,
                apiKey = "",
                baseUrl = "https://enabled.example/v1",
                modelId = "enabled-model",
                cachedModels = listOf("enabled-model", "disabled-model"),
                enabledModelIds = listOf("enabled-model"),
                isEnabled = true,
            ),
            LlmProviderConfig(
                id = "disabled-provider",
                providerId = "disabled",
                name = "Disabled",
                providerType = LlmProvider.OpenAiCompatible,
                apiKey = "",
                baseUrl = "https://disabled.example/v1",
                modelId = "disabled-provider-model",
                cachedModels = listOf("disabled-provider-model"),
                enabledModelIds = listOf("disabled-provider-model"),
                isEnabled = false,
            ),
        ).availableModelOptions()

        assertEquals(listOf("enabled/enabled-model"), options.map { it.fullLabel })
    }

    @Test
    fun availableModelOptionsGroupsMatchingModelProviderPrefixes() {
        val options = listOf(
            LlmProviderConfig(
                id = "first-site",
                providerId = "site_a",
                name = "Site A",
                providerType = LlmProvider.OpenAiCompatible,
                apiKey = "",
                baseUrl = "https://site-a.example/v1",
                modelId = "google/gemini-pro",
                cachedModels = listOf("google/gemini-pro", "openai/gpt-5"),
                enabledModelIds = listOf("google/gemini-pro", "openai/gpt-5"),
            ),
            LlmProviderConfig(
                id = "second-site",
                providerId = "site_b",
                name = "Site B",
                providerType = LlmProvider.OpenAiCompatible,
                apiKey = "",
                baseUrl = "https://site-b.example/v1",
                modelId = "openai/gpt-4o",
                cachedModels = listOf("anthropic/claude-sonnet", "openai/gpt-4o"),
                enabledModelIds = listOf("anthropic/claude-sonnet", "openai/gpt-4o"),
            ),
        ).availableModelOptions()

        assertEquals(
            listOf(
                "site_b/anthropic/claude-sonnet",
                "site_a/google/gemini-pro",
                "site_a/openai/gpt-5",
                "site_b/openai/gpt-4o",
            ),
            options.map { it.fullLabel },
        )
    }

    @Test
    fun providerConfigRoundTripsCustomHeaders() {
        val serialized = serializeProviderConfigs(
            listOf(
                LlmProviderConfig(
                    providerId = "openrouter",
                    name = "OpenRouter",
                    providerType = LlmProvider.OpenAiCompatible,
                    apiKey = "test-key",
                    baseUrl = "https://openrouter.ai/api/v1",
                    modelId = "openai/gpt-test",
                    customHeaders = listOf(
                        LlmCustomHeader("HTTP-Referer", "https://example.com"),
                        LlmCustomHeader("X-Title", "Aether"),
                    ),
                )
            )
        )

        val config = parseProviderConfigs(serialized).single()

        assertEquals("HTTP-Referer", config.customHeaders[0].name)
        assertEquals("https://example.com", config.customHeaders[0].value)
        assertEquals("X-Title", config.customHeaders[1].name)
        assertEquals("Aether", config.customHeaders[1].value)
        assertEquals(config.customHeaders, listOf(config).availableModelOptions().single().customHeaders)
    }

    @Test
    fun providerConfigRoundTripsManualModelIdsSeparatelyFromCachedModels() {
        val serialized = serializeProviderConfigs(
            listOf(
                LlmProviderConfig(
                    providerId = "custom",
                    name = "Custom",
                    providerType = LlmProvider.OpenAiCompatible,
                    apiKey = "test-key",
                    baseUrl = "https://api.example.com/v1",
                    modelId = "manual-a",
                    manualModelIds = listOf("manual-a", "manual-b"),
                    cachedModels = listOf("fetched-a"),
                    enabledModelIds = listOf("manual-a", "manual-b", "fetched-a"),
                )
            )
        )

        val config = parseProviderConfigs(serialized).single()

        assertEquals(listOf("manual-a", "manual-b"), config.manualModelIds)
        assertEquals(listOf("fetched-a"), config.cachedModels)
        assertEquals(listOf("fetched-a", "manual-a", "manual-b"), config.availableModels())
    }

    @Test
    fun providerConfigDropsEnabledManualModelWhenManualModelIdIsRemoved() {
        val config = LlmProviderConfig(
            providerId = "custom",
            name = "Custom",
            providerType = LlmProvider.OpenAiCompatible,
            apiKey = "test-key",
            baseUrl = "https://api.example.com/v1",
            modelId = "fetched-a",
            manualModelIds = emptyList(),
            cachedModels = listOf("fetched-a"),
            enabledModelIds = listOf("manual-a", "fetched-a"),
        )

        assertEquals(listOf("fetched-a"), config.availableModels())
        assertEquals(listOf("fetched-a"), config.enabledModels())
    }
}

package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelCatalogClientTest {
    @Test
    fun fetchModelsIncludesAetherUserAgentAndCustomHeaders() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"gpt-test"}]}""")
        )
        server.start()

        try {
            val result = ProviderModelCatalogClient.fetchModels(
                LlmProviderConfig(
                    providerId = "openai",
                    name = "OpenAI",
                    piProviderId = "openai-compatible",
                    apiKey = "test-key",
                    baseUrl = server.url("/v1").toString(),
                    modelId = "gpt-test",
                    customHeaders = listOf(LlmCustomHeader("X-Aether-Test", "models")),
                )
            )

            assertEquals(listOf("gpt-test"), result.models)
            val request = server.takeRequest()
            assertEquals(AetherLlmUserAgent, request.getHeader("User-Agent"))
            assertEquals("models", request.getHeader("X-Aether-Test"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun builtInProviderUsesPiCatalogInsteadOfAndroidCompatibilityPaths() = runBlocking {
        val result = ProviderModelCatalogClient.fetchModels(
            LlmProviderConfig(
                providerId = "vertex",
                name = "Vertex",
                piProviderId = "google-vertex",
                apiKey = "test-key",
                baseUrl = "",
                modelId = "gemini-2.5-flash",
            )
        )

        assertEquals(null, result.error)
        assertEquals(listOf("gemini-2.5-flash"), result.models)
    }
}

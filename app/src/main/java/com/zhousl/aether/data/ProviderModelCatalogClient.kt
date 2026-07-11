package com.zhousl.aether.data

import com.zhousl.aether.data.pi.PiKernelBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ProviderModelCatalogClient {

    data class FetchModelsResult(
        val models: List<String>,
        val error: String? = null,
    )

    suspend fun fetchModels(
        config: LlmProviderConfig,
        piKernelBridge: PiKernelBridge? = null,
    ): FetchModelsResult = withContext(Dispatchers.IO) {
        try {
            val definition = PiProviderCatalog.resolve(
                config.piProviderId,
            )
            if (definition.isBuiltIn) {
                return@withContext fetchPiBuiltinModels(
                    definition = definition,
                    piKernelBridge = piKernelBridge,
                )
            }
            fetchOpenAiModels(config)
        } catch (e: Exception) {
            FetchModelsResult(emptyList(), e.message ?: "Unknown error")
        }
    }

    private suspend fun fetchPiBuiltinModels(
        definition: PiProviderDefinition,
        piKernelBridge: PiKernelBridge?,
    ): FetchModelsResult {
        if (piKernelBridge == null) {
            return FetchModelsResult(listOf(definition.defaultModelId).filter(String::isNotBlank))
        }
        val providers = piKernelBridge.listProviders().optJSONArray("providers")
            ?: return FetchModelsResult(emptyList(), "No provider catalog was returned.")
        for (providerIndex in 0 until providers.length()) {
            val provider = providers.optJSONObject(providerIndex) ?: continue
            if (provider.optString("id") != definition.id) continue
            val models = provider.optJSONArray("models") ?: return FetchModelsResult(emptyList())
            return FetchModelsResult(
                buildList {
                    for (modelIndex in 0 until models.length()) {
                        val modelId = models.optJSONObject(modelIndex)
                            ?.optString("id")
                            ?.trim()
                            .orEmpty()
                        if (modelId.isNotBlank()) add(modelId)
                    }
                }.distinct(),
            )
        }
        return FetchModelsResult(emptyList(), "Provider ${definition.id} is unavailable.")
    }

    private fun fetchOpenAiModels(config: LlmProviderConfig): FetchModelsResult {
        val baseUrl = config.baseUrl.trimEnd('/')
        val modelsUrl = when {
            baseUrl.endsWith("/responses") -> baseUrl.replace("/responses", "/models")
            baseUrl.endsWith("/chat/completions") -> baseUrl.replace("/chat/completions", "/models")
            baseUrl.endsWith("/v1") -> "$baseUrl/models"
            else -> "$baseUrl/models"
        }

        val connection = URL(modelsUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.applyAetherLlmHeaders(config.customHeaders)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000

        return try {
            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseText)
                val dataArray = json.optJSONArray("data")
                val models = mutableListOf<String>()
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val modelObj = dataArray.optJSONObject(i)
                        val modelId = modelObj?.optString("id")
                        if (!modelId.isNullOrBlank()) {
                            models.add(modelId)
                        }
                    }
                }
                // Sort models: prefer chat/gpt models first
                models.sortWith(compareBy(
                    { if (it.contains("gpt") || it.contains("chat")) 0 else 1 },
                    { it }
                ))
                FetchModelsResult(models)
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP ${connection.responseCode}"
                FetchModelsResult(emptyList(), errorText)
            }
        } finally {
            connection.disconnect()
        }
    }

}

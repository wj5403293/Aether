package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAiCompatibleClientProviderFormatsTest {
    private val client = OpenAiCompatibleClient()

    @Test
    fun openAiResponsesUsesResponsesEndpointAndToolShape() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "output": [
                        {
                          "type": "message",
                          "role": "assistant",
                          "content": [
                            { "type": "output_text", "text": "I'll read it." }
                          ]
                        },
                        {
                          "type": "function_call",
                          "call_id": "call_1",
                          "name": "read",
                          "arguments": "{\"path\":\"README.md\"}"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val settings = AppSettings(
                provider = LlmProvider.OpenAiResponses,
                apiKey = "test-key",
                baseUrl = server.url("/v1").toString(),
                modelId = "gpt-5.4",
            )
            val conversation = client.buildConversation(
                settings = settings,
                messages = listOf(LlmMessage("user", listOf(LlmTextPart("Read README.md")))),
            )

            val result = client.createChatCompletion(
                settings = settings,
                systemPrompt = "Be concise.",
                conversation = conversation,
                tools = listOf(readTool()),
            ).getOrThrow()

            assertEquals("I'll read it.", result.assistantText)
            assertEquals("read", result.toolCalls.single().name)
            assertEquals("""{"path":"README.md"}""", result.toolCalls.single().arguments)

            val request = server.takeRequest()
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))

            val payload = JSONObject(request.body.readUtf8())
            assertEquals(false, payload.getBoolean("store"))
            assertEquals("Be concise.", payload.getString("instructions"))
            assertEquals(
                "input_text",
                payload.getJSONArray("input")
                    .getJSONObject(0)
                    .getJSONArray("content")
                    .getJSONObject(0)
                    .getString("type"),
            )
            val tool = payload.getJSONArray("tools").getJSONObject(0)
            assertEquals("function", tool.getString("type"))
            assertEquals("read", tool.getString("name"))
            assertFalse(tool.has("function"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicMessagesUsesMessagesEndpointAndToolShape() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "role": "assistant",
                      "content": [
                        { "type": "text", "text": "I'll read it." },
                        {
                          "type": "tool_use",
                          "id": "toolu_1",
                          "name": "read",
                          "input": { "path": "README.md" }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val settings = AppSettings(
                provider = LlmProvider.AnthropicMessages,
                apiKey = "test-key",
                baseUrl = server.url("/v1").toString(),
                modelId = "claude-sonnet-4-5",
            )
            val conversation = client.buildConversation(
                settings = settings,
                messages = listOf(LlmMessage("user", listOf(LlmTextPart("Read README.md")))),
            )

            val result = client.createChatCompletion(
                settings = settings,
                systemPrompt = "Be concise.",
                conversation = conversation,
                tools = listOf(readTool()),
            ).getOrThrow()

            assertEquals("I'll read it.", result.assistantText)
            assertEquals("read", result.toolCalls.single().name)
            assertEquals("""{"path":"README.md"}""", result.toolCalls.single().arguments)

            val request = server.takeRequest()
            assertEquals("/v1/messages", request.path)
            assertEquals("test-key", request.getHeader("x-api-key"))
            assertEquals("2023-06-01", request.getHeader("anthropic-version"))

            val payload = JSONObject(request.body.readUtf8())
            assertEquals("Be concise.", payload.getString("system"))
            assertEquals(4096, payload.getInt("max_tokens"))
            assertEquals(
                "text",
                payload.getJSONArray("messages")
                    .getJSONObject(0)
                    .getJSONArray("content")
                    .getJSONObject(0)
                    .getString("type"),
            )
            val tool = payload.getJSONArray("tools").getJSONObject(0)
            assertEquals("read", tool.getString("name"))
            assertEquals("object", tool.getJSONObject("input_schema").getString("type"))
            assertFalse(tool.has("function"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicStreamingToolUseKeepsInputJsonDeltaArguments() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"type":"message_start","message":{"role":"assistant","content":[]}}

                    data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read","input":{}}}

                    data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"README.md\"}"}}

                    data: {"type":"content_block_stop","index":0}

                    data: {"type":"message_stop"}

                    """.trimIndent()
                )
        )
        server.start()

        try {
            val settings = AppSettings(
                provider = LlmProvider.AnthropicMessages,
                apiKey = "test-key",
                baseUrl = server.url("/v1").toString(),
                modelId = "claude-sonnet-4-5",
            )
            val conversation = client.buildConversation(
                settings = settings,
                messages = listOf(LlmMessage("user", listOf(LlmTextPart("Read README.md")))),
            )

            val result = client.streamChatCompletion(
                settings = settings,
                systemPrompt = "Be concise.",
                conversation = conversation,
                tools = listOf(readTool()),
            ).getOrThrow()

            assertEquals("read", result.toolCalls.single().name)
            assertEquals("README.md", JSONObject(result.toolCalls.single().arguments).getString("path"))

            val request = server.takeRequest()
            assertEquals("/v1/messages", request.path)
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertEquals(true, JSONObject(request.body.readUtf8()).getBoolean("stream"))
        } finally {
            server.shutdown()
        }
    }

    private fun readTool(): JSONObject = JSONObject(
        """
        {
          "type": "function",
          "function": {
            "name": "read",
            "description": "Read a file.",
            "parameters": {
              "type": "object",
              "properties": {
                "path": { "type": "string" }
              },
              "required": ["path"]
            },
            "strict": true
          }
        }
        """.trimIndent()
    )
}

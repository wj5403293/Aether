package com.zhousl.aether.data.pi

import com.zhousl.aether.data.ActiveSkillContext
import com.zhousl.aether.data.AetherAgentTurnResult
import com.zhousl.aether.data.AgentToolEvent
import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.InstalledSkill
import com.zhousl.aether.data.LlmMessage
import com.zhousl.aether.data.LlmProviderConfig
import com.zhousl.aether.data.McpToolBinding
import com.zhousl.aether.data.StreamingStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * First-stage Pi runner that preserves AetherAgent.runTurn callback semantics.
 *
 * It intentionally does not replace AetherAgent by default until host tool execution,
 * dynamic MCP tool registration, and Pi harness session replay are complete. The
 * JSONL/event contract here is the compatibility surface the later harness runner
 * will keep.
 */
class PiAgentRunner(
    private val bridge: PiKernelBridge,
) {
    suspend fun runTurn(
        settings: AppSettings,
        messages: List<LlmMessage>,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill> = emptyList(),
        activeSkills: List<ActiveSkillContext> = emptyList(),
        mcpToolBindings: List<McpToolBinding> = emptyList(),
        agentModeEnabled: Boolean = false,
        providerConfigs: List<LlmProviderConfig> = emptyList(),
        onToolEvent: suspend (AgentToolEvent) -> Unit = {},
        onAssistantTextDelta: suspend (String) -> Unit = {},
        onAssistantReasoningDelta: suspend (String) -> Unit = {},
        onStreamingStatus: suspend (StreamingStatus?) -> Unit = {},
    ): Result<AetherAgentTurnResult> = runCatching {
        onStreamingStatus(StreamingStatus("Thinking", "Pi bridge is running this turn."))
        val payload = JSONObject().apply {
            put("model_config", settings.toPiModelConfig().toJson())
            put("system_prompt", settings.systemPrompt)
            put("messages", messages.toPiJson())
            put("workspace_directory", workspaceDirectory)
            put("available_skills", JSONArray().apply {
                availableSkills.forEach { skill ->
                    put(
                        JSONObject().apply {
                            put("id", skill.id)
                            put("name", skill.name)
                            put("description", skill.description)
                        }
                    )
                }
            })
            put("active_skills", JSONArray().apply {
                activeSkills.forEach { skill ->
                    put(
                        JSONObject().apply {
                            put("skill_id", skill.skillId)
                            put("name", skill.name)
                            put("root_path", skill.skillRootPath)
                        }
                    )
                }
            })
            put("mcp_tool_bindings", JSONArray().apply {
                mcpToolBindings.forEach { binding ->
                    put(
                        JSONObject().apply {
                            put("server_id", binding.serverId)
                            put("server_name", binding.serverName)
                            put("tool_name", binding.toolName)
                            put("namespaced_name", binding.namespacedToolName)
                            put("description", binding.description)
                            put("input_schema", binding.inputSchema)
                        }
                    )
                }
            })
            put("agent_mode_enabled", agentModeEnabled)
            put("provider_config_count", providerConfigs.size)
        }
        val response = bridge.runTurn(payload) { event, eventPayload ->
            when (event) {
                "assistant_text_delta" -> onAssistantTextDelta(eventPayload.optString("delta"))
                "assistant_reasoning_delta" -> onAssistantReasoningDelta(eventPayload.optString("delta"))
                "tool_call_start" -> onToolEvent(eventPayload.toToolEvent(isRunning = true))
                "tool_call_delta" -> onToolEvent(eventPayload.toToolEvent(isRunning = true))
                "tool_call_end" -> onToolEvent(eventPayload.toToolEvent(isRunning = false))
                "assistant_error" -> onStreamingStatus(
                    StreamingStatus(
                        text = "Pi bridge error",
                        detail = eventPayload.optString("error_message"),
                    )
                )
            }
        }
        onStreamingStatus(null)
        val result = response.toPiCompletionResult()
        if (result.errorMessage.isNotBlank()) {
            error(result.errorMessage)
        }
        AetherAgentTurnResult(
            assistantText = result.assistantText,
            tokenUsage = result.usage,
        )
    }.also {
        onStreamingStatus(null)
    }
}

private fun JSONObject.toToolEvent(isRunning: Boolean): AgentToolEvent =
    AgentToolEvent(
        id = optString("id").ifBlank { "pi-tool-${optInt("content_index", 0)}" },
        name = optString("name").ifBlank { "tool_call" },
        argumentsJson = when (val arguments = opt("arguments")) {
            is JSONObject -> arguments.toString()
            null -> optString("delta").ifBlank { "{}" }
            else -> arguments.toString()
        },
        outputJson = null,
        isRunning = isRunning,
    )

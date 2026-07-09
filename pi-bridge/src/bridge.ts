import { createInterface } from "node:readline";
import { stdin as input, stdout as output, stderr } from "node:process";
import {
  createModels,
  createProvider,
  fauxAssistantMessage,
  fauxProvider,
  type AssistantMessage,
  type AssistantMessageEvent,
  type Context,
  type Message,
  type Model,
  type MutableModels,
  type ProviderStreams,
  type SimpleStreamOptions,
  type Usage,
} from "@earendil-works/pi-ai";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { openAIResponsesApi } from "@earendil-works/pi-ai/api/openai-responses.lazy";
import { anthropicMessagesApi } from "@earendil-works/pi-ai/api/anthropic-messages.lazy";
import { googleVertexApi } from "@earendil-works/pi-ai/api/google-vertex.lazy";

const BRIDGE_VERSION = "2.0.0-alpha.0";
const PI_AI_VERSION = "0.80.3";
const PI_AGENT_CORE_VERSION = "0.80.3";

type JsonObject = Record<string, unknown>;

interface BridgeRequest {
  id?: string;
  type?: string;
  payload?: JsonObject;
}

interface ModelConfig {
  provider_type: string;
  pi_provider_id: string;
  pi_api: string;
  model_id: string;
  base_url: string;
  api_key?: string;
  custom_headers?: Record<string, string>;
  reasoning?: boolean;
  context_window?: number;
  max_tokens?: number;
  faux_response?: string;
}

const activeControllers = new Map<string, AbortController>();
let defaultModelConfig: ModelConfig | undefined;

function writeFrame(frame: JsonObject): void {
  output.write(`${JSON.stringify(frame)}\n`);
}

function writeEvent(id: string, event: string, payload: JsonObject = {}): void {
  writeFrame({ type: "event", id, event, payload });
}

function writeResponse(id: string, payload: JsonObject = {}): void {
  writeFrame({ type: "response", id, ok: true, payload });
}

function writeError(id: string | undefined, error: unknown, code = "bridge_error"): void {
  const message = error instanceof Error ? error.message : String(error);
  writeFrame({
    type: "error",
    id: id ?? "",
    ok: false,
    error: {
      code,
      message,
    },
  });
}

function asObject(value: unknown): JsonObject {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as JsonObject) : {};
}

function asString(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function asNumber(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function asBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function normalizeHeaders(value: unknown): Record<string, string> {
  const inputHeaders = asObject(value);
  const headers: Record<string, string> = {};
  for (const [key, rawValue] of Object.entries(inputHeaders)) {
    if (!key.trim()) continue;
    if (typeof rawValue === "string") headers[key] = rawValue;
  }
  return headers;
}

function normalizeModelConfig(rawValue: unknown): ModelConfig {
  const raw = asObject(rawValue);
  const providerType = asString(raw.provider_type).trim();
  const piProviderId = asString(raw.pi_provider_id).trim();
  const piApi = asString(raw.pi_api).trim();
  const modelId = asString(raw.model_id).trim();
  const baseUrl = asString(raw.base_url).trim();
  if (!providerType) throw new Error("model_config.provider_type is required.");
  if (!piProviderId) throw new Error("model_config.pi_provider_id is required.");
  if (!piApi) throw new Error("model_config.pi_api is required.");
  if (!modelId) throw new Error("model_config.model_id is required.");
  if (providerType !== "faux" && !baseUrl) throw new Error("model_config.base_url is required.");
  return {
    provider_type: providerType,
    pi_provider_id: piProviderId,
    pi_api: piApi,
    model_id: modelId,
    base_url: baseUrl,
    api_key: asString(raw.api_key),
    custom_headers: normalizeHeaders(raw.custom_headers),
    reasoning: asBoolean(raw.reasoning, false),
    context_window: asNumber(raw.context_window, 128000),
    max_tokens: asNumber(raw.max_tokens, 16384),
    faux_response: asString(raw.faux_response),
  };
}

function apiStreamsFor(piApi: string): ProviderStreams {
  switch (piApi) {
    case "openai-responses":
      return openAIResponsesApi();
    case "openai-completions":
      return openAICompletionsApi();
    case "anthropic-messages":
      return anthropicMessagesApi();
    case "google-vertex":
      return googleVertexApi();
    default:
      throw new Error(`Unsupported Pi API: ${piApi}`);
  }
}

function createAetherModel(config: ModelConfig): Model<string> {
  return {
    id: config.model_id,
    name: config.model_id,
    api: config.pi_api,
    provider: config.pi_provider_id,
    baseUrl: config.base_url,
    reasoning: config.reasoning ?? false,
    input: ["text", "image"],
    cost: {
      input: 0,
      output: 0,
      cacheRead: 0,
      cacheWrite: 0,
    },
    contextWindow: config.context_window ?? 128000,
    maxTokens: config.max_tokens ?? 16384,
    headers: config.custom_headers,
  };
}

function buildModels(config: ModelConfig): { models: MutableModels; model: Model<string> } {
  const models = createModels();
  if (config.provider_type === "faux") {
    const faux = fauxProvider({
      provider: config.pi_provider_id,
      models: [
        {
          id: config.model_id,
          reasoning: config.reasoning ?? true,
          input: ["text", "image"],
          contextWindow: config.context_window ?? 128000,
          maxTokens: config.max_tokens ?? 16384,
        },
      ],
      tokensPerSecond: 0,
    });
    faux.setResponses([fauxAssistantMessage(config.faux_response || "ok")]);
    models.setProvider(faux.provider);
    const model = faux.getModel(config.model_id) ?? faux.getModel();
    return { models, model };
  }

  const model = createAetherModel(config);
  const headers = config.custom_headers ?? {};
  const provider = createProvider({
    id: config.pi_provider_id,
    name: config.pi_provider_id,
    baseUrl: config.base_url,
    headers,
    auth: {
      apiKey: {
        name: "Aether provider credentials",
        resolve: async () => ({
          auth: {
            apiKey: config.api_key || undefined,
            baseUrl: config.base_url || undefined,
            headers,
          },
          source: "Aether",
        }),
      },
    },
    models: [model],
    api: apiStreamsFor(config.pi_api),
  });
  models.setProvider(provider);
  return { models, model };
}

function normalizeContentPart(rawValue: unknown): { type: "text"; text: string } | { type: "image"; mimeType: string; data: string } | undefined {
  const raw = asObject(rawValue);
  const type = asString(raw.type);
  if (type === "image") {
    const data = asString(raw.data).trim();
    if (!data) return undefined;
    return {
      type: "image",
      mimeType: asString(raw.mime_type, asString(raw.mimeType, "application/octet-stream")),
      data,
    };
  }
  const text = asString(raw.text);
  return { type: "text", text };
}

function normalizeUserContent(rawContent: unknown): string | Array<{ type: "text"; text: string } | { type: "image"; mimeType: string; data: string }> {
  if (typeof rawContent === "string") return rawContent;
  if (!Array.isArray(rawContent)) return "";
  const parts = rawContent.map(normalizeContentPart).filter((part): part is NonNullable<typeof part> => Boolean(part));
  if (parts.length === 1 && parts[0].type === "text") return parts[0].text;
  return parts;
}

function normalizeMessages(rawMessages: unknown): Context["messages"] {
  if (!Array.isArray(rawMessages)) return [];
  return rawMessages.flatMap((rawMessage): Message[] => {
    const raw = asObject(rawMessage);
    const role = asString(raw.role);
    if (role === "assistant") {
      const providerPayload = asObject(raw.provider_payload);
      if (providerPayload.role === "assistant" && Array.isArray(providerPayload.content)) {
        return [providerPayload as unknown as AssistantMessage];
      }
      return [
        {
          role: "assistant" as const,
          content: [{ type: "text" as const, text: asString(raw.text, asString(raw.content)) }],
          api: asString(raw.api, "aether"),
          provider: asString(raw.provider, "aether"),
          model: asString(raw.model, "unknown"),
          usage: emptyUsage(),
          stopReason: "stop" as const,
          timestamp: asNumber(raw.timestamp, Date.now()),
        },
      ];
    }
    if (role === "toolResult") {
      return [
        {
          role: "toolResult" as const,
          toolCallId: asString(raw.tool_call_id, asString(raw.toolCallId)),
          toolName: asString(raw.tool_name, asString(raw.toolName)),
          content: [{ type: "text" as const, text: asString(raw.text, asString(raw.content)) }],
          isError: asBoolean(raw.is_error, asBoolean(raw.isError, false)),
          timestamp: asNumber(raw.timestamp, Date.now()),
        },
      ];
    }
    return [
      {
        role: "user" as const,
        content: normalizeUserContent(raw.content ?? raw.text),
        timestamp: asNumber(raw.timestamp, Date.now()),
      },
    ];
  });
}

function buildContext(payload: JsonObject): Context {
  return {
    systemPrompt: asString(payload.system_prompt),
    messages: normalizeMessages(payload.messages),
  };
}

function emptyUsage(): Usage {
  return {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: 0,
    cost: {
      input: 0,
      output: 0,
      cacheRead: 0,
      cacheWrite: 0,
      total: 0,
    },
  };
}

function usagePayload(usage: Usage | undefined): JsonObject {
  if (!usage) return {};
  return {
    input_tokens: usage.input,
    output_tokens: usage.output,
    total_tokens: usage.totalTokens,
    reasoning_tokens: usage.reasoning,
    cached_input_tokens: usage.cacheRead,
  };
}

function assistantText(message: AssistantMessage): string {
  return message.content
    .filter((block) => block.type === "text")
    .map((block) => block.text)
    .join("");
}

function assistantThinking(message: AssistantMessage): string {
  return message.content
    .filter((block) => block.type === "thinking")
    .map((block) => block.thinking)
    .join("");
}

function assistantPayload(message: AssistantMessage): JsonObject {
  return {
    assistant_text: assistantText(message),
    reasoning_text: assistantThinking(message),
    assistant_message: message as unknown as JsonObject,
    usage: usagePayload(message.usage),
    provider: message.provider,
    model: message.model,
    response_id: message.responseId,
    stop_reason: message.stopReason,
    error_message: message.errorMessage,
  };
}

function emitStreamEvent(requestId: string, event: AssistantMessageEvent): void {
  switch (event.type) {
    case "text_delta":
      writeEvent(requestId, "assistant_text_delta", { delta: event.delta });
      break;
    case "thinking_delta":
      writeEvent(requestId, "assistant_reasoning_delta", { delta: event.delta });
      break;
    case "toolcall_start":
      writeEvent(requestId, "tool_call_start", { content_index: event.contentIndex });
      break;
    case "toolcall_delta":
      writeEvent(requestId, "tool_call_delta", { content_index: event.contentIndex, delta: event.delta });
      break;
    case "toolcall_end":
      writeEvent(requestId, "tool_call_end", {
        id: event.toolCall.id,
        name: event.toolCall.name,
        arguments: event.toolCall.arguments,
      });
      break;
    case "done":
      writeEvent(requestId, "assistant_done", assistantPayload(event.message));
      break;
    case "error":
      writeEvent(requestId, "assistant_error", assistantPayload(event.error));
      break;
  }
}

function streamOptionsFor(payload: JsonObject, signal: AbortSignal): SimpleStreamOptions {
  const options: SimpleStreamOptions = {
    signal,
    sessionId: asString(payload.session_id),
    headers: normalizeHeaders(payload.headers),
  };
  const temperature = payload.temperature;
  if (typeof temperature === "number") options.temperature = temperature;
  const maxTokens = payload.max_tokens;
  if (typeof maxTokens === "number") options.maxTokens = maxTokens;
  const reasoning = asString(payload.reasoning);
  if (reasoning && reasoning !== "off") {
    options.reasoning = reasoning as SimpleStreamOptions["reasoning"];
  }
  return options;
}

async function runSimpleCompletion(id: string, payload: JsonObject, stream: boolean): Promise<JsonObject> {
  const config = normalizeModelConfig(payload.model_config ?? defaultModelConfig);
  const { models, model } = buildModels(config);
  const controller = new AbortController();
  activeControllers.set(id, controller);
  try {
    const context = buildContext(payload);
    const options = streamOptionsFor(payload, controller.signal);
    if (stream) {
      const eventStream = models.streamSimple(model, context, options);
      for await (const event of eventStream) {
        emitStreamEvent(id, event);
      }
      const message = await eventStream.result();
      return assistantPayload(message);
    }
    const message = await models.completeSimple(model, context, options);
    return assistantPayload(message);
  } finally {
    activeControllers.delete(id);
  }
}

async function handleRequest(request: BridgeRequest): Promise<void> {
  const id = asString(request.id);
  const type = asString(request.type);
  const payload = asObject(request.payload);
  if (!id) throw new Error("Request id is required.");
  switch (type) {
    case "ping":
      writeResponse(id, {
        bridge_version: BRIDGE_VERSION,
        pi_ai_version: PI_AI_VERSION,
        pi_agent_core_version: PI_AGENT_CORE_VERSION,
        node_version: process.version,
      });
      return;
    case "set_model_config":
      defaultModelConfig = normalizeModelConfig(payload.model_config);
      writeResponse(id, { configured: true });
      return;
    case "complete_once":
      writeResponse(id, await runSimpleCompletion(id, payload, asBoolean(payload.stream, false)));
      return;
    case "run_turn":
      writeResponse(id, await runSimpleCompletion(id, payload, true));
      return;
    case "start_session":
    case "steer":
    case "follow_up":
    case "host_tool_result":
    case "host_tool_progress":
      writeResponse(id, { accepted: true });
      return;
    case "abort": {
      const targetId = asString(payload.request_id, asString(payload.target_id));
      const controller = targetId ? activeControllers.get(targetId) : undefined;
      controller?.abort();
      writeResponse(id, { aborted: Boolean(controller) });
      return;
    }
    default:
      throw new Error(`Unsupported request type: ${type}`);
  }
}

async function main(): Promise<void> {
  if (process.argv.includes("--ping")) {
    writeFrame({
      type: "response",
      id: "ping",
      ok: true,
      payload: {
        bridge_version: BRIDGE_VERSION,
        pi_ai_version: PI_AI_VERSION,
        pi_agent_core_version: PI_AGENT_CORE_VERSION,
        node_version: process.version,
      },
    });
    return;
  }

  const reader = createInterface({ input, crlfDelay: Infinity });
  for await (const line of reader) {
    if (!line.trim()) continue;
    let request: BridgeRequest;
    try {
      request = JSON.parse(line) as BridgeRequest;
    } catch (error) {
      writeError(undefined, error, "invalid_json");
      continue;
    }
    handleRequest(request).catch((error) => {
      writeError(request.id, error);
    });
  }
}

main().catch((error) => {
  stderr.write(`pi-bridge fatal: ${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
  process.exitCode = 1;
});

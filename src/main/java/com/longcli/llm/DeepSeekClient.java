package com.longcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DeepSeekClient implements LlmClient {

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String model;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public DeepSeekClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", true);

        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
            if ("tool".equals(msg.role()) && msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBody != null ? responseBody.string() : "无响应体";
                throw new IOException("API请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBody == null) {
                throw new IOException("API返回空响应体");
            }

            BufferedSource source = responseBody.source();
            StringBuilder content = new StringBuilder();
            List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();

            while (!source.exhausted()) {
                String line = source.readUtf8Line();
                if (line == null) break;

                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue;

                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) break;

                JsonNode root = mapper.readTree(payload);
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) continue;

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode() || delta.isNull()) continue;

                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                }

                JsonNode toolCalls = delta.path("tool_calls");
                if (toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        int index = tc.path("index").asInt(toolAccumulators.size());
                        while (toolAccumulators.size() <= index) {
                            toolAccumulators.add(new ToolCallAccumulator());
                        }
                        ToolCallAccumulator acc = toolAccumulators.get(index);
                        String id = tc.path("id").asText("");
                        if (!id.isEmpty()) acc.id = id;
                        JsonNode function = tc.path("function");
                        acc.name.append(function.path("name").asText(""));
                        acc.arguments.append(function.path("arguments").asText(""));
                    }
                }
            }

            List<ToolCall> toolCalls = new ArrayList<>();
            for (ToolCallAccumulator acc : toolAccumulators) {
                if (acc.id != null && !acc.id.isBlank()) {
                    toolCalls.add(new ToolCall(acc.id, new ToolCall.Function(
                            acc.name.toString(), acc.arguments.toString())));
                }
            }

            return new ChatResponse("assistant", content.toString(), toolCalls.isEmpty() ? null : toolCalls);
        }
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    private static final class ToolCallAccumulator {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}
package com.longcli.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

public interface LlmClient {

    ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException;

    String getModelName();

    String getProviderName();

    record Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        public static Message system(String content) {
            return new Message("system", content, null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, null, null);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content, null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, toolCallId, null);
        }
    }

    record ToolCall(String id, Function function) {
        public record Function(String name, String arguments) {}
    }

    record Tool(String name, String description, JsonNode parameters) {}

    record ChatResponse(String role, String content, List<ToolCall> toolCalls) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }
}

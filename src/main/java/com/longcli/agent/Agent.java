package com.longcli.agent;

import com.longcli.llm.LlmClient;
import com.longcli.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Agent {
    private static final String SYSTEM_PROMPT = """
            你是一个智能助手，能够使用工具完成任务。
            
            可用工具:
            {tool_list}
            
            思考过程:
            - 分析用户的问题
            - 决定是否需要调用工具
            - 如果需要调用工具，直接调用合适的工具
            - 如果不需要调用工具，直接回答问题
            
            注意: 工具的参数必须是有效的JSON格式，且参数必须完整。
            """;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt()));
    }

    private String buildSystemPrompt() {
        StringBuilder toolList = new StringBuilder();
        for (var tool : toolRegistry.getToolDefinitions()) {
            toolList.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        return SYSTEM_PROMPT.replace("{tool_list}", toolList.toString());
    }

    public String run(String userInput) {
        conversationHistory.add(LlmClient.Message.user(userInput));

        while (true) {
            try {
                List<LlmClient.Tool> tools = toolRegistry.getToolDefinitions();
                LlmClient.ChatResponse response = llmClient.chat(conversationHistory, tools);

                if (response.hasToolCalls()) {
                    System.out.println("🧠 思考过程:");
                    System.out.println(response.content());
                    System.out.println();

                    conversationHistory.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));

                    for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                        String toolName = toolCall.function().name();
                        String toolArgs = toolCall.function().arguments();
                        
                        System.out.println("🤖 正在调用工具: " + toolName);
                        System.out.println("参数: " + toolArgs);
                        System.out.println();

                        String toolResult = toolRegistry.executeTool(toolName, toolArgs);
                        conversationHistory.add(LlmClient.Message.tool(toolCall.id(), toolResult));

                        System.out.println("📋 工具返回:");
                        System.out.println(toolResult);
                        System.out.println();
                    }
                } else {
                    conversationHistory.add(LlmClient.Message.assistant(response.content()));
                    return response.content();
                }
            } catch (IOException e) {
                return "❌ 调用LLM失败: " + e.getMessage();
            }
        }
    }

    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt()));
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }
}

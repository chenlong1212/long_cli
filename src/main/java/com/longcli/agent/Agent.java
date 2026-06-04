package com.longcli.agent;

import com.longcli.llm.LlmClient;
import com.longcli.memory.MemoryManager;
import com.longcli.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct Agent - 边想边做的智能代理
 *
 * 工作流程：
 * 1. 接收用户输入
 * 2. 调用LLM思考下一步行动
 * 3. 如果需要调用工具，执行工具并观察结果
 * 4. 将结果反馈给LLM继续思考
 * 5. 重复直到任务完成
 */
public class Agent {
    private static final String BASE_SYSTEM_PROMPT = """
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
    private final MemoryManager memoryManager;

    public Agent(LlmClient llmClient) {
        this(llmClient, null);
    }

    public Agent(LlmClient llmClient, MemoryManager memoryManager) {
        this(llmClient, new ToolRegistry(), memoryManager);
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.conversationHistory = new ArrayList<>();
        conversationHistory.add(LlmClient.Message.system(buildBaseSystemPrompt()));
    }

    private String buildBaseSystemPrompt() {
        StringBuilder toolList = new StringBuilder();
        for (var tool : toolRegistry.getToolDefinitions()) {
            toolList.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        return BASE_SYSTEM_PROMPT.replace("{tool_list}", toolList.toString());
    }

    public String run(String userInput) {
        // 记录用户消息到记忆系统
        if (memoryManager != null) {
            memoryManager.addUserMessage(userInput);
        }

        conversationHistory.add(LlmClient.Message.user(userInput));

        while (true) {
            try {
                List<LlmClient.Message> messagesToSend;
                if (memoryManager != null) {
                    messagesToSend = buildMessagesWithMemory(userInput);
                } else {
                    messagesToSend = new ArrayList<>(conversationHistory);
                }

                List<LlmClient.Tool> tools = toolRegistry.getToolDefinitions();
                LlmClient.ChatResponse response = llmClient.chat(messagesToSend, tools);

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

                        // 记录工具结果到记忆系统
                        if (memoryManager != null) {
                            memoryManager.addToolResult(toolName, toolResult);
                        }

                        System.out.println("📋 工具返回:");
                        System.out.println(toolResult);
                        System.out.println();
                    }
                } else {
                    conversationHistory.add(LlmClient.Message.assistant(response.content()));
                    
                    // 记录助手回复到记忆系统
                    if (memoryManager != null) {
                        memoryManager.addAssistantMessage(response.content());
                    }
                    
                    return response.content();
                }
            } catch (IOException e) {
                return "❌ 调用LLM失败: " + e.getMessage();
            }
        }
    }

    private List<LlmClient.Message> buildMessagesWithMemory(String userInput) {
        String memoryContext = memoryManager.buildContextForQuery(userInput, 1500);
        
        List<LlmClient.Message> result = new ArrayList<>();
        
        if (!memoryContext.isEmpty()) {
            // 构造包含记忆的系统提示词
            String systemPromptWithMemory = buildBaseSystemPrompt() + "\n\n## 相关记忆（仅供参考）:\n" + memoryContext;
            result.add(LlmClient.Message.system(systemPromptWithMemory));
            System.out.println("🧠 检索到相关记忆并已注入上下文\n");
        } else {
            // 使用原始系统提示词
            result.add(LlmClient.Message.system(buildBaseSystemPrompt()));
        }
        
        // 添加除第一条系统消息外的其他历史消息
        if (conversationHistory.size() > 1) {
            result.addAll(conversationHistory.subList(1, conversationHistory.size()));
        }
        
        return result;
    }

    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(LlmClient.Message.system(buildBaseSystemPrompt()));
        
        // 清空短期记忆
        if (memoryManager != null) {
            memoryManager.clearShortTerm();
        }
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }
}

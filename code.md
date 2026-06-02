# LongCLI v1.1 源码阅读指南

本文档详细讲解 LongCLI v1.1 的核心源码实现，适合想要理解 ReAct Agent 工作原理的开发者。

## 目录

- [项目概览](#项目概览)
- [目录结构](#目录结构)
- [核心模块详解](#核心模块详解)
  - [Main.java - CLI入口](#mainjava---cli入口)
  - [Agent.java - ReAct循环核心](#agentjava---react循环核心)
  - [LlmClient.java - LLM接口抽象](#llmclientjava---llm接口抽象)
  - [DeepSeekClient.java - DeepSeek API实现](#deepseekclientjava---deepseek-api实现)
  - [ToolRegistry.java - 工具注册表](#toolregistryjava---工具注册表)
- [完整运行流程](#完整运行流程)
- [关键代码片段分析](#关键代码片段分析)

---

## 项目概览

LongCLI v1.1 是一个基于 Java 的 ReAct Agent CLI，集成了 DeepSeek API。它实现了经典的 "思考-行动-观察" (Reasoning-Acting-Observing) 循环模式。

### 技术栈

| 组件 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 17+ | 使用现代 Java 特性 |
| JSON处理 | Jackson | 序列化/反序列化 |
| HTTP客户端 | OkHttp | 发送API请求 |
| 构建工具 | Maven | 项目管理 |

---

## 目录结构

```
v1/
├── src/
│   └── main/
│       └── java/
│           └── com/longcli/
│               ├── agent/
│               │   └── Agent.java           ← ReAct循环核心
│               ├── cli/
│               │   └── Main.java            ← CLI入口
│               ├── llm/
│               │   ├── LlmClient.java       ← LLM接口
│               │   └── DeepSeekClient.java  ← DeepSeek实现
│               └── tool/
│                   └── ToolRegistry.java    ← 工具注册表
├── pom.xml
└── .env.example
```

从入口到核心的依赖链：
```
Main.java → Agent.java → LlmClient.java + ToolRegistry.java
                         ↓
                  DeepSeekClient.java
```

---

## 核心模块详解

### Main.java - CLI入口

**文件**：[Main.java](file:///e:/code/Paicli/v1/src/main/java/com/longcli/cli/Main.java)

这是程序的主入口，主要职责：
1. 打印启动 Banner
2. 加载 DeepSeek API Key
3. 初始化 Agent
4. 交互式循环接收用户输入

#### 核心代码片段

```java
// 第16-35行：程序启动
public static void main(String[] args) {
    printBanner();
    
    String apiKey = loadApiKey();
    if (apiKey == null || apiKey.isBlank()) {
        System.err.println("❌ 错误: 未找到 DEEPSEEK_API_KEY");
        System.exit(1);
    }

    // 初始化 LLM 客户端和 Agent
    LlmClient llmClient = new DeepSeekClient(apiKey);
    Agent agent = new Agent(llmClient);
    
    // 启动交互式循环
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
        while (true) {
            String input = reader.readLine();
            if ("/exit".equalsIgnoreCase(input)) break;
            if ("/clear".equalsIgnoreCase(input)) {
                agent.clearHistory();
                continue;
            }
            String response = agent.run(input.trim());
            System.out.println("🤖 " + response);
        }
    }
}
```

```java
// 第73-94行：加载 API Key
private static String loadApiKey() {
    // 优先从环境变量读取
    String envValue = System.getenv("DEEPSEEK_API_KEY");
    if (envValue != null && !envValue.isBlank()) {
        return envValue.trim();
    }

    // 其次从 .env 文件读取
    File envFile = new File(ENV_FILE);
    if (envFile.exists()) {
        try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("DEEPSEEK_API_KEY=")) {
                    return line.substring("DEEPSEEK_API_KEY=".length()).trim();
                }
            }
        } catch (Exception ignored) {}
    }
    return null;
}
```

---

### Agent.java - ReAct循环核心

**文件**：[Agent.java](file:///e:/code/Paicli/v1/src/main/java/com/longcli/agent/Agent.java)

这是整个项目的核心，实现了 ReAct 循环：
1. **Reason** - 让 LLM 思考
2. **Act** - 调用工具执行行动
3. **Observe** - 观察工具执行结果
4. **Repeat** - 回到第1步或给出最终答案

#### 核心代码片段

```java
// 第16-36行：初始化与System Prompt
public class Agent {
    private static final String SYSTEM_PROMPT = """
        你是一个智能助手，能够使用工具完成任务。
        可用工具：{tool_list}
        ...
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
}
```

```java
// 第49-85行：ReAct 主循环
public String run(String userInput) {
    // 1. 添加用户问题到对话历史
    conversationHistory.add(LlmClient.Message.user(userInput));

    while (true) {
        try {
            // 2. 调用 LLM 获取响应
            List<LlmClient.Tool> tools = toolRegistry.getToolDefinitions();
            LlmClient.ChatResponse response = llmClient.chat(conversationHistory, tools);

            // 3. 如果响应包含工具调用
            if (response.hasToolCalls()) {
                System.out.println("🧠 思考过程: " + response.content());
                System.out.println();

                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                    String toolName = toolCall.function().name();
                    String toolArgs = toolCall.function().arguments();
                    
                    System.out.println("🤖 正在调用工具: " + toolName);
                    
                    // 4. 执行工具
                    String toolResult = toolRegistry.executeTool(toolName, toolArgs);
                    
                    // 5. 添加工具返回结果到对话历史
                    conversationHistory.add(LlmClient.Message.tool(toolCall.id(), toolResult));
                    
                    System.out.println("📋 工具返回: " + toolResult);
                }
            } else {
                // 6. 无工具调用，返回最终答案
                conversationHistory.add(LlmClient.Message.assistant(response.content()));
                return response.content();
            }
        } catch (IOException e) {
            return "❌ 调用LLM失败: " + e.getMessage();
        }
    }
}
```

#### 图解 ReAct 循环

```
用户输入 "帮我创建一个Python项目"
    ↓
Agent.run() 开始
    ↓
conversationHistory += 【用户消息】
    ↓
进入 while(true) 循环
    ↓
llmClient.chat(history, tools)
    ↓
LLM 返回思考 + tool_call: create_project
    ↓
有工具调用? → YES
    ↓
执行 ToolRegistry.executeTool()
    ↓
工具返回 "项目创建成功"
    ↓
conversationHistory += 【tool消息: 带tool_call_id】
    ↓
回到循环开头，再次调用 LLM
    ↓
LLM 收到观察结果 → "项目已成功创建！"
    ↓
有工具调用? → NO
    ↓
conversationHistory += 【assistant消息】
    ↓
退出循环，返回答案
```

---

### LlmClient.java - LLM接口抽象

**文件**：[LlmClient.java](file:///e:/code/Paicli/v1/src/main/java/com/longcli/llm/LlmClient.java)

定义了 LLM 客户端的通用接口，方便后续扩展其他模型（如 OpenAI、Claude 等）。

#### 核心数据结构

```java
// 第16-36行：Message record
record Message(String role, String content, String toolCallId) {
    // 静态工厂方法
    public static Message system(String content) {
        return new Message("system", content, null);
    }
    
    public static Message user(String content) {
        return new Message("user", content, null);
    }
    
    public static Message assistant(String content) {
        return new Message("assistant", content, null);
    }
    
    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, toolCallId); // ← 关键：tool消息必须有toolCallId
    }
}
```

```java
// 第38-40行：ToolCall record
record ToolCall(String id, Function function) {
    public record Function(String name, String arguments) {}
}
```

```java
// 第44-48行：ChatResponse record
record ChatResponse(String role, String content, List<ToolCall> toolCalls) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
```

---

### DeepSeekClient.java - DeepSeek API实现

**文件**：[DeepSeekClient.java](file:///e:/code/Paicli/v1/src/main/java/com/longcli/llm/DeepSeekClient.java)

负责与 DeepSeek API 通信，支持流式响应。

#### 核心代码片段

```java
// 第37-137行：chat方法
public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
    // 1. 构建请求JSON
    ObjectNode requestBody = mapper.createObjectNode();
    requestBody.put("model", model);
    requestBody.put("stream", true);

    ArrayNode messagesArray = requestBody.putArray("messages");
    for (Message msg : messages) {
        ObjectNode msgNode = messagesArray.addObject();
        msgNode.put("role", msg.role());
        msgNode.put("content", msg.content());
        
        // ← 关键：tool消息必须加tool_call_id
        if ("tool".equals(msg.role()) && msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
            msgNode.put("tool_call_id", msg.toolCallId());
        }
    }

    // 2. 发送HTTP请求
    Request request = new Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestBody.toString(), 
                MediaType.parse("application/json")))
            .build();

    // 3. 处理流式响应
    try (Response response = HTTP_CLIENT.newCall(request).execute()) {
        BufferedSource source = responseBody.source();
        StringBuilder content = new StringBuilder();
        List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();

        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null) break;
            
            // 解析 data: 格式的行
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue;
            
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty()) continue;
            if ("[DONE]".equals(payload)) break;
            
            // 解析JSON并累加内容
            JsonNode root = mapper.readTree(payload);
            // ... 流式解析逻辑 ...
        }
        
        // 4. 返回最终响应
        return new ChatResponse("assistant", content.toString(), toolCalls);
    }
}
```

---

### ToolRegistry.java - 工具注册表

**文件**：[ToolRegistry.java](file:///e:/code/Paicli/v1/src/main/java/com/longcli/tool/ToolRegistry.java)

管理所有可用工具，定义工具的参数和执行逻辑。

#### 内置工具列表

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `read_file` | 读取文件 | `path` (string) |
| `write_file` | 写入文件 | `path`, `content` |
| `list_dir` | 列出目录 | `path` |
| `execute_command` | 执行命令 | `command` |
| `create_project` | 创建项目 | `name`, `type` |

#### 核心代码片段

```java
// 第18-35行：注册工具
public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();
    
    public ToolRegistry() {
        registerFileTools();
        registerShellTools();
        registerCodeTools();
    }
}
```

```java
// 第37-93行：注册文件工具
private void registerFileTools() {
    tools.put("read_file", new Tool(
        "read_file",
        "读取文件内容",
        createParameters(new Param("path", "string", "文件路径", true)),
        args -> {
            String path = args.get("path");
            try {
                return "文件内容:\n" + Files.readString(Path.of(path));
            } catch (Exception e) {
                return "读取文件失败: " + e.getMessage();
            }
        }
    ));
    
    // ... write_file, list_dir ...
}
```

```java
// 第193-233行：执行工具
public String executeTool(String name, String argumentsJson) {
    Tool tool = tools.get(name);
    if (tool == null) {
        return "未知工具: " + name;
    }

    // 解析工具参数JSON
    Map<String, String> args = new HashMap<>();
    if (argumentsJson != null && !argumentsJson.isBlank()) {
        try {
            JsonNode root = mapper.readTree(argumentsJson);
            root.fields().forEachRemaining(entry -> 
                args.put(entry.getKey(), entry.getValue().asText()));
        } catch (Exception e) {
            return "参数解析失败: " + e.getMessage();
        }
    }

    // 执行工具
    try {
        return tool.handler.apply(args);
    } catch (Exception e) {
        return "工具执行失败: " + e.getMessage();
    }
}
```

---

## 完整运行流程

我们用一个具体例子来走一遍完整流程：

### 例子：用户问"你好，能帮我在桌面创建一个test.py文件吗？"

```
=============================
1. 用户输入
=============================
"你好，能帮我在桌面创建一个test.py文件吗？"
    ↓
Main.java:
    conversationHistory.add(Message.user(...))
    agent.run("...")
    ↓
=============================
2. Agent 第一次调用 LLM
=============================
对话历史:
 [
   {"role": "system", "content": "你是一个智能助手..."},
   {"role": "user", "content": "你好，能帮我在桌面创建一个test.py文件吗？"}
 ]
    ↓
DeepSeekClient 发送请求:
 POST https://api.deepseek.com/v1/chat/completions
 {
   "model": "deepseek-chat",
   "messages": [...],
   "tools": [...]
 }
    ↓
LLM 返回响应:
 {
   "role": "assistant",
   "content": "我需要先帮你创建一个Python文件",
   "tool_calls": [
     {
       "id": "call_abc123",
       "type": "function",
       "function": {
         "name": "write_file",
         "arguments": "{\"path\":\"C:/Users/你的用户名/Desktop/test.py\",\"content\":\"print('Hello, world!')\"}"
       }
     }
   ]
 }
    ↓
=============================
3. Agent 执行工具
=============================
ToolRegistry.executeTool("write_file", ...)
    ↓
Files.writeString(Path.of("C:/.../test.py"), "print('Hello, world!')")
    ↓
返回: "文件已写入: C:/.../test.py"
    ↓
=============================
4. Agent 添加工具结果到历史
=============================
conversationHistory.add(
    Message.tool(toolCallId="call_abc123", content="文件已写入: ...")
)
    ↓
=============================
5. Agent 再次调用 LLM (观察结果)
=============================
对话历史现在是:
 [
   {"role": "system", "content": "..."},
   {"role": "user", "content": "你好，能帮我在桌面创建一个test.py文件吗？"},
   {"role": "assistant", "content": "我需要先帮你创建一个Python文件", "tool_calls": [...]},
   {"role": "tool", "content": "文件已写入: ...", "tool_call_id": "call_abc123"}
 ]
    ↓
LLM 收到观察结果，知道文件已经创建好了
    ↓
返回最终回答: "好的，我已经帮你在桌面创建了test.py文件，内容是print('Hello, world!')"
    ↓
=============================
6. Agent 返回最终答案
=============================
用户看到: "🤖 好的，我已经帮你在桌面创建了test.py文件..."
```

---

## 关键代码片段分析

### 1. Tool 消息的 tool_call_id (v1.1修复点)

**修复位置**：[DeepSeekClient.java:48-50](file:///e:/code/Paicli/v1/src/main/java/com/longcli/llm/DeepSeekClient.java#L48-L50)

```java
if ("tool".equals(msg.role()) && msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
    msgNode.put("tool_call_id", msg.toolCallId());
}
```

**为什么重要？**
- DeepSeek API要求：`tool`角色的消息必须带`tool_call_id`，用于关联调用和返回值
- v1.0缺少这个字段，导致API返回400错误
- v1.1修复后，对话历史能正确关联工具调用链

### 2. 流式响应解析

**位置**：[DeepSeekClient.java:82-124](file:///e:/code/Paicli/v1/src/main/java/com/longcli/llm/DeepSeekClient.java#L82-L124)

```java
BufferedSource source = responseBody.source();
StringBuilder content = new StringBuilder();
List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();

while (!source.exhausted()) {
    String line = source.readUtf8Line();
    if (line == null) break;
    // 解析 data: {...} 格式
    // 累加 content 和 tool_calls
}
```

**为什么重要？**
- DeepSeek API返回流式响应（Server-Sent Events）
- 需要逐行解析，手动拼接完整响应
- `ToolCallAccumulator` 用于合并多段工具调用

### 3. ReAct循环架构

**位置**：[Agent.java:49-85](file:///e:/code/Paicli/v1/src/main/java/com/longcli/agent/Agent.java#L49-L85)

```java
public String run(String userInput) {
    conversationHistory.add(LlmClient.Message.user(userInput));
    while (true) {
        ChatResponse response = llmClient.chat(history, tools);
        if (response.hasToolCalls()) {
            // 执行工具
        } else {
            // 返回答案
        }
    }
}
```

**这是整个项目的灵魂**：
- 简洁的 `while(true)` 循环实现 ReAct
- 决策完全由 LLM 控制（工具调用还是回答）
- 对话历史自动维护，上下文连贯

---

## 总结

LongCLI v1.1 虽然只有大约600行代码，但完整实现了：
✅ ReAct 循环模式
✅ OpenAI 兼容的 LLM 抽象
✅ 5个基础工具
✅ 交互式 CLI
✅ DeepSeek API 集成

它是学习 Agent 开发的绝佳起点！

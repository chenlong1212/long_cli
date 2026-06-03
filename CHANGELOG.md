# LongCLI v1.0 源码解析

## 项目概述

LongCLI 是一个基于 Java 实现的 ReAct Agent CLI，通过集成 DeepSeek API 让 AI 能够调用工具完成复杂任务。核心代码约 600 行，包含 5 个模块。

## 目录结构

```
com/longcli/
├── cli/Main.java              # 程序入口
├── agent/Agent.java           # ReAct 循环核心
├── llm/LlmClient.java         # LLM 接口抽象
├── llm/DeepSeekClient.java   # DeepSeek API 实现
└── tool/ToolRegistry.java     # 工具注册表
```

---

## 一、程序启动

程序从 [Main.java](file:///e:/code/Paicli/v1/src/main/java/com/longcli/cli/Main.java) 的 main 方法开始：

1. 打印启动 Banner
2. 调用 `loadApiKey()` 加载 DeepSeek API Key（优先读环境变量，其次读 `.env` 文件）
3. 创建 `DeepSeekClient` 和 `Agent` 实例
4. 进入 `while(true)` 循环，等待用户输入

```java
LlmClient llmClient = new DeepSeekClient(apiKey);
Agent agent = new Agent(llmClient);

while (true) {
    String input = reader.readLine();
    if ("/exit".equalsIgnoreCase(input)) break;
    String response = agent.run(input);
    System.out.println(response);
}
```

---

## 二、Agent 的运作原理

[Agent.java](file:///e:/code/Paicli/v1/src/main/java/com/longcli/agent/Agent.java) 是整个项目的核心，实现了 **ReAct 循环**（Reasoning-Acting-Observing）。

### ReAct 三步循环

```
用户提问 → LLM思考 → 调用工具 → 获取结果 → 重复或回答
```

### 具体实现

```java
public String run(String userInput) {
    // 1. 把用户问题加入对话历史
    conversationHistory.add(Message.user(userInput));

    while (true) {
        // 2. 调用 LLM，把工具列表和对话历史一起传过去
        List<Tool> tools = toolRegistry.getToolDefinitions();
        ChatResponse response = llmClient.chat(conversationHistory, tools);

        // 3. 检查 LLM 是否想调用工具
        if (response.hasToolCalls()) {
            for (ToolCall toolCall : response.toolCalls()) {
                // 4. 执行工具
                String result = toolRegistry.executeTool(
                    toolCall.function().name(),
                    toolCall.function().arguments()
                );
                // 5. 把结果加入对话历史（LLM需要看到结果才能继续思考）
                conversationHistory.add(Message.tool(toolCall.id(), result));
            }
        } else {
            // 6. LLM 不再调用工具，说明已经有答案了
            conversationHistory.add(Message.assistant(response.content()));
            return response.content();
        }
    }
}
```

### 关键设计

Agent 不做任何业务决策，它只负责：
- **维护对话历史**：`conversationHistory` 保存完整的上下文
- **循环调用 LLM**：直到 LLM 表示不需要更多工具为止
- **执行工具并回传结果**：让 LLM 能"观察"到行动的效果

---

## 三、工具的注册机制

[ToolRegistry.java](file:///e:/code/Paicli/v1/src/main/java/com/longcli/tool/ToolRegistry.java) 用一个 `Map<String, Tool>` 管理所有工具。

### 内置工具

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `read_file` | 读取文件内容 | `path` |
| `write_file` | 写入文件内容 | `path`, `content` |
| `list_dir` | 列出目录内容 | `path` |
| `execute_command` | 执行 Shell 命令 | `command` |
| `create_project` | 创建项目结构 | `name`, `type` |

### 注册流程

```java
public ToolRegistry() {
    registerFileTools();    // 注册文件操作工具
    registerShellTools();   // 注册 Shell 执行工具
    registerCodeTools();    // 注册代码生成工具
}

private void registerFileTools() {
    tools.put("read_file", new Tool(
        "read_file",                        // 工具名
        "读取文件内容",                       // 描述（给 LLM 看）
        createParameters(...),               // 参数定义（JSON Schema）
        args -> {                            // 执行逻辑（Lambda）
            String path = args.get("path");
            return Files.readString(Path.of(path));
        }
    ));
}
```

### 工具执行

```java
public String executeTool(String name, String argumentsJson) {
    Tool tool = tools.get(name);           // 查找工具
    if (tool == null) return "未知工具";    // 找不到就报错

    // 解析 JSON 参数
    Map<String, String> args = parseJson(argumentsJson);

    // 执行 Lambda 逻辑
    return tool.handler().apply(args);
}
```

---

## 四、LLM 客户端设计

[LlmClient](file:///e:/code/Paicli/v1/src/main/java/com/longcli/llm/LlmClient.java) 定义了接口，[DeepSeekClient](file:///e:/code/Paicli/v1/src/main/java/com/longcli/llm/DeepSeekClient.java) 是具体实现。

### 接口设计

```java
public interface LlmClient {
    // 发送对话，返回 LLM 的回复
    ChatResponse chat(List<Message> messages, List<Tool> tools);

    // 获取模型信息
    String getModelName();
    String getProviderName();
}
```

### DeepSeek 实现

```java
public ChatResponse chat(List<Message> messages, List<Tool> tools) {
    // 1. 构建 JSON 请求体
    ObjectNode body = mapper.createObjectNode();
    body.put("model", "deepseek-chat");
    body.put("stream", true);  // 启用流式响应

    // 2. 发送 HTTP POST 请求
    Request request = new Request.Builder()
        .url("https://api.deepseek.com/v1/chat/completions")
        .header("Authorization", "Bearer " + apiKey)
        .post(RequestBody.create(body.toString(), MediaType.JSON))
        .build();

    // 3. 解析流式响应（SSE 格式）
    while (!source.exhausted()) {
        String line = source.readUtf8Line();
        if (line.startsWith("data:")) {
            // 累加 content 和 tool_calls
        }
    }

    return new ChatResponse(..., toolCalls);
}
```

---

## 五、整体调用链

```
用户输入
    ↓
Main.main()
    ↓
Agent.run("帮我创建一个Python项目")
    ↓
┌─────────────────────────────────────┐
│        ReAct 循环开始                  │
├─────────────────────────────────────┤
│ 第1轮:                               │
│   LLM: "需要调用 write_file 工具"       │
│   Agent.executeTool("write_file",...) │
│   返回: "文件已创建"                    │
├─────────────────────────────────────┤
│ 第2轮:                               │
│   LLM: "项目创建成功，没有更多工具需要"    │
│   Agent: 返回答案给用户                 │
└─────────────────────────────────────┘
```

---

## 六、总结

LongCLI v1.0 的核心设计：

1. **Agent** 是调度者：维护历史、循环调用、返回答案
2. **ToolRegistry** 是工具箱：注册、执行、管理工具
3. **LlmClient** 是通信层：统一接口，对接不同 API
4. **DeepSeekClient** 是实现：处理 HTTP 通信和流式响应

整个系统遵循"**小而专注**"的设计原则，每个类只做一件事，通过清晰的接口定义实现松耦合。

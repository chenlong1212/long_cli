# LongCLI v3.0

一个基于 Java 的智能 Agent CLI，集成了 DeepSeek API，支持两种执行模式：ReAct 模式和 Plan-and-Execute 模式，并配备完整的记忆系统。

## 功能特性

### 三大核心能力

| 能力 | 说明 |
|------|------|
| **ReAct 模式** | 边想边做，适合简单任务和快速探索 |
| **Plan-and-Execute 模式** | 先规划后执行，适合复杂多步任务 |
| **记忆系统** | 短期记忆管理对话，长期记忆持久化关键事实 |

### 记忆系统特性

- **短期记忆**：自动管理当前对话历史，超出预算时自动压缩摘要
- **长期记忆**：跨对话持久化关键事实，支持项目级和全局级作用域
- **记忆检索**：基于关键词匹配检索相关记忆，自动注入上下文
- **Token 预算**：控制上下文大小，避免超出模型限制

### 内置工具

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `read_file` | 读取文件内容 | `path` |
| `write_file` | 写入文件内容 | `path`, `content` |
| `list_dir` | 列出目录内容 | `path` |
| `execute_command` | 执行 Shell 命令 | `command` |
| `create_project` | 创建项目结构 | `name`, `type` |

## 快速开始

### 1. 准备 DeepSeek API Key

从 [DeepSeek 开放平台](https://platform.deepseek.com/) 获取 API Key。

### 2. 配置环境

```bash
cd v1
copy .env.example .env
```

编辑 `.env`，填入你的 API Key：

```env
DEEPSEEK_API_KEY=sk-你的真实APIKey
```

### 3. 编译运行

```bash
mvn clean package
java -jar target/longcli-3.0.0.jar
```

## 使用方法

### ReAct 模式（默认）

适合简单任务和快速探索：

```
🔄 [ReAct] > 帮我查看当前目录有哪些文件
```

### Plan-and-Execute 模式

适合复杂任务，先规划后执行：

```
📋 [Plan] > 帮我重构用户模块，分析现有代码，设计新架构，实现并测试
```

### 记忆系统

保存关键事实到长期记忆：

```
🔄 [ReAct] > /save 项目使用Maven构建，Java版本是17
✅ 已保存到长期记忆: 项目使用Maven构建，Java版本是17
```

查看记忆状态：

```
🔄 [ReAct] > /memory

📊 记忆系统状态:

短期记忆: 5条 / 1200 tokens (预算: 8000, 使用率: 15%, 已压缩: 0条)
长期记忆: 3条 / 150 tokens (事实: 2, 摘要: 1, 工具结果: 0)
Token 统计: 调用 10 次 | 总输入: 15000 | 总输出: 3000 | 平均输入: 1500 | 预算: 64000 (可用: 60700)
```

## 命令行选项

| 命令 | 说明 |
|------|------|
| `/react` | 切换到 ReAct 模式 |
| `/plan` | 切换到 Plan-and-Execute 模式 |
| `/memory` | 查看记忆系统状态 |
| `/memory list` | 列出所有长期记忆 |
| `/memory search <关键词>` | 搜索相关记忆 |
| `/memory delete <id>` | 删除指定记忆 |
| `/memory clear` | 清空长期记忆 |
| `/save <事实>` | 保存事实到长期记忆 |
| `/help` | 显示帮助信息 |
| `/exit` | 退出程序 |

## 项目结构

```
v1/
├── src/main/java/com/longcli/
│   ├── agent/
│   │   ├── Agent.java            # ReAct Agent
│   │   └── PlanExecuteAgent.java # Plan-and-Execute Agent
│   ├── cli/
│   │   └── Main.java             # 命令行入口
│   ├── llm/
│   │   ├── LlmClient.java        # LLM 接口
│   │   └── DeepSeekClient.java   # DeepSeek 实现
│   ├── memory/
│   │   ├── Memory.java           # 记忆接口
│   │   ├── MemoryEntry.java      # 记忆条目
│   │   ├── MemoryManager.java    # 记忆管理器
│   │   ├── ConversationMemory.java  # 短期记忆
│   │   ├── LongTermMemory.java   # 长期记忆
│   │   ├── ContextCompressor.java   # 上下文压缩器
│   │   ├── TokenBudget.java      # Token预算管理
│   │   └── MemoryRetriever.java  # 记忆检索器
│   ├── plan/
│   │   ├── Planner.java          # 规划器
│   │   ├── ExecutionPlan.java   # 执行计划
│   │   └── Task.java            # 任务节点
│   └── tool/
│       └── ToolRegistry.java     # 工具注册表
├── pom.xml
└── README.md
```

## 记忆系统工作原理

```
用户输入
    ↓
┌─────────────────────────────────────┐
│        记忆检索                       │
├─────────────────────────────────────┤
│ MemoryRetriever.search(query)       │
│   ↓                                │
│ 从长期记忆中检索相关事实              │
│   ↓                                │
│ 注入到系统提示词                      │
└─────────────────────────────────────┘
    ↓
┌─────────────────────────────────────┐
│        对话执行                       │
├─────────────────────────────────────┤
│ 记录用户消息到短期记忆                 │
│   ↓                                │
│ 调用LLM执行任务                      │
│   ↓                                │
│ 记录助手回复和工具结果                 │
│   ↓                                │
│ 检查是否需要压缩                      │
└─────────────────────────────────────┘
    ↓
如果用户说"记住/记一下"，保存到长期记忆
```

## 版本历史

- **v3.0.0**：新增记忆系统，支持短期/长期记忆、上下文压缩、记忆检索
- **v2.0.0**：新增 Plan-and-Execute 模式，支持任务规划和并行执行
- **v1.1.0**：修复 tool_call_id 缺失导致的 API 调用失败
- **v1.0.0**：初始版本，基础 ReAct Agent

详细更新日志见 [CHANGELOG.md](CHANGELOG.md)

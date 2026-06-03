# LongCLI v2.0

一个基于 Java 的智能 Agent CLI，集成了 DeepSeek API，支持两种执行模式：ReAct 模式和 Plan-and-Execute 模式。

## 功能特性

### 两种执行模式

| 模式 | 命令 | 适用场景 |
|------|------|---------|
| **ReAct** | 默认/输入 `/react` | 简单任务，快速探索，边想边做 |
| **Plan-and-Execute** | 输入 `/plan` | 复杂任务，先规划后执行 |

### Plan-and-Execute 特性

- **智能规划**：自动将复杂任务分解为执行步骤
- **任务依赖**：支持任务间的依赖关系管理
- **并行执行**：无依赖的任务可以并行执行
- **失败重规划**：任务失败时自动重新规划后续步骤
- **执行可视化**：清晰展示执行计划和进度

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
java -jar target/longcli-2.0.0.jar
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

## 命令行选项

| 命令 | 说明 |
|------|------|
| `/react` | 切换到 ReAct 模式 |
| `/plan` | 切换到 Plan-and-Execute 模式 |
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
│   ├── plan/
│   │   ├── Planner.java          # 规划器
│   │   ├── ExecutionPlan.java   # 执行计划
│   │   └── Task.java            # 任务节点
│   └── tool/
│       └── ToolRegistry.java     # 工具注册表
├── pom.xml
└── README.md
```

## Plan-and-Execute 工作原理

```
用户输入复杂任务
    ↓
┌─────────────────────────────────────┐
│        规划阶段                       │
├─────────────────────────────────────┤
│ Planner 分析任务                     │
│   ↓                                │
│ LLM 生成执行计划 (JSON)             │
│   ↓                                │
│ 解析为 Task 列表 + 依赖关系          │
│   ↓                                │
│ 拓扑排序确定执行顺序                 │
└─────────────────────────────────────┘
    ↓
┌─────────────────────────────────────┐
│        执行阶段                       │
├─────────────────────────────────────┤
│ 按批次执行任务                       │
│ (可并行的任务同时执行)               │
│   ↓                                │
│ 任务失败? → 重新规划                 │
│   ↓                                │
│ 所有任务完成 → 返回结果              │
└─────────────────────────────────────┘
```

## 版本历史

- **v2.0.0**：新增 Plan-and-Execute 模式，支持任务规划和并行执行
- **v1.1.0**：修复 tool_call_id 缺失导致的 API 调用失败
- **v1.0.0**：初始版本，基础 ReAct Agent

详细更新日志见 [CHANGELOG.md](CHANGELOG.md)

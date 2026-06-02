# LongCLI v1

一个基于Java的ReAct Agent CLI工具，集成了DeepSeek API。

## 功能特性

- ReAct循环（思考-行动-观察）
- DeepSeek API集成
- 5个基础工具（文件操作、Shell执行、项目创建）
- 交互式CLI界面

## 快速开始

### 1. 准备DeepSeek API Key

从 [DeepSeek开放平台](https://platform.deepseek.com/) 获取API Key。

### 2. 配置环境

复制 `.env.example` 为 `.env`：

```bash
cd v1
copy .env.example .env
```

编辑 `.env`，填入你的API Key：

```env
DEEPSEEK_API_KEY=sk-你的真实APIKey
```

### 3. 编译运行

```bash
mvn clean package
java -jar target/longcli-1.0.0.jar
```

## 项目结构

```
v1/
├── src/
│   └── main/
│       ├── java/com/longcli/
│       │   ├── agent/          # ReAct Agent核心
│       │   ├── cli/            # 命令行入口
│       │   ├── llm/            # LLM客户端
│       │   └── tool/           # 工具实现
│       └── resources/
├── pom.xml
└── .env.example
```

## 版本历史

- **v1.0**：第一版发布，基础功能完整

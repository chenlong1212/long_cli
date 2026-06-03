# Changelog

所有重要的项目更新都会在此文件中记录。

## [2.0.0] - 2024-XX-XX

### 新增功能

#### Plan-and-Execute 模式
- 新增 `PlanExecuteAgent`，支持先规划后执行的模式
- 新增 `Planner`，使用 LLM 自动分解复杂任务
- 新增 `ExecutionPlan`，管理执行计划和任务依赖
- 新增 `Task`，表示单个任务单元
- 支持任务 DAG 和拓扑排序
- 支持并行执行无依赖的任务
- 支持任务失败后自动重新规划
- 命令行新增 `/plan` 切换到规划模式
- 命令行新增 `/react` 切换回 ReAct 模式
- 命令行新增 `/help` 显示帮助信息

### 改进

- 优化命令行交互体验
- 任务执行结果展示更清晰
- 新增执行计划可视化

## [1.1.0] - 2024-XX-XX

### Bug 修复

- 修复 tool 消息缺少 `tool_call_id` 字段导致的 API 400 错误
- 修复 DeepSeek API 调用失败问题

### 改进

- Message record 新增 `toolCallId` 字段
- DeepSeekClient 正确序列化 tool 角色的消息

## [1.0.0] - 2024-XX-XX

### 首次发布

- 基础 ReAct Agent 实现
- DeepSeek API 集成
- 5 个内置工具：read_file, write_file, list_dir, execute_command, create_project
- 交互式 CLI 界面
- .env 配置文件支持

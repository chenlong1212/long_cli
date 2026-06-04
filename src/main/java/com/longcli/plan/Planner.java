package com.longcli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.longcli.llm.LlmClient;
import com.longcli.memory.MemoryManager;

import java.io.IOException;
import java.util.*;

/**
 * 规划器 - 使用LLM将复杂任务分解为执行计划
 *
 * 核心流程：
 * 1. 判断任务是否简单 - 简单任务直接执行不规划
 * 2. 调用LLM生成JSON格式的执行计划
 * 3. 解析JSON为ExecutionPlan和Task对象
 * 4. 支持失败时重新规划
 */
public class Planner {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 给LLM的规划提示词
     * 告诉LLM如何输出JSON格式的执行计划
     */
    private static final String BASE_PLANNER_PROMPT = """
            你是一个任务规划专家。当用户提出复杂任务时，你需要将其分解为具体的执行步骤。
            
            请按照以下JSON格式输出执行计划：
            {
              "summary": "计划摘要，一句话描述整体计划",
              "tasks": [
                {
                  "id": "task_1",
                  "description": "任务描述",
                  "type": "ANALYSIS/FILE_READ/FILE_WRITE/COMMAND/VERIFICATION",
                  "dependencies": []
                },
                {
                  "id": "task_2",
                  "description": "任务描述",
                  "type": "FILE_WRITE",
                  "dependencies": ["task_1"]
                }
              ]
            }
            
            任务类型说明：
            - ANALYSIS: 分析、思考、理解类任务（不需要工具调用的纯思考）
            - FILE_READ: 读取文件、查看内容
            - FILE_WRITE: 创建或修改文件
            - COMMAND: 执行系统命令
            - VERIFICATION: 验证结果、测试、检查
            
            注意事项：
            1. 任务之间有依赖关系时，dependencies数组填写被依赖的任务ID
            2. 每个任务应该足够小且具体
            3. 先分析再执行，先读取再修改
            4. 最后通常需要一个VERIFICATION任务来验证结果
            """;

    private final LlmClient llmClient;
    private final MemoryManager memoryManager;

    public Planner(LlmClient llmClient) {
        this(llmClient, null);
    }

    public Planner(LlmClient llmClient, MemoryManager memoryManager) {
        this.llmClient = llmClient;
        this.memoryManager = memoryManager;
    }

    /**
     * 创建执行计划
     * @param goal 用户的目标/任务描述
     * @return 解析好的ExecutionPlan
     * @throws IOException LLM调用失败或解析失败
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        System.out.println("📋 正在分析任务...");
        System.out.println();

        // 快速路径：如果是简单任务，直接返回最小化计划
        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        // 构建消息上下文
        List<LlmClient.Message> messages = new ArrayList<>();
        
        // 构造包含记忆的系统提示词
        String prompt = BASE_PLANNER_PROMPT;
        if (memoryManager != null) {
            String memoryContext = memoryManager.buildContextForQuery(goal, 1500);
            if (!memoryContext.isEmpty()) {
                prompt += "\n\n## 相关记忆（仅供参考）:\n" + memoryContext;
                System.out.println("🧠 检索到相关记忆并已注入上下文\n");
            }
        }
        
        messages.add(LlmClient.Message.system(prompt));
        messages.add(LlmClient.Message.user("请为以下任务制定执行计划：\n" + goal));

        // 调用LLM生成计划
        LlmClient.ChatResponse response = llmClient.chat(messages, null);
        String planJson = response.content();

        // 解析JSON计划
        return parsePlan(goal, planJson);
    }

    /**
     * 解析LLM返回的JSON计划
     * @param goal 原始目标
     * @param planJson LLM返回的JSON字符串
     * @return 解析好的ExecutionPlan
     * @throws IOException 解析失败
     */
    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        // 清理JSON（去除可能的代码块标记）
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        // 解析根节点
        JsonNode root = mapper.readTree(cleaned);
        String summary = root.path("summary").asText("");
        JsonNode tasksNode = root.path("tasks");

        // 创建执行计划
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        // 映射原始ID到规范化ID（task_1, task_2...）
        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;

        // 第一遍：先创建所有任务，建立ID映射
        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String description = taskNode.path("description").asText();
            Task.TaskType type = parseTaskType(taskNode.path("type").asText());

            plan.addTask(new Task(newId, description, type));
        }

        // 第二遍：再设置依赖关系（此时所有任务已存在）
        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);

            // 解析依赖关系
            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                    Task dep = plan.getTask(newDepId);
                    if (dep != null) {
                        task.addDependency(newDepId);
                        dep.addDependent(task.getId());
                    }
                }
            }
        }

        // 计算执行顺序（拓扑排序）
        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return plan;
    }

    /**
     * 解析任务类型字符串为枚举
     */
    private Task.TaskType parseTaskType(String typeStr) {
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;  // 默认分析任务
        };
    }

    /**
     * 生成唯一的计划ID
     */
    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    /**
     * 失败时重新规划
     * @param failedPlan 失败的原始计划
     * @param failureReason 失败原因
     * @return 新的执行计划
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        System.out.println("🔄 重新规划，原因: " + failureReason);
        System.out.println();

        // 构建新的提示，包含已完成的任务信息
        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n\n");
        context.append("失败原因: ").append(failureReason).append("\n\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }

        context.append("\n请制定新的执行计划，避开之前的问题。");

        return createPlan(context.toString());
    }

    // ========== 简单任务判断 ==========
    /**
     * 判断是否是简单任务（不需要规划直接执行）
     * 简单任务特征：
     * - 不包含"然后"、"并且"等多步关键词
     * - 长度较短
     * - 只包含单一操作（列出、查看、读取等）
     */
    private boolean isSimpleGoal(String goal) {
        if (goal == null || goal.trim().isEmpty()) return false;

        String normalized = goal.trim();
        
        // 检查是否有多步操作的关键词
        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("首先")
                || normalized.contains("接着");
        
        if (hasMultiStepCue) return false;

        // 检查长度
        if (normalized.length() > 30) return false;

        // 检查是否是常见的简单操作
        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前")
                || normalized.contains("文件");
    }

    /**
     * 创建最小化计划（单任务）
     * 用于简单任务的快速路径
     */
    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary("直接执行简单任务");
        
        Task.TaskType type = inferSimpleTaskType(goal);
        plan.addTask(new Task("task_1", goal.trim(), type));
        
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    /**
     * 根据任务描述推断任务类型
     */
    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal.trim();
        if (normalized.contains("读取") || normalized.contains("查看") || normalized.contains("打开")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("创建") || normalized.contains("修改")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("验证") || normalized.contains("检查") || normalized.contains("测试")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.ANALYSIS;
    }
}

package com.longcli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.longcli.llm.LlmClient;

import java.io.IOException;
import java.util.*;

/**
 * 规划器 - 使用LLM将复杂任务分解为执行计划
 */
public class Planner {
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String PLANNER_PROMPT = """
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
        - ANALYSIS: 分析、思考、理解类任务
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

    public Planner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public ExecutionPlan createPlan(String goal) throws IOException {
        System.out.println("📋 正在分析任务...");
        System.out.println();

        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        List<LlmClient.Message> messages = Arrays.asList(
                LlmClient.Message.system(PLANNER_PROMPT),
                LlmClient.Message.user("请为以下任务制定执行计划：\n" + goal)
        );

        LlmClient.ChatResponse response = llmClient.chat(messages, null);
        String planJson = response.content();

        return parsePlan(goal, planJson);
    }

    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root = mapper.readTree(cleaned);
        String summary = root.path("summary").asText("");
        JsonNode tasksNode = root.path("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;

        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String description = taskNode.path("description").asText();
            Task.TaskType type = parseTaskType(taskNode.path("type").asText());

            plan.addTask(new Task(newId, description, type));
        }

        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);

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

        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return plan;
    }

    private Task.TaskType parseTaskType(String typeStr) {
        return switch (typeStr.toUpperCase()) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        System.out.println("🔄 重新规划，原因: " + failureReason);
        System.out.println();

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

    private boolean isSimpleGoal(String goal) {
        if (goal == null || goal.trim().isEmpty()) return false;

        String normalized = goal.trim();
        
        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("首先")
                || normalized.contains("接着");
        
        if (hasMultiStepCue) return false;

        if (normalized.length() > 30) return false;

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

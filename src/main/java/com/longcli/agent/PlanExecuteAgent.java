package com.longcli.agent;

import com.longcli.llm.LlmClient;
import com.longcli.plan.ExecutionPlan;
import com.longcli.plan.Planner;
import com.longcli.plan.Task;
import com.longcli.tool.ToolRegistry;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final int MAX_TASK_ITERATIONS = 5;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;

    private static final String TASK_PROMPT = """
        你是一个任务执行专家。请根据以下信息执行任务。
        
        任务类型: {taskType}
        任务描述: {taskDescription}
        
        {context}
        
        请使用适当的工具完成任务。完成后给出执行结果。
        """;

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.planner = new Planner(llmClient);
    }

    public String run(String userInput) {
        try {
            ExecutionPlan plan = planner.createPlan(userInput);
            System.out.println(plan.summarize());
            System.out.println(plan.visualize());
            
            System.out.println("📌 按 Enter 执行计划，或输入修改要求...");
            System.out.println();

            return executePlan(plan);

        } catch (IOException e) {
            return "❌ 规划失败: " + e.getMessage();
        }
    }

    public String runWithAutoExecute(String userInput) {
        try {
            ExecutionPlan plan = planner.createPlan(userInput);
            System.out.println(plan.summarize());
            System.out.println(plan.visualize());
            
            return executePlan(plan);

        } catch (IOException e) {
            return "❌ 规划失败: " + e.getMessage();
        }
    }

    private String executePlan(ExecutionPlan plan) {
        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();

        while (true) {
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) break;

            List<Task> batch = getNextBatch(plan, executableTasks);
            
            if (batch.size() == 1) {
                Task task = batch.get(0);
                System.out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();

                try {
                    TaskRunResult result = executeTask(plan, task);
                    task.markCompleted(result.content());
                    System.out.println("✅ 完成 [" + task.getId() + "]: " + truncate(result.content(), 100));
                } catch (Exception e) {
                    task.markFailed(e.getMessage());
                    System.out.println("❌ 失败 [" + task.getId() + "]: " + e.getMessage());
                    
                    if (plan.getProgress() < 0.5) {
                        try {
                            System.out.println("🔄 尝试重新规划...");
                            ExecutionPlan replanned = planner.replan(plan, e.getMessage());
                            return executePlan(replanned);
                        } catch (IOException ex) {
                            return "⚠️ 重新规划失败: " + ex.getMessage();
                        }
                    }
                    finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(e.getMessage());
                }
            } else {
                System.out.println("⚡ 并行执行 " + batch.size() + " 个任务: " + 
                    batch.stream().map(Task::getId).reduce((a, b) -> a + ", " + b).orElse(""));
                
                ExecutorService executor = Executors.newFixedThreadPool(Math.min(batch.size(), 4));
                List<Future<TaskRunResult>> futures = new ArrayList<>();
                
                for (Task task : batch) {
                    task.markStarted();
                    System.out.println("▶️ [" + task.getId() + "]: " + task.getDescription());
                    futures.add(executor.submit(() -> executeTask(plan, task)));
                }
                
                executor.shutdown();
                
                for (int i = 0; i < batch.size(); i++) {
                    Task task = batch.get(i);
                    try {
                        TaskRunResult result = futures.get(i).get(60, TimeUnit.SECONDS);
                        task.markCompleted(result.content());
                        System.out.println("✅ 完成 [" + task.getId() + "]: " + truncate(result.content(), 50));
                    } catch (Exception e) {
                        task.markFailed(e.getMessage());
                        System.out.println("❌ 失败 [" + task.getId() + "]: " + e.getMessage());
                        finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(e.getMessage());
                    }
                }
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进";
        }

        if (plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划部分完成，有任务失败。\n" + finalResult;
        }

        plan.markCompleted();
        return buildFinalResult(plan);
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<Task> getNextBatch(ExecutionPlan plan, List<Task> executableTasks) {
        if (executableTasks.isEmpty()) return executableTasks;
        
        Set<String> batchIds = new HashSet<>();
        for (Task task : executableTasks) {
            boolean canAdd = true;
            for (String depId : task.getDependencies()) {
                if (!batchIds.contains(depId) && !plan.getTask(depId).getStatus().equals(Task.TaskStatus.COMPLETED)) {
                    canAdd = false;
                    break;
                }
            }
            if (canAdd) batchIds.add(task.getId());
        }

        return executableTasks.stream()
                .filter(t -> batchIds.contains(t.getId()))
                .toList();
    }

    private TaskRunResult executeTask(ExecutionPlan plan, Task task) throws IOException {
        String context = buildTaskContext(plan, task);
        String prompt = TASK_PROMPT
                .replace("{taskType}", task.getType().toString())
                .replace("{taskDescription}", task.getDescription())
                .replace("{context}", context);

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(prompt));
        messages.add(LlmClient.Message.user("请执行此任务。"));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;

        while (iteration < MAX_TASK_ITERATIONS) {
            iteration++;
            
            LlmClient.ChatResponse response = llmClient.chat(messages, toolRegistry.getToolDefinitions());

            if (response.hasToolCalls()) {
                System.out.println("🧠 思考: " + truncate(response.content(), 100));
                
                messages.add(LlmClient.Message.assistant(response.content(), response.toolCalls()));

                for (LlmClient.ToolCall toolCall : response.toolCalls()) {
                    String toolName = toolCall.function().name();
                    String toolArgs = toolCall.function().arguments();
                    
                    System.out.println("🤖 调用: " + toolName + " " + truncate(toolArgs, 60));

                    String toolResult = toolRegistry.executeTool(toolName, toolArgs);
                    allResults.append(toolResult).append("\n");
                    messages.add(LlmClient.Message.tool(toolCall.id(), toolResult));
                    
                    System.out.println("📋 结果: " + truncate(toolResult, 100));
                }
            } else {
                String answer = response.content();
                if (answer != null && !answer.isBlank()) {
                    allResults.append(answer);
                }
                return new TaskRunResult(allResults.toString().trim());
            }
        }

        return new TaskRunResult(allResults.toString().trim());
    }

    private String buildTaskContext(ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标: ").append(plan.getGoal()).append("\n\n");
        context.append("当前任务: ").append(task.getDescription()).append("\n\n");

        if (!task.getDependencies().isEmpty()) {
            context.append("依赖任务结果:\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                context.append("- ").append(depId).append(": ");
                if (dep != null && dep.getResult() != null) {
                    context.append(truncate(dep.getResult(), 200));
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    private String buildSystemPrompt() {
        StringBuilder toolList = new StringBuilder();
        for (var tool : toolRegistry.getToolDefinitions()) {
            toolList.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }

        return """
            你是一个智能助手，能够使用工具完成任务。
            
            可用工具:
            """ + toolList + """
            
            注意: 工具的参数必须是有效的JSON格式。
            """;
    }

    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        result.append("✅ 计划执行完成！\n\n");

        for (Task task : plan.getAllTasks()) {
            result.append("[").append(task.getId()).append("] ");
            result.append(task.getStatus() == Task.TaskStatus.COMPLETED ? "✅" : "❌");
            result.append(" ").append(task.getDescription());
            if (task.getResult() != null && !task.getResult().isBlank()) {
                result.append("\n   ").append(truncate(task.getResult(), 150));
            }
            result.append("\n");
        }

        return result.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String cleaned = text.replace("\n", " ").replace("\r", " ").trim();
        if (cleaned.length() <= maxLen) return cleaned;
        return cleaned.substring(0, maxLen - 3) + "...";
    }

    private record TaskRunResult(String content) {}
}

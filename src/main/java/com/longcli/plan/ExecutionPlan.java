package com.longcli.plan;

import java.util.*;

/**
 * 执行计划 - 包含一组有依赖关系的任务
 * 负责：
 * 1. 管理所有Task
 * 2. 计算拓扑排序的执行顺序
 * 3. 计算可以并行执行的任务批次
 * 4. 可视化展示执行计划
 */
public class ExecutionPlan {
    private final String id;                          // 计划唯一ID
    private final String goal;                        // 计划的目标描述
    private final Map<String, Task> tasks;            // 任务集合（ID -> Task）
    private final List<String> executionOrder;        // 拓扑排序后的执行顺序
    private PlanStatus status;                        // 计划状态
    private String summary;                           // 计划摘要（LLM生成）
    private long startTime;                           // 计划开始时间
    private long endTime;                             // 计划结束时间

    /**
     * 计划状态枚举
     */
    public enum PlanStatus {
        CREATED,     // 已创建
        RUNNING,     // 执行中
        COMPLETED,   // 已完成
        FAILED,      // 失败
        CANCELLED    // 已取消
    }

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
        this.tasks = new LinkedHashMap<>();          // 保持插入顺序
        this.executionOrder = new ArrayList<>();
        this.status = PlanStatus.CREATED;
    }

    // ========== Getter方法 ==========
    public String getId() { return id; }
    public String getGoal() { return goal; }
    public PlanStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    public void setSummary(String summary) { this.summary = summary; }
    public void setStatus(PlanStatus status) { this.status = status; }

    // ========== 任务管理 ==========
    /**
     * 添加一个任务到计划中
     */
    public void addTask(Task task) {
        tasks.put(task.getId(), task);
        // 同时更新依赖任务的反向依赖关系
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                dep.addDependent(task.getId());
            }
        }
    }

    public Task getTask(String id) {
        return tasks.get(id);
    }

    public Collection<Task> getAllTasks() {
        return tasks.values();
    }

    /**
     * 获取根任务（没有依赖的任务）
     */
    public List<Task> getRootTasks() {
        return tasks.values().stream()
                .filter(t -> t.getDependencies().isEmpty())
                .toList();
    }

    // ========== 拓扑排序与执行顺序 ==========
    /**
     * 获取当前可以执行的任务列表
     */
    public List<Task> getExecutableTasks() {
        return tasks.values().stream()
                .filter(t -> t.isExecutable(tasks))
                .toList();
    }

    /**
     * 计算拓扑排序的执行顺序
     * 使用DFS的方法进行拓扑排序，同时检测循环依赖
     * @return 如果有循环依赖返回false，否则返回true
     */
    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();      // 已访问的节点
        Set<String> visiting = new HashSet<>();      // 当前递归栈中的节点（用于检测环）

        for (Task task : tasks.values()) {
            if (!visited.contains(task.getId())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false;  // 检测到循环依赖
                }
            }
        }
        return true;
    }

    /**
     * DFS拓扑排序的递归实现
     */
    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String id = task.getId();
        
        // 检测到循环依赖
        if (visiting.contains(id)) return false;
        if (visited.contains(id)) return true;

        visiting.add(id);
        
        // 先处理所有依赖的任务
        for (String depId : task.getDependencies()) {
            Task dep = tasks.get(depId);
            if (dep != null) {
                if (!topologicalSort(dep, visited, visiting)) {
                    return false;
                }
            }
        }
        
        visiting.remove(id);
        visited.add(id);
        executionOrder.add(id);
        return true;
    }

    public List<String> getExecutionOrder() {
        if (executionOrder.isEmpty()) {
            computeExecutionOrder();
        }
        return new ArrayList<>(executionOrder);
    }

    // ========== 并行执行批次计算 ==========
    /**
     * 计算可以并行执行的任务批次
     * 每个批次中的任务相互之间没有依赖关系，可以同时执行
     */
    public List<List<Task>> getExecutionBatches() {
        if (tasks.isEmpty()) return List.of();

        Map<String, Task> remaining = new LinkedHashMap<>(tasks);  // 剩余待执行的任务
        Set<String> completed = new HashSet<>();                    // 已完成的任务ID
        List<List<Task>> batches = new ArrayList<>();               // 执行批次

        while (!remaining.isEmpty()) {
            // 找到所有可执行的任务（所有依赖都已完成）
            List<Task> batch = remaining.values().stream()
                    .filter(t -> completed.containsAll(t.getDependencies()))
                    .toList();

            if (batch.isEmpty()) break;  // 没有可执行任务了

            batches.add(batch);
            
            // 标记这批任务为"已完成"（模拟执行）
            for (Task task : batch) {
                remaining.remove(task.getId());
                completed.add(task.getId());
            }
        }
        return batches;
    }

    // ========== 进度与状态 ==========
    /**
     * 计算计划执行进度（0.0-1.0）
     */
    public double getProgress() {
        if (tasks.isEmpty()) return 1.0;
        long completed = tasks.values().stream()
                .filter(t -> t.getStatus() == Task.TaskStatus.COMPLETED)
                .count();
        return (double) completed / tasks.size();
    }

    public boolean isAllCompleted() {
        return tasks.values().stream()
                .allMatch(t -> t.getStatus() == Task.TaskStatus.COMPLETED);
    }

    public boolean hasFailed() {
        return tasks.values().stream()
                .anyMatch(t -> t.getStatus() == Task.TaskStatus.FAILED);
    }

    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed() {
        this.status = PlanStatus.FAILED;
        this.endTime = System.currentTimeMillis();
    }

    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    // ========== 可视化与摘要 ==========
    /**
     * 可视化展示执行计划（控制台输出）
     */
    public String visualize() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════\n");
        sb.append("📋 执行计划: ").append(goal).append("\n");
        sb.append("═══════════════════════════════════════════════════\n");

        List<String> order = getExecutionOrder();
        for (int i = 0; i < order.size(); i++) {
            String taskId = order.get(i);
            Task task = tasks.get(taskId);
            String statusIcon = getStatusIcon(task.getStatus());
            String deps = task.getDependencies().isEmpty() ? "无" : String.join(",", task.getDependencies());
            
            sb.append(String.format("%d. %s [%s] 依赖: %s\n", 
                i + 1, statusIcon, task.getId(), deps));
            sb.append(String.format("   📝 %s\n", task.getDescription()));
        }
        
        sb.append("───────────────────────────────────────────────────\n");
        sb.append(String.format("进度: %.0f%% | 状态: %s\n", getProgress() * 100, status));
        sb.append("═══════════════════════════════════════════════════\n");
        return sb.toString();
    }

    /**
     * 返回计划的简要摘要
     */
    public String summarize() {
        List<List<Task>> batches = getExecutionBatches();
        StringBuilder sb = new StringBuilder();
        sb.append("📋 计划摘要\n");
        sb.append("   - 目标: ").append(goal).append("\n");
        sb.append("   - 任务数: ").append(tasks.size())
          .append(" | 并行批次: ").append(batches.size())
          .append(" | 状态: ").append(status).append("\n");
        
        if (!batches.isEmpty()) {
            sb.append("   - 首批执行: ").append(formatTaskList(batches.get(0))).append("\n");
            if (batches.size() > 1) {
                sb.append("   - 末批执行: ").append(formatTaskList(batches.get(batches.size() - 1))).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatTaskList(List<Task> batch) {
        if (batch.isEmpty()) return "无";
        List<String> ids = batch.stream().map(Task::getId).toList();
        if (ids.size() <= 3) return String.join(", ", ids);
        return String.join(", ", ids.subList(0, 3)) + " 等" + ids.size() + "个";
    }

    private String getStatusIcon(Task.TaskStatus status) {
        return switch (status) {
            case PENDING -> "⏳";
            case RUNNING -> "▶️";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case SKIPPED -> "⏭️";
        };
    }

    @Override
    public String toString() {
        return String.format("ExecutionPlan[%s: %s] (%d tasks, %s)", id, goal, tasks.size(), status);
    }
}

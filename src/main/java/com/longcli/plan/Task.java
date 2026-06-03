package com.longcli.plan;

import java.util.*;

/**
 * 任务节点 - 表示一个可执行的任务单元
 * 在Plan-and-Execute模式中，每个步骤就是一个Task
 */
public class Task {
    private final String id;                    // 任务唯一ID
    private final String description;           // 任务描述（给LLM看的）
    private final TaskType type;                // 任务类型
    private volatile TaskStatus status;         // 任务状态（原子更新）
    private volatile String result;             // 任务执行结果
    private volatile String error;              // 任务执行失败时的错误信息
    private final List<String> dependencies;    // 依赖的其他任务ID列表
    private final List<String> dependents;      // 依赖此任务的其他任务ID列表
    private volatile long startTime;            // 开始时间戳
    private volatile long endTime;              // 结束时间戳

    /**
     * 任务类型枚举
     */
    public enum TaskType {
        FILE_READ,         // 读取文件
        FILE_WRITE,        // 写入文件
        COMMAND,           // 执行命令
        ANALYSIS,          // 分析/思考类任务（纯LLM）
        VERIFICATION       // 验证/检查任务
    }

    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        PENDING,           // 待执行
        RUNNING,           // 执行中
        COMPLETED,         // 已完成
        FAILED,            // 执行失败
        SKIPPED            // 已跳过
    }

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type);
        this.dependencies.addAll(dependencies);
    }

    // ========== Getter方法 ==========
    public String getId() { return id; }
    public String getDescription() { return description; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
    public List<String> getDependencies() { return new ArrayList<>(dependencies); }
    public List<String> getDependents() { return new ArrayList<>(dependents); }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }

    // ========== 依赖管理 ==========
    /**
     * 添加一个依赖于此任务的任务（反向依赖）
     */
    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    /**
     * 添加此任务依赖的任务
     */
    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    // ========== 状态变更 ==========
    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped() {
        this.status = TaskStatus.SKIPPED;
        this.endTime = System.currentTimeMillis();
    }

    /**
     * 获取任务执行耗时（毫秒）
     */
    public long getDuration() {
        if (startTime == 0) return 0;
        if (endTime == 0) return System.currentTimeMillis() - startTime;
        return endTime - startTime;
    }

    /**
     * 检查任务是否可以执行：
     * - 状态必须是PENDING
     * - 所有依赖的任务都必须已完成
     */
    public boolean isExecutable(Map<String, Task> allTasks) {
        if (status != TaskStatus.PENDING) return false;
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, status);
    }
}

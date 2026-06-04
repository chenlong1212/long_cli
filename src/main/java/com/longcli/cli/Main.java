package com.longcli.cli;

import com.longcli.agent.Agent;
import com.longcli.agent.PlanExecuteAgent;
import com.longcli.llm.DeepSeekClient;
import com.longcli.llm.LlmClient;
import com.longcli.memory.MemoryEntry;
import com.longcli.memory.MemoryManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.List;

public class Main {
    private static final String VERSION = "3.0.0";
    private static final String ENV_FILE = ".env";

    public static void main(String[] args) {
        printBanner();
        
        String apiKey = loadApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误: 未找到 DEEPSEEK_API_KEY");
            System.err.println("请在 .env 文件中添加 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        LlmClient llmClient = new DeepSeekClient(apiKey);
        MemoryManager memoryManager = new MemoryManager(llmClient);
        
        System.out.println("✅ 已加载模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
        System.out.println();
        System.out.println("模式说明:");
        System.out.println("  - /react  : ReAct 模式（逐步执行，边想边做）");
        System.out.println("  - /plan   : Plan-and-Execute 模式（先规划后执行）");
        System.out.println("  - /memory : 查看记忆系统状态");
        System.out.println("  - /save   : 保存事实到长期记忆");
        System.out.println("  - /exit   : 退出程序");
        System.out.println();
        System.out.println("💡 默认使用 ReAct 模式，输入 /plan 切换到规划模式");
        System.out.println();

        boolean usePlanMode = false;
        Agent reactAgent = new Agent(llmClient, memoryManager);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient, memoryManager);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                String modePrefix = usePlanMode ? "📋 [Plan]" : "🔄 [ReAct]";
                System.out.print(modePrefix + " > ");
                System.out.flush();
                
                String input = reader.readLine();
                if (input == null) break;
                
                input = input.trim();
                if (input.isEmpty()) continue;

                if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                    System.out.println("\n👋 再见!");
                    break;
                }

                if ("/help".equalsIgnoreCase(input)) {
                    printHelp();
                    continue;
                }

                if ("/react".equalsIgnoreCase(input)) {
                    usePlanMode = false;
                    System.out.println("🔄 已切换到 ReAct 模式\n");
                    continue;
                }

                if ("/plan".equalsIgnoreCase(input)) {
                    usePlanMode = true;
                    System.out.println("📋 已切换到 Plan-and-Execute 模式\n");
                    continue;
                }

                // 记忆系统命令
                if (input.startsWith("/memory")) {
                    handleMemoryCommand(input, memoryManager);
                    continue;
                }

                // 保存事实命令
                if (input.startsWith("/save ")) {
                    String fact = input.substring("/save ".length()).trim();
                    if (!fact.isEmpty()) {
                        memoryManager.storeFact(fact);
                        System.out.println("✅ 已保存到长期记忆: " + fact + "\n");
                    } else {
                        System.out.println("❌ 用法: /save <事实内容>\n");
                    }
                    continue;
                }

                System.out.println();
                String response;
                if (usePlanMode) {
                    response = planAgent.runWithAutoExecute(input);
                } else {
                    response = reactAgent.run(input);
                }
                System.out.println("\n" + response);
                System.out.println();
            }
        } catch (Exception e) {
            System.err.println("❌ 程序异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理记忆系统命令
     */
    private static void handleMemoryCommand(String input, MemoryManager memoryManager) {
        String[] parts = input.split("\\s+", 3);
        String subCommand = parts.length > 1 ? parts[1].toLowerCase() : "";

        switch (subCommand) {
            case "" -> {
                // /memory - 显示状态
                System.out.println("\n📊 记忆系统状态:\n");
                System.out.println(memoryManager.getSystemStatus());
                System.out.println();
            }
            case "list" -> {
                // /memory list - 列出所有长期记忆
                System.out.println("\n📚 长期记忆列表:\n");
                List<MemoryEntry> entries = memoryManager.listLongTerm();
                if (entries.isEmpty()) {
                    System.out.println("  (无记忆)");
                } else {
                    for (MemoryEntry entry : entries) {
                        System.out.println("  [" + entry.getId() + "] " + entry.getType() + ": " + 
                                truncate(entry.getContent(), 60));
                    }
                }
                System.out.println();
            }
            case "search" -> {
                // /memory search <关键词>
                if (parts.length < 3) {
                    System.out.println("❌ 用法: /memory search <关键词>\n");
                    return;
                }
                String query = parts[2];
                System.out.println("\n🔍 搜索结果:\n");
                List<MemoryEntry> results = memoryManager.searchLongTerm(query, 10);
                if (results.isEmpty()) {
                    System.out.println("  (未找到相关记忆)");
                } else {
                    for (MemoryEntry entry : results) {
                        System.out.println("  [" + entry.getId() + "] " + entry.getType() + ": " + 
                                truncate(entry.getContent(), 60));
                    }
                }
                System.out.println();
            }
            case "delete" -> {
                // /memory delete <id>
                if (parts.length < 3) {
                    System.out.println("❌ 用法: /memory delete <记忆ID>\n");
                    return;
                }
                String id = parts[2];
                if (memoryManager.deleteLongTerm(id)) {
                    System.out.println("✅ 已删除记忆: " + id + "\n");
                } else {
                    System.out.println("❌ 未找到记忆: " + id + "\n");
                }
            }
            case "clear" -> {
                // /memory clear - 清空长期记忆
                memoryManager.clearLongTerm();
                System.out.println("✅ 已清空长期记忆\n");
            }
            default -> {
                System.out.println("❌ 未知命令: " + subCommand);
                System.out.println("   可用命令: list, search, delete, clear\n");
            }
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 3) + "...";
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                                                          ║");
        System.out.println("║   ██████╗  █████╗ ██╗      ██████╗██╗     ██╗            ║");
        System.out.println("║   ██╔══██╗██╔══██╗██║     ██╔════╝██║     ██║            ║");
        System.out.println("║   ██████╔╝███████║██║     ██║     ██║     ██║            ║");
        System.out.println("║   ██╔═══╝ ██╔══██║██║     ██║     ██║     ██║            ║");
        System.out.println("║   ██║     ██║  ██║███████╗╚██████╗███████╗██║            ║");
        System.out.println("║   ╚═╝     ╚═╝  ╚═╝╚══════╝ ╚═════╝╚══════╝╚═╝            ║");
        System.out.println("║                                                          ║");
        System.out.println("║              LongCLI v" + VERSION + " - Java Agent CLI        ║");
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void printHelp() {
        System.out.println("""
            
            LongCLI v3.0.0 帮助
            
            命令:
              /react      切换到 ReAct 模式（默认）
              /plan       切换到 Plan-and-Execute 模式
              /memory     查看记忆系统状态
              /memory list        列出所有长期记忆
              /memory search <关键词>  搜索相关记忆
              /memory delete <id>  删除指定记忆
              /memory clear       清空长期记忆
              /save <事实>        保存事实到长期记忆
              /help       显示此帮助信息
              /exit       退出程序
            
            模式说明:
            
              🔄 ReAct 模式:
                 边想边做，每一步都让AI思考后执行
                 适合简单任务和快速探索
            
              📋 Plan-and-Execute 模式:
                 先让AI制定执行计划，再按计划执行
                 适合复杂任务，能看到完整的执行步骤
                 支持任务依赖和并行执行
                 支持失败后自动重新规划
            
            记忆系统:
            
              🧠 短期记忆:
                 自动管理当前对话历史
                 超出预算时自动压缩摘要
              
              📚 长期记忆:
                 跨对话持久化关键事实
                 自动检索相关记忆注入上下文
                 支持 /save 命令手动保存
            
            示例:
              > 帮我创建一个Python项目
                 (ReAct模式会一步步创建)
              
              > /plan 帮我重构用户模块，先分析现有代码，设计新架构，再实现
                 (Plan模式会先制定详细计划，用户确认后执行)
              
              > /save 项目使用Maven构建，Java版本是17
                 (保存事实到长期记忆，后续对话会自动检索)
            """);
    }

    private static String loadApiKey() {
        String envValue = System.getenv("DEEPSEEK_API_KEY");
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        File envFile = new File(ENV_FILE);
        if (envFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("DEEPSEEK_API_KEY=")) {
                        return line.substring("DEEPSEEK_API_KEY=".length()).trim();
                    }
                }
            } catch (Exception ignored) {}
        }

        return null;
    }
}

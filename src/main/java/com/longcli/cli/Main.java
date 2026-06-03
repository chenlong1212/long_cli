package com.longcli.cli;

import com.longcli.agent.Agent;
import com.longcli.agent.PlanExecuteAgent;
import com.longcli.llm.DeepSeekClient;
import com.longcli.llm.LlmClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

public class Main {
    private static final String VERSION = "2.0.0";
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
        
        System.out.println("✅ 已加载模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
        System.out.println();
        System.out.println("模式说明:");
        System.out.println("  - /react  : ReAct 模式（逐步执行，边想边做）");
        System.out.println("  - /plan   : Plan-and-Execute 模式（先规划后执行）");
        System.out.println("  - /exit   : 退出程序");
        System.out.println();
        System.out.println("💡 默认使用 ReAct 模式，输入 /plan 切换到规划模式");
        System.out.println();

        boolean usePlanMode = false;
        Agent reactAgent = new Agent(llmClient);
        PlanExecuteAgent planAgent = new PlanExecuteAgent(llmClient);

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
            
            LongCLI v2.0.0 帮助
            
            命令:
              /react      切换到 ReAct 模式（默认）
              /plan      切换到 Plan-and-Execute 模式
              /help      显示此帮助信息
              /exit      退出程序
            
            模式说明:
            
              🔄 ReAct 模式:
                 边想边做，每一步都让AI思考后执行
                 适合简单任务和快速探索
            
              📋 Plan-and-Execute 模式:
                 先让AI制定执行计划，再按计划执行
                 适合复杂任务，能看到完整的执行步骤
                 支持任务依赖和并行执行
                 支持失败后自动重新规划
            
            示例:
              > 帮我创建一个Python项目
                 (ReAct模式会一步步创建)
              
              > 帮我重构用户模块，先分析现有代码，设计新架构，再实现
                 (Plan模式会先制定详细计划，用户确认后执行)
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
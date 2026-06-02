package com.longcli.cli;

import com.longcli.agent.Agent;
import com.longcli.llm.DeepSeekClient;
import com.longcli.llm.LlmClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

public class Main {
    private static final String VERSION = "1.0";
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
        Agent agent = new Agent(llmClient);
        
        System.out.println("✅ 已加载模型: " + llmClient.getModelName() + " (" + llmClient.getProviderName() + ")");
        System.out.println("🔄 使用 ReAct 模式");
        System.out.println();
        System.out.println("Tips for getting started:");
        System.out.println("1. 输入你的问题或任务");
        System.out.println("2. 输入 /exit 退出程序");
        System.out.println();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("🗨️ ");
                System.out.flush();
                
                String input = reader.readLine();
                if (input == null || "/exit".equalsIgnoreCase(input)) {
                    System.out.println("\n👋 再见!");
                    break;
                }
                
                if (input.trim().isEmpty()) {
                    continue;
                }

                if ("/clear".equalsIgnoreCase(input.trim())) {
                    agent.clearHistory();
                    System.out.println("🗑️ 对话历史已清空\n");
                    continue;
                }

                System.out.println();
                String response = agent.run(input.trim());
                System.out.println("🤖 " + response);
                System.out.println();
            }
        } catch (Exception e) {
            System.err.println("❌ 程序异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printBanner() {
        System.out.println("              LongCLI v" + VERSION + " - Java Agent CLI        ");
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
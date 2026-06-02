package com.longcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class ToolRegistry {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    
    private final Map<String, Tool> tools = new HashMap<>();
    private final long commandTimeoutSeconds;
    private String projectPath = System.getProperty("user.dir");

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS);
    }

    public ToolRegistry(long commandTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        registerFileTools();
        registerShellTools();
        registerCodeTools();
    }

    private void registerFileTools() {
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容",
                createParameters(
                        new Param("path", "string", "文件路径", true)
                ),
                args -> {
                    String path = args.get("path");
                    try {
                        return "文件内容:\n" + Files.readString(Path.of(path));
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        tools.put("write_file", new Tool(
                "write_file",
                "写入文件内容",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");
                    try {
                        Path filePath = Path.of(path);
                        Files.createDirectories(filePath.getParent());
                        Files.writeString(filePath, content);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容",
                createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    String path = args.get("path");
                    File[] files = new File(path).listFiles();
                    if (files == null) {
                        return "目录为空或不存在";
                    }
                    StringBuilder sb = new StringBuilder("目录内容:\n");
                    for (File f : files) {
                        sb.append(f.isDirectory() ? "[D] " : "[F] ")
                          .append(f.getName()).append("\n");
                    }
                    return sb.toString();
                }
        ));
    }

    private void registerShellTools() {
        tools.put("execute_command", new Tool(
                "execute_command",
                "执行Shell命令",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> executeCommand(args.get("command"))
        ));
    }

    private void registerCodeTools() {
        tools.put("create_project", new Tool(
                "create_project",
                "创建新项目结构",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/node)", true)
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type");
                    Path projectRoot = Path.of(name);
                    try {
                        Files.createDirectories(projectRoot);

                        switch (type.toLowerCase()) {
                            case "java" -> {
                                Files.createDirectories(projectRoot.resolve("src/main/java"));
                                Files.createDirectories(projectRoot.resolve("src/main/resources"));
                                Files.writeString(projectRoot.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectRoot.resolve(name));
                                Files.writeString(projectRoot.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectRoot.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> {
                                Files.writeString(projectRoot.resolve("package.json"),
                                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            }
                        }
                        return "项目已创建: " + name + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    private String executeCommand(String command) {
        try {
            Process process = new ProcessBuilder()
                    .command(command.contains(" ") ? command.split(" ") : new String[]{command})
                    .directory(new File(projectPath))
                    .redirectErrorStream(true)
                    .start();

            boolean completed = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());
            
            if (!completed) {
                process.destroyForcibly();
                return "命令执行超时";
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "命令执行失败 (exit code " + exitCode + "):\n" + output;
            }
            return output;
        } catch (Exception e) {
            return "命令执行失败: " + e.getMessage();
        }
    }

    private JsonNode createParameters(Param... params) {
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }
        return parameters;
    }

    public List<com.longcli.llm.LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new com.longcli.llm.LlmClient.Tool(t.name, t.description, t.parameters))
                .toList();
    }

    public String executeTool(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "未知工具: " + name;
        }

        Map<String, String> args = new HashMap<>();
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                JsonNode root = mapper.readTree(argumentsJson);
                root.fields().forEachRemaining(entry -> args.put(entry.getKey(), entry.getValue().asText()));
            } catch (Exception e) {
                return "参数解析失败: " + e.getMessage();
            }
        }

        try {
            return tool.handler.apply(args);
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getProjectPath() {
        return projectPath;
    }

    private record Param(String name, String type, String description, boolean required) {}

    private record Tool(String name, String description, JsonNode parameters, Function<Map<String, String>, String> handler) {}
}
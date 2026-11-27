package com.countmaske.orderagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表：启动时收集所有 Tool Bean，对外提供两件事：
 * 1. getDefinitions()：给 LLM 的 OpenAI tools 数组（name/description/parameters）
 * 2. execute(name, args)：按 name 路由到对应工具，替代原来的 switch 硬编码
 *
 * Spring 会把所有 Tool 实现类自动注入进来，新增工具无需改动这里。
 */
@Component
public class ToolRegistry {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> toolList) {
        this.tools = toolList.stream()
            .collect(Collectors.toMap(Tool::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * 生成 OpenAI tools 格式的定义数组。
     * system prompt 里的工具清单也从这里取，避免两处维护。
     */
    public List<Map<String, Object>> getDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.schema());

            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", function);
            definitions.add(wrapper);
        }
        return definitions;
    }

    /** 给 system prompt 用的工具清单描述 */
    public String describeTools() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools.values()) {
            sb.append("- ").append(tool.name()).append("：").append(tool.description()).append("\n");
        }
        return sb.toString();
    }

    public String execute(String toolName, JsonNode arguments) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        return tool.execute(arguments);
    }
}

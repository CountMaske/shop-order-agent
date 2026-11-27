package com.countmaske.orderagent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具统一契约：名称 + 描述 + 参数 Schema + 执行器。
 *
 * LLM 只看 name / description / schema 决定是否调用；
 * Runtime 只认 name 路由到 handler，不再写 switch。
 * 新增工具 = 新增一个实现类 + @Component，引擎与注册表都不用改。
 */
public interface Tool {

    /** 工具名，必须与 LLM tool_calls.function.name 一致 */
    String name();

    /** 给 LLM 看的工具用途描述 */
    String description();

    /** 参数 JSON Schema（OpenAI function.parameters 的内容） */
    JsonNode schema();

    /** 执行工具，入参是 LLM 传来的 arguments，返回 JSON 字符串 */
    String execute(JsonNode arguments);
}

package com.countmaske.orderagent.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Agent 从 LLM 响应中扁平化提取的决策对象
 * 对应 OpenAI Tool Calling 协议的三个关键字段：
 * - reasoning_content: 思考过程
 * - tool_calls: 工具调用列表
 * - content: 最终答案
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentDecision {

    /** LLM 的思考过程（enable_thinking=true 时返回） */
    private String reasoning;

    /** 工具名称（tool_calls[0].function.name） */
    private String toolName;

    /** 工具参数（tool_calls[0].function.arguments 解析后的 JsonNode） */
    private JsonNode arguments;

    /** LLM 的最终回答（message.content），非空说明不需要调工具 */
    private String finalAnswer;
}

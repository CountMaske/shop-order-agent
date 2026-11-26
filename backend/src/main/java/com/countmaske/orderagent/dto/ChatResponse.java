package com.countmaske.orderagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * /api/chat 的响应体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** Agent 的最终回答 */
    private String answer;

    /** 思考过程（可选） */
    private String reasoning;

    /** 被调用的工具名（如有） */
    private String toolName;
}

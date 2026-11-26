package com.countmaske.orderagent.dto;

import lombok.Data;

/**
 * /api/chat 的请求体
 */
@Data
public class ChatRequest {

    /** 会话 ID，用于隔离不同窗口或用户的对话上下文 */
    private String sessionId;

    /** 用户消息 */
    private String message;
}

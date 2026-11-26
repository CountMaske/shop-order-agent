package com.countmaske.orderagent.controller;

import com.countmaske.orderagent.dto.ChatRequest;
import com.countmaske.orderagent.dto.ChatResponse;
import com.countmaske.orderagent.service.AgentEngineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 对话入口
 * POST /api/chat
 * 同步返回完整答案（后续补 SSE 流式）
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    @Autowired
    private AgentEngineService agentEngineService;

    /**
     * 接收用户输入并触发 Loop 引擎
     * 
     * @param request sessionId + userMessage
     * @return 包含 answer、reasoning、toolName 的响应
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return agentEngineService.execute(request.getSessionId(), request.getMessage());
    }
}

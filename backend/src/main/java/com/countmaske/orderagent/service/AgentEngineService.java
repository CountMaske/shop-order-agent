package com.countmaske.orderagent.service;

import com.countmaske.orderagent.dto.ChatRequest;
import com.countmaske.orderagent.dto.ChatResponse;
import com.countmaske.orderagent.domain.AgentDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Runtime Loop 引擎核心服务
 * 实现四步循环：接收输入 → 判断回复/调工具 → 执行工具 → 继续 loop 或返回
 * 
 * 技术栈：Spring MVC（同步阻塞）+ OpenAI Tool Calling 协议
 * 解析策略：Jackson 扁平化提取 reasoning_content / tool_calls / content
 */
@Service
public class AgentEngineService {

    private static final Logger log = LoggerFactory.getLogger(AgentEngineService.class);

    private static final int MAX_ITERATIONS = 5;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${llm.api-key:sk-placeholder}")
    private String apiKey;

    @Value("${llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${llm.model:qwen-plus}")
    private String model;

    /**
     * 执行 Loop 四步引擎
     * 
     * @param sessionId 会话 ID
     * @param userMessage 用户消息
     * @return ChatResponse（包含最终答案、思考过程、工具名）
     */
    public ChatResponse execute(String sessionId, String userMessage) {
        // 初始化 messages 列表：system prompt + 用户初始消息
        List<Map<String, Object>> messages = initializeMessages(sessionId, userMessage);

        String lastToolCallId = null;
        AgentDecision lastDecision = null;

        // Step four: for 循环控制 Loop，最多跑 MAX_ITERATIONS 次
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("=== Loop iteration {} ===", i + 1);

            try {
                // Step two: 调用 LLM，获取原始 JSON 响应
                String llmJson = callLLM(messages);
                log.info("[LLM raw response] {}", llmJson);

                // Step three: 解析 LLM 输出，提取 reasoning / tool_calls / finalAnswer
                AgentDecision decision = parseDecision(llmJson);
                lastDecision = decision;

                // 记录思考过程
                if (decision.getReasoning() != null) {
                    log.debug("[Reasoning] {}", decision.getReasoning());
                }

                // 【关键】Step four 先判断 tool_calls：
                // 深度思考模型调工具时，通常「同一轮」同时返回 reasoning + content(过渡话) + tool_calls
                // 必须优先处理 tool_calls，否则那句「我先帮你搜索」会被误当成最终答案直接返回
                if (decision.getToolName() != null && !decision.getToolName().isEmpty()) {
                    lastToolCallId = "call_" + sessionId + "_" + i;
                    log.info("[Tool call] name={}, arguments={}", decision.getToolName(), decision.getArguments());

                    // Step three: 执行工具
                    String toolResult = executeTool(decision.getToolName(), decision.getArguments());

                    // Step four: 维护 OpenAI 协议上下文
                    // 必须先追加 assistant(带 tool_calls)，再追加 tool result，两者 id 对应
                    messages.add(buildAssistantToolMessage(
                        lastToolCallId,
                        decision.getToolName(),
                        decision.getArguments() != null ? decision.getArguments().toString() : "{}"
                    ));
                    messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", lastToolCallId,
                        "content", toolResult
                    ));

                    log.info("[Tool result] {}", toolResult);
                    continue; // 进入下一轮 Loop，让 LLM 基于工具结果决定下一步
                }

                // 没有 tool_calls，content 才算最终答案
                if (decision.getFinalAnswer() != null && !decision.getFinalAnswer().isEmpty()) {
                    log.info("[Loop terminated] Final answer from LLM");
                    return buildSuccessResponse(decision.getFinalAnswer(), decision.getReasoning(), null);
                }

                // 既没工具也没答案，说明 LLM 卡住了，强制返回友好提示
                log.warn("[Loop stalled] LLM neither answered nor called tools");
                return buildErrorResponse("抱歉，我暂时无法处理您的请求。");

            } catch (Exception e) {
                // 异常处理：把错误信息以 role: "tool" 返回给 LLM，让它自己决定重试或换方案
                log.error("[Loop error] iteration={}", i, e);
                messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", lastToolCallId != null ? lastToolCallId : "unknown",
                    "content", "Error: " + e.getMessage()
                ));
            }
        }

        // 超过最大迭代次数，强制终止
        log.warn("[Loop terminated] Max iterations ({}) reached", MAX_ITERATIONS);
        return buildErrorResponse("抱歉，处理超时，请稍后重试。");
    }

    /**
     * 初始化 messages 列表
     */
    private List<Map<String, Object>> initializeMessages(String sessionId, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // System prompt：设定 Agent 角色和工具列表
        String systemPrompt = buildSystemPrompt();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // User message
        messages.add(Map.of("role", "user", "content", userMessage));

        return messages;
    }

    /**
     * 构建 System Prompt（仅设定角色和上下文，工具 Schema 通过 tools 参数传入）
     */
    private String buildSystemPrompt() {
        return """
            你是商城订单助手，专门帮助用户完成下单、查订单、取消订单等操作。

            规则：
            1. 需要查询商品、计算价格、创建订单时，必须通过 tool_calls 调用工具，
               不要只在文字里说「我先帮你搜索」。文字回答和工具调用是两回事。
            2. 只有当用户只是打招呼、闲聊、或问题不需要工具时，才直接用文字回答。
            3. 可用工具：search（搜商品）、calculator（算总价）、create_order（下单）。
            4. 调用工具后，根据工具返回结果决定下一步：继续调用其他工具，或用文字给出最终答案。
            """;
    }

    /**
     * Step two: 调用 LLM API（OpenAI 兼容协议）
     */
    private String callLLM(List<Map<String, Object>> messages) throws Exception {
        // 构造请求体
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", messages,
            "enable_thinking", true,
            "tools", getToolDefinitions()
        );

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        // 发送 POST 请求
        ResponseEntity<String> response = restTemplate.postForEntity(
            baseUrl + "/chat/completions",
            entity,
            String.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("LLM API call failed: " + response.getBody());
        }

        return response.getBody();
    }

    /**
     * Step three: 解析 LLM 输出（扁平化提取）
     * 
     * OpenAI Tool Calling 标准响应格式：
     * {
     *   "choices": [{
     *     "message": {
     *       "role": "assistant",
     *       "content": "最终回答文本" | null,
     *       "reasoning_content": "思考过程（enable_thinking=true 时存在）",
     *       "tool_calls": [null] | [{
     *         "id": "call_xxx",
     *         "type": "function",
     *         "function": {
     *           "name": "calculator",
     *           "arguments": "{\"items\": [...]}""
     *         }
     *       }]
     *     }
     *   }]
     * }
     */
    private AgentDecision parseDecision(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode message = root.path("choices").path(0).path("message");

        AgentDecision decision = new AgentDecision();

        // 1. 提取 reasoning_content（思考过程）
        JsonNode reasoningNode = message.path("reasoning_content");
        if (reasoningNode.isTextual() && !reasoningNode.asText().trim().isEmpty()) {
            decision.setReasoning(reasoningNode.asText());
        }

        // 2. 提取 content（最终答案）
        JsonNode contentNode = message.path("content");
        if (contentNode.isTextual() && !contentNode.asText().trim().isEmpty()) {
            decision.setFinalAnswer(contentNode.asText());
        }

        // 3. 提取 tool_calls（工具调用列表）
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && toolCalls.size() > 0) {
            JsonNode firstCall = toolCalls.get(0);
            decision.setToolName(firstCall.path("function").path("name").asText());
            
            // arguments 是 JSON 字符串，解析为 JsonNode
            String argsStr = firstCall.path("function").path("arguments").asText();
            if (argsStr != null && !argsStr.trim().isEmpty()) {
                try {
                    decision.setArguments(mapper.readTree(argsStr));
                } catch (Exception e) {
                    log.warn("Failed to parse tool arguments: {}", argsStr);
                }
            }
        }

        return decision;
    }

    /**
     * 工具执行路由
     */
    private String executeTool(String toolName, JsonNode arguments) {
        switch (toolName) {
            case "calculator":
                return calculator(arguments);
            case "search":
                return search(arguments);
            case "create_order":
                return createOrder(arguments);
            default:
                throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
    }

    /**
     * 工具 1：calculator - 计算总价（mock）
     */
    private String calculator(JsonNode args) {
        try {
            double total = 0;
            if (args.has("total")) {
                total = args.get("total").asDouble();
            } else if (args.has("items")) {
                JsonNode items = args.get("items");
                for (JsonNode item : items) {
                    total += item.path("price").asDouble(0) * item.path("quantity").asInt(1);
                }
            }
            return String.format("{\"total\": %.2f, \"currency\": \"CNY\"}", total);
        } catch (Exception e) {
            throw new RuntimeException("Calculator error: " + e.getMessage());
        }
    }

    /**
     * 工具 2：search - 搜索商品信息（mock）
     */
    private String search(JsonNode args) {
        String keyword = args.has("keyword") ? args.get("keyword").asText() : "";
        
        // Mock 数据：矿泉水 / 可乐 / 气泡水
        String mockData = "[{\"id\": 1, \"name\": \"农夫山泉 550ml\", \"price\": 2.0}, " +
                         "{\"id\": 2, \"name\": \"可口可乐 330ml\", \"price\": 3.0}, " +
                         "{\"id\": 3, \"name\": \"元气森林气泡水 480ml\", \"price\": 5.0}]";
        
        return mockData;
    }

    /**
     * 工具 3：create_order - 创建订单
     */
    private String createOrder(JsonNode args) {
        try {
            String orderId = "ORD-" + System.currentTimeMillis();
            String userId = args.has("userId") ? args.get("userId").asText() : "anonymous";
            String status = "created";
            
            return String.format("{\"orderId\": \"%s\", \"userId\": \"%s\", \"status\": \"%s\"}", 
                               orderId, userId, status);
        } catch (Exception e) {
            throw new RuntimeException("Create order error: " + e.getMessage());
        }
    }

    /**
     * 定义可用工具的 JSON Schema（OpenAI tools 格式）
     */
    private List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // Tool 1: calculator
        Map<String, Object> calculator = new HashMap<>();
        calculator.put("type", "function");
        Map<String, Object> calculatorFunc = new HashMap<>();
        calculatorFunc.put("name", "calculator");
        calculatorFunc.put("description", "计算商品总价，支持传入 items 列表或直接的 total 值");
        Map<String, Object> calculatorSchema = new HashMap<>();
        calculatorSchema.put("type", "object");
        Map<String, Object> calculatorProps = new HashMap<>();
        calculatorProps.put("total", Map.of("type", "number", "description", "直接传入总金额"));
        calculatorProps.put("items", Map.of("type", "array", "description", "商品列表，每项包含 price 和 quantity"));
        calculatorSchema.put("properties", calculatorProps);
        calculatorFunc.put("parameters", calculatorSchema);
        calculator.put("function", calculatorFunc);
        tools.add(calculator);

        // Tool 2: search
        Map<String, Object> search = new HashMap<>();
        search.put("type", "function");
        Map<String, Object> searchFunc = new HashMap<>();
        searchFunc.put("name", "search");
        searchFunc.put("description", "搜索商品信息，返回匹配的商品列表（mock 数据）");
        Map<String, Object> searchSchema = new HashMap<>();
        searchSchema.put("type", "object");
        Map<String, Object> searchProps = new HashMap<>();
        searchProps.put("keyword", Map.of("type", "string", "description", "搜索关键词"));
        searchSchema.put("properties", searchProps);
        searchFunc.put("parameters", searchSchema);
        search.put("function", searchFunc);
        tools.add(search);

        // Tool 3: create_order
        Map<String, Object> createOrder = new HashMap<>();
        createOrder.put("type", "function");
        Map<String, Object> createOrderFunc = new HashMap<>();
        createOrderFunc.put("name", "create_order");
        createOrderFunc.put("description", "创建新订单，返回订单号和状态");
        Map<String, Object> createOrderSchema = new HashMap<>();
        createOrderSchema.put("type", "object");
        Map<String, Object> createOrderProps = new HashMap<>();
        createOrderProps.put("userId", Map.of("type", "string", "description", "用户 ID"));
        createOrderProps.put("items", Map.of("type", "array", "description", "商品列表"));
        createOrderSchema.put("properties", createOrderProps);
        createOrderFunc.put("parameters", createOrderSchema);
        createOrder.put("function", createOrderFunc);
        tools.add(createOrder);

        return tools;
    }

    /**
     * 构建 assistant 消息（带 tool_calls），维持 OpenAI 协议上下文完整性
     * 每次工具调用后必须先追加这条，再追加 role: "tool" 的结果消息
     */
    private Map<String, Object> buildAssistantToolMessage(String toolCallId, String toolName, String arguments) {
        Map<String, Object> function = new HashMap<>();
        function.put("name", toolName);
        function.put("arguments", arguments);

        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", toolCallId);
        toolCall.put("type", "function");
        toolCall.put("function", function);

        Map<String, Object> assistantMessage = new HashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", null);
        assistantMessage.put("tool_calls", List.of(toolCall));

        return assistantMessage;
    }

    /**
     * 构建成功响应
     */
    private ChatResponse buildSuccessResponse(String answer, String reasoning, String toolName) {
        return ChatResponse.builder()
            .answer(answer)
            .reasoning(reasoning)
            .toolName(toolName)
            .build();
    }

    /**
     * 构建错误响应
     */
    private ChatResponse buildErrorResponse(String errorMessage) {
        return ChatResponse.builder()
            .answer(errorMessage)
            .build();
    }
}

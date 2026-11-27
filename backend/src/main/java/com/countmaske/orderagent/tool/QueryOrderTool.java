package com.countmaske.orderagent.tool;

import com.countmaske.orderagent.domain.Order;
import com.countmaske.orderagent.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 工具 4：query_order —— 查询订单 / 支付状态（Agent runtime 核心「读」工具）。
 */
@Component
public class QueryOrderTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderService orderService;

    public QueryOrderTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String name() {
        return "query_order";
    }

    @Override
    public String description() {
        return "查询订单信息与支付状态，返回订单号、状态（CREATED/PAID/CANCELLED）、金额和商品明细";
    }

    @Override
    public JsonNode schema() {
        return mapper.createObjectNode()
            .put("type", "object")
            .set("properties", mapper.createObjectNode()
                .set("orderId", mapper.createObjectNode()
                    .put("type", "string")
                    .put("description", "订单号，如 ORD-xxxxxxxxxxxx")));
    }

    @Override
    public String execute(JsonNode arguments) {
        String orderId = arguments.path("orderId").asText();
        Order order = orderService.queryOrder(orderId);

        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", order.getOrderId());
        node.put("userId", order.getUserId());
        node.put("status", order.getStatus().name());
        node.put("total", Math.round(order.getTotalAmount() * 100.0) / 100.0);
        ArrayNode items = node.putArray("items");
        order.getItems().forEach(line -> {
            ObjectNode item = items.addObject();
            item.put("productId", line.productId());
            item.put("name", line.name());
            item.put("quantity", line.quantity());
        });
        return node.toString();
    }
}

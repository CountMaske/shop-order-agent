package com.countmaske.orderagent.tool;

import com.countmaske.orderagent.domain.CreateOrderResult;
import com.countmaske.orderagent.domain.OrderItem;
import com.countmaske.orderagent.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具 3：create_order —— 下单（Agent runtime 核心「写」工具）。
 *
 * confirm 闸门：confirm=false 只返回预览，不建单不扣库存；
 * confirm=true 才真正下单。LLM 会根据工具返回值向用户确认后再调一次。
 */
@Component
public class CreateOrderTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderService orderService;

    public CreateOrderTool(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String name() {
        return "create_order";
    }

    @Override
    public String description() {
        return "创建订单。必须先设置 confirm=false 获取商品和总价让用户确认；"
            + "用户确认后，再用相同参数把 confirm 设为 true 完成下单。"
            + "重试同一笔订单请传相同的 idempotencyKey，避免重复下单";
    }

    @Override
    public JsonNode schema() {
        ObjectNode itemProps = mapper.createObjectNode();
        itemProps.set("productId", mapper.createObjectNode()
            .put("type", "string").put("description", "商品 ID"));
        itemProps.set("quantity", mapper.createObjectNode()
            .put("type", "integer").put("description", "购买数量"));

        ObjectNode itemSchema = mapper.createObjectNode();
        itemSchema.put("type", "object");
        itemSchema.set("properties", itemProps);

        ObjectNode itemsSchema = mapper.createObjectNode();
        itemsSchema.put("type", "array");
        itemsSchema.put("description", "商品列表，每项含 productId 和 quantity");
        itemsSchema.set("items", itemSchema);

        ObjectNode props = mapper.createObjectNode();
        props.set("items", itemsSchema);
        props.set("userId", mapper.createObjectNode()
            .put("type", "string").put("description", "用户 ID，缺省为 anonymous"));
        props.set("confirm", mapper.createObjectNode()
            .put("type", "boolean").put("description", "是否已获得用户确认。false=仅预览，true=真正下单"));
        props.set("idempotencyKey", mapper.createObjectNode()
            .put("type", "string").put("description", "幂等键，同一笔订单重试时务必传相同值，防止重复下单"));

        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.set("properties", props);
        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        String userId = arguments.path("userId").asText("anonymous");
        boolean confirm = arguments.path("confirm").asBoolean(false);
        String idempotencyKey = arguments.path("idempotencyKey").asText(null);

        List<OrderService.ItemRequest> items = new ArrayList<>();
        for (JsonNode item : arguments.path("items")) {
            items.add(new OrderService.ItemRequest(
                item.path("productId").asText(),
                item.path("quantity").asInt(1)));
        }

        CreateOrderResult result = orderService.createOrder(userId, items, confirm, idempotencyKey);
        return toJson(result);
    }

    private String toJson(CreateOrderResult result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("needConfirm", result.isNeedConfirm());
        node.put("message", result.getMessage());
        if (result.getOrderId() != null) {
            node.put("orderId", result.getOrderId());
            node.put("status", result.getStatus());
        }
        node.put("total", Math.round(result.getTotal() * 100.0) / 100.0);
        ArrayNode items = node.putArray("items");
        for (OrderItem item : result.getItems()) {
            ObjectNode line = items.addObject();
            line.put("productId", item.productId());
            line.put("name", item.name());
            line.put("unitPrice", item.unitPrice());
            line.put("quantity", item.quantity());
        }
        return node.toString();
    }
}

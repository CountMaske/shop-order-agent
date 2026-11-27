package com.countmaske.orderagent.tool;

import com.countmaske.orderagent.domain.Order;
import com.countmaske.orderagent.repository.InventoryRepository;
import com.countmaske.orderagent.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 工具 5：cancel_order —— 取消订单并恢复库存（Agent runtime 核心「写」工具）。
 *
 * 重复取消幂等：cancelOrder 内部对 CANCELLED 状态直接返回，不会二次恢复库存。
 * 已支付订单：拒绝取消，提示走退款流程。
 */
@Component
public class CancelOrderTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderService orderService;
    private final InventoryRepository inventory;

    public CancelOrderTool(OrderService orderService, InventoryRepository inventory) {
        this.orderService = orderService;
        this.inventory = inventory;
    }

    @Override
    public String name() {
        return "cancel_order";
    }

    @Override
    public String description() {
        return "取消未支付的订单并恢复库存。已支付订单不能取消；重复取消同一订单不会重复恢复库存";
    }

    @Override
    public JsonNode schema() {
        return mapper.createObjectNode()
            .put("type", "object")
            .set("properties", mapper.createObjectNode()
                .set("orderId", mapper.createObjectNode()
                    .put("type", "string")
                    .put("description", "要取消的订单号")));
    }

    @Override
    public String execute(JsonNode arguments) {
        String orderId = arguments.path("orderId").asText();
        Order order = orderService.cancelOrder(orderId);

        ObjectNode node = mapper.createObjectNode();
        node.put("orderId", order.getOrderId());
        node.put("status", order.getStatus().name());
        node.put("message", "订单已取消，库存已恢复");
        return node.toString();
    }
}
